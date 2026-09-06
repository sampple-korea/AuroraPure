/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.pure.data.AppSummary
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ConnectionState
import com.aurora.pure.data.DownloadConfirmation
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.PureUiState
import com.aurora.pure.data.Screen
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.download.DownloadCoordinator
import com.aurora.pure.play.AuroraGateway
import com.aurora.pure.storage.ExportRepository
import com.aurora.pure.storage.RecordRepository
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PureViewModel(application: Application) : AndroidViewModel(application) {
    private val recordRepository = RecordRepository(application)
    private val gateway = AuroraGateway(application)
    private val exporter = ExportRepository(application)
    private val coordinator = DownloadCoordinator(application, gateway.httpClient, recordRepository)

    private val restoredRecords = recordRepository.load()
    private val _uiState = MutableStateFlow(
        PureUiState(
            records = restoredRecords.sortedByDescending { it.createdAt },
            architectureChoice = recordRepository.architectureChoice,
            themeMode = recordRepository.themeMode,
            keepScreenOn = recordRepository.keepScreenOn,
            customFolderUri = recordRepository.customFolderUri
        )
    )
    val uiState: StateFlow<PureUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeTaskId: String? = null
    private var cancelRequestedTaskId: String? = null
    private var foreground = true

    init {
        recordRepository.save(restoredRecords)
        viewModelScope.launch(Dispatchers.IO) { exporter.cleanupAbandonedExports() }
    }

    fun setQuery(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun submitSearch() {
        val input = _uiState.value.query.trim()
        if (input.isBlank() || _uiState.value.busy) return
        parsePackageName(input)?.let {
            openDetails(it)
            return
        }
        viewModelScope.launch {
            setBusy(true)
            runCatching { gateway.search(input) }
                .onSuccess { results ->
                    _uiState.update {
                        it.copy(
                            results = results,
                            message = if (results.isEmpty()) string(R.string.no_results) else ""
                        )
                    }
                }
                .onFailure(::showError)
            setBusy(false)
        }
    }

    fun acceptSharedText(text: String) {
        setQuery(text)
        parsePackageName(text)?.let(::openDetails)
    }

    fun openDetails(packageName: String) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            setBusy(true)
            runCatching { gateway.details(packageName) }
                .onSuccess { app ->
                    _uiState.update { it.copy(selected = app, screen = Screen.DETAILS, message = "") }
                }
                .onFailure(::showError)
            setBusy(false)
        }
    }

    fun prepareDownload() {
        val selected = _uiState.value.selected ?: return
        if (!selected.isFree || _uiState.value.busy) return
        val architectureChoice = _uiState.value.architectureChoice
        viewModelScope.launch {
            Log.i(TAG, "Preparing delivery for ${selected.packageName}")
            setBusy(true)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTING) }
            runCatching { gateway.resolvePlan(selected.packageName, architectureChoice) }
                .onSuccess { plan ->
                    Log.i(
                        TAG,
                        "Delivery resolved for ${plan.packageName}: versionCode=${plan.versionCode}, " +
                            "architecture=${plan.architectureChoice}, apkCount=${plan.artifacts.size}, " +
                            "bytes=${plan.totalBytes}"
                    )
                    _uiState.update { it.copy(connection = ConnectionState.CONNECTED) }
                    val changed = (selected.versionCode > 0 && plan.versionCode != selected.versionCode) ||
                        (selected.size > 0 && plan.totalBytes > 0 && selected.size != plan.totalBytes)
                    _uiState.update {
                        it.copy(
                            confirmation = DownloadConfirmation(
                                previous = selected,
                                plan = plan,
                                reason = if (changed) {
                                    "${selected.versionName} (${selected.versionCode}) → " +
                                        "${plan.versionName} (${plan.versionCode})"
                                } else {
                                    ""
                                },
                                changed = changed
                            )
                        )
                    }
                }
                .onFailure {
                    Log.w(
                        TAG,
                        "Delivery resolution failed (${it.javaClass.simpleName}): ${safeMessage(it)}"
                    )
                    _uiState.update { state -> state.copy(connection = ConnectionState.FAILED) }
                    showError(it)
                }
            setBusy(false)
        }
    }

    fun confirmDownload() {
        val confirmation = _uiState.value.confirmation ?: return
        _uiState.update { it.copy(confirmation = null) }
        acceptPlan(confirmation.plan)
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(confirmation = null) }
    }

    private fun acceptPlan(plan: DownloadPlan) {
        val replacing = _uiState.value.records.firstOrNull { it.id == plan.id }
        if (replacing != null) {
            val replacement = plan.toRecord().copy(
                id = replacing.id,
                createdAt = replacing.createdAt
            )
            viewModelScope.launch {
                setBusy(true)
                withContext(Dispatchers.IO) { recordRepository.deleteTaskFiles(replacing.id) }
                replaceOrAdd(replacement)
                _uiState.update { it.copy(screen = Screen.DOWNLOADS) }
                setBusy(false)
                if (activeJob == null && foreground) launchRecord(replacement)
            }
            return
        }
        val existing = _uiState.value.records.firstOrNull {
            it.planFingerprint == plan.fingerprint() && it.status == TaskStatus.COMPLETED
        }
        if (existing != null && recordRepository.outputExists(existing)) {
            _uiState.update {
                it.copy(screen = Screen.DOWNLOADS, message = string(R.string.message_same_saved))
            }
            return
        }
        val duplicate = _uiState.value.records.firstOrNull {
            it.planFingerprint == plan.fingerprint() && it.status.isActive
        }
        if (duplicate != null) {
            _uiState.update {
                it.copy(screen = Screen.DOWNLOADS, message = string(R.string.message_already_queued))
            }
            return
        }

        val stablePlan = plan.copy(id = UUID.randomUUID().toString())
        val record = stablePlan.toRecord()
        replaceOrAdd(record)
        _uiState.update { it.copy(screen = Screen.DOWNLOADS) }
        if (activeJob == null && foreground) launchRecord(record)
    }

    private fun launchRecord(record: DownloadRecord) {
        if (activeJob != null || !foreground) return
        activeTaskId = record.id
        activeJob = viewModelScope.launch {
            runRecord(record)
        }
    }

    private suspend fun runRecord(original: DownloadRecord) {
        var record = original
        try {
            updateRecord(record.id) { it.copy(status = TaskStatus.CHECKING, error = "") }
            val latest = gateway.resolvePlan(
                record.packageName,
                record.architectureChoice
            ).copy(id = record.id)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTED) }
            if (latest.fingerprint() != record.planFingerprint) {
                updateRecord(record.id) {
                    it.copy(
                        status = TaskStatus.VERSION_CHANGED,
                        error = string(R.string.message_delivery_changed)
                    )
                }
                return
            }

            record = currentRecord(record.id) ?: return
            updateRecord(record.id) {
                it.copy(
                    status = TaskStatus.DOWNLOADING,
                    totalBytes = latest.totalBytes,
                    totalFiles = latest.artifacts.size,
                    checkedAt = latest.checkedAt
                )
            }

            var lastUiUpdate = 0L
            var lastPersist = 0L
            val outcome = coordinator.execute(
                latest,
                onProgress = { bytes, files ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdate >= 200 || bytes == latest.totalBytes) {
                        val persist = now - lastPersist >= 1000 || bytes == latest.totalBytes
                        updateRecord(record.id, persist) {
                            it.copy(downloadedBytes = bytes, completedFiles = files)
                        }
                        lastUiUpdate = now
                        if (persist) lastPersist = now
                    }
                },
                onVerifying = {
                    updateRecord(record.id) { it.copy(status = TaskStatus.VERIFYING) }
                }
            )

            updateRecord(record.id) {
                it.copy(status = TaskStatus.EXPORTING, verification = outcome.verification.summary())
            }
            val exported = exporter.export(outcome, recordRepository.customFolderUri)
            updateRecord(record.id) {
                it.copy(
                    status = TaskStatus.COMPLETED,
                    downloadedBytes = latest.totalBytes,
                    completedFiles = latest.artifacts.size,
                    completedAt = System.currentTimeMillis(),
                    outputUri = exported.uri,
                    outputName = exported.displayName,
                    outputSize = exported.size,
                    verification = outcome.verification.summary(),
                    error = ""
                )
            }
            recordRepository.deleteTaskFiles(record.id)
        } catch (exception: DownloadCoordinator.PauseRequestedException) {
            updateRecord(record.id) { it.copy(status = TaskStatus.PAUSED, error = "") }
        } catch (exception: DownloadCoordinator.CancelRequestedException) {
            updateRecord(record.id) { it.copy(status = TaskStatus.CANCELLED, error = "") }
        } catch (exception: CancellationException) {
            if (cancelRequestedTaskId == record.id) {
                withContext(NonCancellable + Dispatchers.IO) {
                    recordRepository.deleteTaskFiles(record.id)
                }
                updateRecord(record.id) { it.copy(status = TaskStatus.CANCELLED, error = "") }
            } else {
                updateRecord(record.id) { it.copy(status = TaskStatus.PAUSED, error = "") }
            }
            throw exception
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Download task failed (${exception.javaClass.simpleName}): ${safeMessage(exception)}"
            )
            updateRecord(record.id) {
                it.copy(status = TaskStatus.FAILED, error = userMessage(exception))
            }
        } finally {
            activeTaskId = null
            activeJob = null
            if (cancelRequestedTaskId == record.id) cancelRequestedTaskId = null
            if (foreground) {
                currentRecords().firstOrNull { it.status == TaskStatus.QUEUED }?.let(::launchRecord)
            }
        }
    }

    fun pause(record: DownloadRecord) {
        if (record.id == activeTaskId) coordinator.pause()
    }

    fun resume(record: DownloadRecord) {
        if (activeJob != null || !foreground) return
        viewModelScope.launch {
            setBusy(true)
            runCatching {
                gateway.resolvePlan(record.packageName, record.architectureChoice).copy(id = record.id)
            }
                .onSuccess { plan ->
                    if (plan.fingerprint() != record.planFingerprint) {
                        _uiState.update {
                            it.copy(
                                confirmation = DownloadConfirmation(
                                    previous = record.toSummary(),
                                    plan = plan,
                                    reason = "${record.versionName} (${record.versionCode}) → " +
                                        "${plan.versionName} (${plan.versionCode})",
                                    changed = true
                                )
                            )
                        }
                    } else {
                        updateRecord(record.id) { it.copy(status = TaskStatus.QUEUED, error = "") }
                        currentRecord(record.id)?.let(::launchRecord)
                    }
                }
                .onFailure(::showError)
            setBusy(false)
        }
    }

    fun cancel(record: DownloadRecord) {
        if (record.id == activeTaskId) {
            cancelRequestedTaskId = record.id
            coordinator.cancel()
            activeJob?.cancel()
        } else {
            updateRecord(record.id) { it.copy(status = TaskStatus.CANCELLED, error = "") }
            viewModelScope.launch(Dispatchers.IO) { recordRepository.deleteTaskFiles(record.id) }
        }
    }

    fun removeRecord(record: DownloadRecord) {
        if (record.id == activeTaskId) return
        val updated = currentRecords().filterNot { it.id == record.id }
        publishRecords(updated)
        viewModelScope.launch(Dispatchers.IO) { recordRepository.deleteTaskFiles(record.id) }
    }

    fun deleteOutput(record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = recordRepository.deleteOutput(record)
            withContext(Dispatchers.Main) {
                updateRecord(record.id) {
                    it.copy(
                        outputUri = "",
                        outputName = "",
                        outputSize = 0,
                        error = if (deleted) {
                            string(R.string.message_saved_deleted)
                        } else {
                            string(R.string.file_missing)
                        }
                    )
                }
            }
        }
    }

    fun verifyOutput(record: DownloadRecord): Boolean {
        val exists = recordRepository.outputExists(record)
        if (!exists && record.outputUri.isNotBlank()) {
            updateRecord(record.id) {
                it.copy(
                    outputUri = "",
                    outputName = "",
                    outputSize = 0,
                    error = string(R.string.file_missing)
                )
            }
        }
        return exists
    }

    fun onForegroundChanged(isForeground: Boolean) {
        foreground = isForeground
        if (!isForeground && activeTaskId != null) {
            coordinator.pause()
            activeJob?.cancel()
        }
    }

    fun navigate(screen: Screen) {
        _uiState.update { it.copy(screen = screen, message = "") }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value.screen
        if (current == Screen.SEARCH) return false
        _uiState.update {
            it.copy(screen = if (current == Screen.ABOUT) Screen.SETTINGS else Screen.SEARCH)
        }
        return true
    }

    fun setThemeMode(mode: Int) {
        recordRepository.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        recordRepository.keepScreenOn = enabled
        _uiState.update { it.copy(keepScreenOn = enabled) }
    }

    fun setArchitectureChoice(choice: ArchitectureChoice) {
        recordRepository.architectureChoice = choice
        _uiState.update { it.copy(architectureChoice = choice) }
    }

    fun setCustomFolder(uri: String) {
        recordRepository.customFolderUri = uri
        _uiState.update {
            it.copy(customFolderUri = uri, message = string(R.string.message_save_location_updated))
        }
    }

    fun useDefaultFolder() = setCustomFolder("")

    fun clearTemporaryFiles() {
        if (activeJob != null) {
            _uiState.update { it.copy(message = string(R.string.message_active_download_first)) }
            return
        }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                val filesCleared = recordRepository.clearTemporaryFiles()
                exporter.cleanupAbandonedExports()
                filesCleared
            }
            _uiState.update {
                it.copy(
                    message = if (success) {
                        string(R.string.message_temporary_cleared)
                    } else {
                        string(R.string.message_temporary_partial)
                    }
                )
            }
        }
    }

    fun clearHistory() {
        val keep = currentRecords().filter {
            it.status in setOf(TaskStatus.QUEUED, TaskStatus.CHECKING, TaskStatus.DOWNLOADING, TaskStatus.PAUSED, TaskStatus.VERIFYING, TaskStatus.EXPORTING)
        }
        val removed = currentRecords().filterNot { it in keep }
        publishRecords(keep)
        _uiState.update { it.copy(message = string(R.string.message_history_cleared)) }
        viewModelScope.launch(Dispatchers.IO) {
            removed.forEach { recordRepository.deleteTaskFiles(it.id) }
        }
    }

    fun reconnect() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            setBusy(true)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTING) }
            gateway.disconnect()
            runCatching {
                gateway.connectProfiles(_uiState.value.architectureChoice, force = true)
            }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            connection = ConnectionState.CONNECTED,
                            message = string(R.string.message_connection_ready)
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(connection = ConnectionState.FAILED) }
                    showError(it)
                }
            setBusy(false)
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = "") }
    }

    private fun DownloadPlan.toRecord() = DownloadRecord(
        id = id,
        packageName = packageName,
        displayName = displayName,
        versionName = versionName,
        versionCode = versionCode,
        architectureChoice = architectureChoice,
        planFingerprint = fingerprint(),
        checkedAt = checkedAt,
        createdAt = System.currentTimeMillis(),
        status = TaskStatus.QUEUED,
        totalBytes = totalBytes,
        totalFiles = artifacts.size,
        hasAdditionalData = hasAdditionalData
    )

    private fun DownloadRecord.toSummary() = AppSummary(
        packageName = packageName,
        displayName = displayName,
        developerName = "",
        iconUrl = "",
        versionName = versionName,
        versionCode = versionCode,
        size = totalBytes,
        isFree = true,
        shortDescription = ""
    )

    private fun replaceOrAdd(record: DownloadRecord) {
        val current = currentRecords().toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) current[index] = record else current.add(0, record)
        publishRecords(current)
    }

    private fun updateRecord(
        id: String,
        persist: Boolean = true,
        transform: (DownloadRecord) -> DownloadRecord
    ) {
        var updatedRecords: List<DownloadRecord>? = null
        _uiState.update { state ->
            val updated = state.records.map { if (it.id == id) transform(it) else it }
            updatedRecords = updated
            state.copy(records = updated)
        }
        if (persist) updatedRecords?.let(recordRepository::save)
    }

    private fun publishRecords(records: List<DownloadRecord>) {
        val sorted = records.sortedByDescending { it.createdAt }
        _uiState.update { it.copy(records = sorted) }
        recordRepository.save(sorted)
    }

    private fun currentRecords() = _uiState.value.records
    private fun currentRecord(id: String) = currentRecords().firstOrNull { it.id == id }

    private fun setBusy(value: Boolean) {
        _uiState.update { it.copy(busy = value) }
    }

    private fun string(@StringRes id: Int): String = getApplication<Application>().getString(id)

    private fun showError(throwable: Throwable) {
        _uiState.update { it.copy(message = userMessage(throwable)) }
    }

    private fun userMessage(throwable: Throwable): String {
        val causes = generateSequence(throwable) { it.cause }.toList()
        if (causes.any { it is UnknownHostException || it is SocketTimeoutException || it is SocketException }) {
            return string(R.string.error_network)
        }
        val raw = causes.mapNotNull { it.message }.firstOrNull(String::isNotBlank)
            ?: return string(R.string.message_operation_failed)
        return when {
            raw == "Anonymous connection rejected the device profile" ->
                string(R.string.error_anonymous_profile)
            raw == "Anonymous connection is unavailable for this network" ->
                string(R.string.error_anonymous_unavailable)
            raw == "Anonymous connection service was not found" ->
                string(R.string.error_anonymous_not_found)
            raw.startsWith("Anonymous connection is rate limited") ->
                string(R.string.error_rate_limited)
            raw == "Anonymous connection service is under maintenance" ->
                string(R.string.error_maintenance)
            raw == "Anonymous connection returned incomplete credentials" ->
                string(R.string.error_incomplete_credentials)
            raw == "Google Play did not create a usable anonymous session" ->
                string(R.string.error_session_unavailable)
            raw == "Google Play returned a different package" ->
                string(R.string.error_wrong_package)
            raw == "Paid apps are not supported by Aurora Pure" ->
                string(R.string.paid_unavailable)
            raw == "Google Play returned no APK files for this device" ->
                string(R.string.error_no_apk)
            raw == "Google Play returned no base APK for a package" ->
                string(R.string.error_no_base_apk)
            raw == "Google Play did not return every declared language split" ||
                raw == "Google Play could not resolve declared language splits" ->
                string(R.string.error_language_splits)
            raw == "Google Play returned different versions across selected architectures" ->
                string(R.string.error_architecture_versions)
            raw == "Rejected a non-Google or non-HTTPS delivery URL" ||
                raw == "Download URL is outside the approved Google delivery hosts" ||
                raw == "Download redirect left the approved Google delivery hosts" ->
                string(R.string.error_delivery_security)
            raw == "Google Play returned conflicting APK file names" ->
                string(R.string.error_conflicting_files)
            raw == "Not enough temporary storage for this download" ->
                string(R.string.error_temporary_space)
            raw == "Not enough space to save the completed file" ->
                string(R.string.error_output_space)
            raw.startsWith("Downloaded APK verification failed") ->
                string(R.string.error_apk_verification)
            raw.startsWith("Server rejected a safe resume") ->
                string(R.string.error_safe_resume)
            raw.startsWith("Size mismatch for ") ->
                string(R.string.error_size_mismatch)
            raw.startsWith("Could not finalize ") && raw != "Could not finalize the exported file" ->
                string(R.string.error_finalize_download)
            raw == "The selected folder is no longer available" ->
                string(R.string.error_folder_unavailable)
            raw == "The selected folder is read-only" ->
                string(R.string.error_folder_read_only)
            raw == "Android could not create the download file" ||
                raw == "Could not create a file in the selected folder" ->
                string(R.string.error_create_output)
            raw == "Could not finalize the exported file" ->
                string(R.string.error_finalize_output)
            raw == "Android could not open the saved file" ||
                raw == "Android could not reopen the saved file" ||
                raw.startsWith("Saved file verification failed") ||
                raw.startsWith("Saved archive ") ->
                string(R.string.error_saved_verification)
            raw.startsWith("Download failed (HTTP ") -> {
                val status = Regex("HTTP (\\d+)").find(raw)?.groupValues?.getOrNull(1) ?: "?"
                getApplication<Application>().getString(R.string.error_http, status)
            }
            raw.startsWith("Google Play request failed (HTTP ") -> {
                val status = Regex("HTTP (\\d+)").find(raw)?.groupValues?.getOrNull(1) ?: "?"
                if (status == "429") string(R.string.error_rate_limited) else {
                    getApplication<Application>().getString(R.string.error_play_http, status)
                }
            }
            else -> string(R.string.message_operation_failed)
        }
    }

    private fun safeMessage(throwable: Throwable): String {
        val raw = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull(String::isNotBlank)
            ?: return string(R.string.message_operation_failed)
        return raw
            .replace(URL_PATTERN, "[redacted URL]")
            .replace(EMAIL_PATTERN, "[redacted email]")
            .replace(SECRET_FIELD_PATTERN, "$1=[redacted]")
            .take(240)
    }

    companion object {
        private const val TAG = "AuroraPure"
        private val URL_PATTERN = Regex("(?i)https?://\\S+")
        private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val SECRET_FIELD_PATTERN = Regex(
            "(?i)\\b(auth(?:orization|token)?|token|cookie|email)\\s*[=:]\\s*[^\\s,;]+"
        )
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun parsePackageName(input: String): String? {
            val trimmed = input.trim()
            if (PACKAGE_PATTERN.matches(trimmed)) return trimmed
            val id = Regex("(?:[?&]id=|market://details\\?id=)([A-Za-z][A-Za-z0-9_.]+)")
                .find(trimmed)?.groupValues?.getOrNull(1)
            return id?.takeIf(PACKAGE_PATTERN::matches)
        }
    }
}
