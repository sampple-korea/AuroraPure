/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.pure.data.AppSummary
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            themeMode = recordRepository.themeMode,
            keepScreenOn = recordRepository.keepScreenOn,
            customFolderUri = recordRepository.customFolderUri
        )
    )
    val uiState: StateFlow<PureUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeTaskId: String? = null
    private var foreground = true

    init {
        recordRepository.save(restoredRecords)
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
                            message = if (results.isEmpty()) "No matching apps were found" else ""
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
        viewModelScope.launch {
            setBusy(true)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTING) }
            runCatching { gateway.resolvePlan(selected.packageName) }
                .onSuccess { plan ->
                    _uiState.update { it.copy(connection = ConnectionState.CONNECTED) }
                    val changed = plan.versionCode != selected.versionCode ||
                        (selected.size > 0 && plan.totalBytes > 0 && selected.size != plan.totalBytes)
                    if (changed) {
                        _uiState.update {
                            it.copy(
                                confirmation = DownloadConfirmation(
                                    previous = selected,
                                    plan = plan,
                                    reason = "${selected.versionName} (${selected.versionCode}) → " +
                                        "${plan.versionName} (${plan.versionCode})"
                                )
                            )
                        }
                    } else {
                        acceptPlan(plan)
                    }
                }
                .onFailure {
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
            recordRepository.deleteTaskFiles(replacing.id)
            val replacement = plan.toRecord().copy(
                id = replacing.id,
                createdAt = replacing.createdAt
            )
            replaceOrAdd(replacement)
            _uiState.update { it.copy(screen = Screen.DOWNLOADS) }
            if (activeJob == null && foreground) launchRecord(replacement)
            return
        }
        val existing = _uiState.value.records.firstOrNull {
            it.planFingerprint == plan.fingerprint() && it.status == TaskStatus.COMPLETED
        }
        if (existing != null && recordRepository.outputExists(existing)) {
            _uiState.update {
                it.copy(screen = Screen.DOWNLOADS, message = "The same delivered APK files are already saved")
            }
            return
        }
        val duplicate = _uiState.value.records.firstOrNull {
            it.planFingerprint == plan.fingerprint() && it.status.isActive
        }
        if (duplicate != null) {
            _uiState.update { it.copy(screen = Screen.DOWNLOADS, message = "This download is already queued") }
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
            val latest = gateway.resolvePlan(record.packageName).copy(id = record.id)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTED) }
            if (latest.fingerprint() != record.planFingerprint) {
                updateRecord(record.id) {
                    it.copy(
                        status = TaskStatus.VERSION_CHANGED,
                        error = "The delivered version or APK set changed; review it before resuming"
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
            updateRecord(record.id) { it.copy(status = TaskStatus.PAUSED, error = "") }
            throw exception
        } catch (exception: Exception) {
            updateRecord(record.id) {
                it.copy(status = TaskStatus.FAILED, error = safeMessage(exception))
            }
        } finally {
            activeTaskId = null
            activeJob = null
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
            runCatching { gateway.resolvePlan(record.packageName).copy(id = record.id) }
                .onSuccess { plan ->
                    if (plan.fingerprint() != record.planFingerprint) {
                        _uiState.update {
                            it.copy(
                                confirmation = DownloadConfirmation(
                                    previous = record.toSummary(),
                                    plan = plan,
                                    reason = "${record.versionName} (${record.versionCode}) → " +
                                        "${plan.versionName} (${plan.versionCode})"
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
            coordinator.cancel()
        } else {
            recordRepository.deleteTaskFiles(record.id)
            updateRecord(record.id) { it.copy(status = TaskStatus.CANCELLED, error = "") }
        }
    }

    fun removeRecord(record: DownloadRecord) {
        if (record.id == activeTaskId) return
        val updated = currentRecords().filterNot { it.id == record.id }
        publishRecords(updated)
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
                        error = if (deleted) "Saved file deleted" else "Saved file could not be found"
                    )
                }
            }
        }
    }

    fun verifyOutput(record: DownloadRecord): Boolean {
        val exists = recordRepository.outputExists(record)
        if (!exists && record.outputUri.isNotBlank()) {
            updateRecord(record.id) { it.copy(error = "Saved file could not be found") }
        }
        return exists
    }

    fun onForegroundChanged(isForeground: Boolean) {
        foreground = isForeground
        if (!isForeground && activeTaskId != null) coordinator.pause()
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

    fun setCustomFolder(uri: String) {
        recordRepository.customFolderUri = uri
        _uiState.update { it.copy(customFolderUri = uri, message = "Save location updated") }
    }

    fun useDefaultFolder() = setCustomFolder("")

    fun clearTemporaryFiles() {
        if (activeJob != null) {
            _uiState.update { it.copy(message = "Pause or cancel the active download first") }
            return
        }
        val success = recordRepository.clearTemporaryFiles()
        _uiState.update { it.copy(message = if (success) "Temporary files cleared" else "Some temporary files could not be cleared") }
    }

    fun clearHistory() {
        val keep = currentRecords().filter {
            it.status in setOf(TaskStatus.QUEUED, TaskStatus.CHECKING, TaskStatus.DOWNLOADING, TaskStatus.PAUSED, TaskStatus.VERIFYING, TaskStatus.EXPORTING)
        }
        publishRecords(keep)
        _uiState.update { it.copy(message = "Download history cleared; saved files were kept") }
    }

    fun reconnect() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            setBusy(true)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTING) }
            gateway.disconnect()
            runCatching { gateway.connect(force = true) }
                .onSuccess { _uiState.update { state -> state.copy(connection = ConnectionState.CONNECTED, message = "Anonymous connection ready") } }
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

    private fun showError(throwable: Throwable) {
        _uiState.update { it.copy(message = safeMessage(throwable)) }
    }

    private fun safeMessage(throwable: Throwable): String =
        throwable.message?.takeIf(String::isNotBlank)?.take(240) ?: "The operation failed"

    companion object {
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
