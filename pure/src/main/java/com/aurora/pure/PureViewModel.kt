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
import com.aurora.pure.data.ConfirmAction
import com.aurora.pure.data.ConnectionState
import com.aurora.pure.data.DiscoveryProgress
import com.aurora.pure.data.DownloadConfirmation
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.DensityChoice
import com.aurora.pure.data.MessageAction
import com.aurora.pure.data.PendingConfirm
import com.aurora.pure.data.PureUiState
import com.aurora.pure.data.Screen
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.data.UiMessage
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PureViewModel(application: Application) : AndroidViewModel(application) {
    private val recordRepository = RecordRepository(application)
    private val gateway = AuroraGateway(application)
    private val exporter = ExportRepository(application)
    private val coordinator = DownloadCoordinator(application, gateway.httpClient, recordRepository)

    private val _uiState = MutableStateFlow(
        PureUiState(
            architectureChoice = ArchitectureChoice.UNIVERSAL,
            densityChoice = DensityChoice.ALL,
            themeMode = recordRepository.themeMode,
            dynamicColor = recordRepository.dynamicColor,
            keepScreenOn = recordRepository.keepScreenOn,
            customFolderUri = recordRepository.customFolderUri
        )
    )
    val uiState: StateFlow<PureUiState> = _uiState.asStateFlow()

    /**
     * Persisting the record list means serialising every record to JSON. A running download used to
     * do that on the main thread once a second; requests are now conflated onto an IO worker, so a
     * burst of progress ticks costs one write of the newest snapshot rather than one write each.
     */
    private val persistRequests = MutableSharedFlow<List<DownloadRecord>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var activeJob: Job? = null
    private var discoveryJob: Job? = null
    private var searchJob: Job? = null
    private var activeTaskId: String? = null
    private var cancelRequestedTaskId: String? = null
    private var foreground = true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            persistRequests.collectLatest(recordRepository::save)
        }
        viewModelScope.launch {
            // Reading and re-parsing the stored history is disk work, so the first frame is no
            // longer waiting on it. Only the Downloads screen shows records, and it renders a
            // restoring state until they arrive.
            val restored = withContext(Dispatchers.IO) {
                val records = recordRepository.load().sortedByDescending(DownloadRecord::createdAt)
                recordRepository.save(records)
                records to recordRepository.recentQueries
            }
            _uiState.update {
                it.copy(
                    records = restored.first,
                    recentQueries = restored.second,
                    restoringRecords = false
                )
            }
        }
        viewModelScope.launch(Dispatchers.IO) { exporter.cleanupAbandonedExports() }
    }

    fun setQuery(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun clearQuery() {
        _uiState.update { it.copy(query = "", results = emptyList(), searched = false) }
    }

    fun submitSearch(input: String = _uiState.value.query) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return
        if (trimmed != _uiState.value.query) setQuery(trimmed)
        parsePackageName(trimmed)?.let {
            rememberQuery(trimmed)
            openDetails(it)
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(searching = true) }
            runCatching { gateway.search(trimmed) }
                .onSuccess { results ->
                    rememberQuery(trimmed)
                    _uiState.update {
                        it.copy(
                            results = results,
                            searched = true,
                            message = if (results.isEmpty()) {
                                UiMessage(string(R.string.no_results))
                            } else {
                                it.message
                            }
                        )
                    }
                }
                .onFailure { showError(it, MessageAction.RETRY_SEARCH) }
            _uiState.update { it.copy(searching = false) }
            searchJob = null
        }
    }

    private fun rememberQuery(query: String) {
        val updated = (listOf(query) + _uiState.value.recentQueries)
            .distinct()
            .take(RecordRepository.MAX_RECENT_QUERIES)
        if (updated == _uiState.value.recentQueries) return
        _uiState.update { it.copy(recentQueries = updated) }
        viewModelScope.launch(Dispatchers.IO) { recordRepository.recentQueries = updated }
    }

    fun removeRecentQuery(query: String) {
        val updated = _uiState.value.recentQueries.filterNot { it == query }
        _uiState.update { it.copy(recentQueries = updated) }
        viewModelScope.launch(Dispatchers.IO) { recordRepository.recentQueries = updated }
    }

    fun acceptSharedText(text: String) {
        setQuery(text)
        parsePackageName(text)?.let(::openDetails)
    }

    fun openDetails(packageName: String) {
        discoveryJob?.cancel()
        // A cached summary paints the header immediately, so opening an app is never a blank screen
        // waiting on the network.
        val cached = gateway.cachedDetails(packageName)
            ?: _uiState.value.results.firstOrNull { it.packageName == packageName }
        _uiState.update {
            it.copy(
                selected = cached,
                variants = emptyList(),
                selectedVariantId = "",
                discovery = DiscoveryProgress(),
                discoveryFailed = false,
                screen = Screen.DETAILS
            )
        }
        viewModelScope.launch {
            setBusy(true)
            var discoverAfterLoading = false
            runCatching { gateway.details(packageName) }
                .onSuccess { app ->
                    discoverAfterLoading = app.isFree
                    // Tapping a second result before the first resolves must not let the slower
                    // response overwrite the app the user is actually looking at.
                    _uiState.update {
                        if (it.selected?.packageName != packageName) it else it.copy(selected = app)
                    }
                }
                .onFailure {
                    if (cached == null && _uiState.value.selected == null) {
                        _uiState.update { state -> state.copy(screen = Screen.SEARCH) }
                    }
                    showError(it)
                }
            setBusy(false)
            if (discoverAfterLoading && _uiState.value.selected?.packageName == packageName) {
                discoverVariants()
            }
        }
    }

    fun prepareDownload() {
        val state = _uiState.value
        val selected = state.selected ?: return
        if (!selected.isFree || state.busy) return
        val variant = state.selectedVariant
        if (variant == null) {
            if (!state.discoveringVariants) discoverVariants()
            return
        }
        val architectureChoice = variant.downloadArchitectureChoice
        val densityChoice = variant.downloadDensityChoice
        viewModelScope.launch {
            Log.i(TAG, "Preparing delivery for ${selected.packageName}")
            setBusy(true)
            _uiState.update { it.copy(connection = ConnectionState.CONNECTING) }
            runCatching {
                gateway.resolvePlan(
                    packageName = selected.packageName,
                    architectureChoice = architectureChoice,
                    densityChoice = densityChoice,
                    selectedProfiles = variant.downloadProfiles
                )
            }
                .onSuccess { plan ->
                    Log.i(
                        TAG,
                        "Delivery resolved for ${plan.packageName}: versionCode=${plan.versionCode}, " +
                            "architecture=${plan.architectureChoice}, apkCount=${plan.artifacts.size}, " +
                            "bytes=${plan.totalBytes}"
                    )
                    val changed = (selected.versionCode > 0 && plan.versionCode != selected.versionCode) ||
                        (selected.size > 0 && plan.totalBytes > 0 && selected.size != plan.totalBytes)
                    _uiState.update {
                        it.copy(
                            connection = ConnectionState.CONNECTED,
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

    fun discoverVariants() {
        val selected = _uiState.value.selected ?: return
        if (!selected.isFree) return
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connection = ConnectionState.CONNECTING,
                    discoveringVariants = true,
                    discoveryFailed = false,
                    discoveryIncomplete = false,
                    variants = emptyList(),
                    selectedVariantId = "",
                    discovery = DiscoveryProgress(),
                    architectureChoice = ArchitectureChoice.UNIVERSAL,
                    densityChoice = DensityChoice.ALL
                )
            }
            try {
                val result = gateway.discoverVariants(
                    packageName = selected.packageName,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            if (state.selected?.packageName != selected.packageName) {
                                state
                            } else {
                                state.copy(discovery = progress)
                            }
                        }
                    },
                    // Results land in the list as each independent path returns instead of after
                    // the whole matrix finishes, so the first usable rows appear in a second or two.
                    onPartialResults = { partial ->
                        _uiState.update { state ->
                            if (state.selected?.packageName != selected.packageName) {
                                state
                            } else {
                                state.copy(
                                    variants = partial,
                                    connection = ConnectionState.CONNECTED,
                                    selectedVariantId = state.selectedVariantId
                                        .takeIf { id -> partial.any { it.id == id } }
                                        .orEmpty()
                                )
                            }
                        }
                    }
                )
                if (_uiState.value.selected?.packageName == selected.packageName) {
                    _uiState.update {
                        it.copy(
                            connection = ConnectionState.CONNECTED,
                            variants = result.variants,
                            discoveryIncomplete = result.incomplete,
                            selectedVariantId = it.selectedVariantId
                                .takeIf { id -> result.variants.any { variant -> variant.id == id } }
                                .orEmpty()
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { state ->
                    state.copy(connection = ConnectionState.FAILED, discoveryFailed = true)
                }
                showError(exception, MessageAction.RETRY_DISCOVERY)
            } finally {
                if (_uiState.value.selected?.packageName == selected.packageName) {
                    _uiState.update { it.copy(discoveringVariants = false) }
                }
                discoveryJob = null
            }
        }
    }

    fun selectVariant(id: String) {
        if (_uiState.value.variants.any { it.id == id }) {
            _uiState.update { it.copy(selectedVariantId = id) }
        }
    }

    fun clearVariantSelection() {
        _uiState.update { it.copy(selectedVariantId = "") }
    }

    fun confirmDownload() {
        val confirmation = _uiState.value.confirmation ?: return
        _uiState.update { it.copy(confirmation = null) }
        viewModelScope.launch { acceptPlan(confirmation.plan) }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(confirmation = null) }
    }

    fun requestConfirm(action: ConfirmAction, record: DownloadRecord? = null) {
        _uiState.update { it.copy(pendingConfirm = PendingConfirm(action, record)) }
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(pendingConfirm = null) }
    }

    fun runPendingConfirm() {
        val pending = _uiState.value.pendingConfirm ?: return
        _uiState.update { it.copy(pendingConfirm = null) }
        when (pending.action) {
            ConfirmAction.CLEAR_HISTORY -> clearHistory()
            ConfirmAction.CLEAR_TEMPORARY -> clearTemporaryFiles()
            ConfirmAction.DELETE_OUTPUT -> pending.record?.let(::deleteOutput)
            ConfirmAction.REMOVE_RECORD -> pending.record?.let(::removeRecord)
            ConfirmAction.CANCEL_DOWNLOAD -> pending.record?.let(::cancel)
        }
    }

    private suspend fun acceptPlan(plan: DownloadPlan) {
        val replacing = _uiState.value.records.firstOrNull { it.id == plan.id }
        if (replacing != null) {
            val replacement = plan.toRecord().copy(
                id = replacing.id,
                createdAt = replacing.createdAt
            )
            setBusy(true)
            withContext(Dispatchers.IO) { recordRepository.deleteTaskFiles(replacing.id) }
            replaceOrAdd(replacement)
            _uiState.update { it.copy(screen = Screen.DOWNLOADS) }
            setBusy(false)
            if (activeJob == null && foreground) launchRecord(replacement)
            return
        }
        val fingerprint = plan.fingerprint()
        val existing = _uiState.value.records.firstOrNull {
            it.planFingerprint == fingerprint && it.status == TaskStatus.COMPLETED
        }
        if (existing != null && withContext(Dispatchers.IO) { recordRepository.outputExists(existing) }) {
            _uiState.update {
                it.copy(
                    screen = Screen.DOWNLOADS,
                    message = UiMessage(string(R.string.message_same_saved))
                )
            }
            return
        }
        val duplicate = _uiState.value.records.firstOrNull {
            it.planFingerprint == fingerprint && it.status.isActive
        }
        if (duplicate != null) {
            _uiState.update {
                it.copy(
                    screen = Screen.DOWNLOADS,
                    message = UiMessage(string(R.string.message_already_queued))
                )
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
                record.architectureChoice,
                record.densityChoice,
                record.deliveryProfiles
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
                    totalFiles = latest.uniqueArtifacts.size,
                    checkedAt = latest.checkedAt
                )
            }

            val rate = TransferRate()
            var lastUiUpdate = 0L
            var lastPersist = 0L
            val outcome = coordinator.execute(
                latest,
                onProgress = { bytes, files ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdate >= UI_PROGRESS_INTERVAL_MS || bytes == latest.totalBytes) {
                        val persist = now - lastPersist >= PERSIST_INTERVAL_MS || bytes == latest.totalBytes
                        val speed = rate.sample(bytes, now)
                        updateRecord(record.id, persist) {
                            it.copy(
                                downloadedBytes = bytes,
                                completedFiles = files,
                                bytesPerSecond = speed
                            )
                        }
                        lastUiUpdate = now
                        if (persist) lastPersist = now
                    }
                },
                onVerifying = {
                    updateRecord(record.id) {
                        it.copy(status = TaskStatus.VERIFYING, bytesPerSecond = 0)
                    }
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
                    completedFiles = latest.uniqueArtifacts.size,
                    completedAt = System.currentTimeMillis(),
                    outputUri = exported.uri,
                    outputName = exported.displayName,
                    outputSize = exported.size,
                    verification = outcome.verification.summary(),
                    bytesPerSecond = 0,
                    error = ""
                )
            }
            withContext(NonCancellable + Dispatchers.IO) {
                recordRepository.deleteTaskFiles(record.id)
            }
        } catch (exception: DownloadCoordinator.PauseRequestedException) {
            updateRecord(record.id) {
                it.copy(status = TaskStatus.PAUSED, bytesPerSecond = 0, error = "")
            }
        } catch (exception: DownloadCoordinator.CancelRequestedException) {
            updateRecord(record.id) {
                it.copy(status = TaskStatus.CANCELLED, bytesPerSecond = 0, error = "")
            }
        } catch (exception: CancellationException) {
            if (cancelRequestedTaskId == record.id) {
                withContext(NonCancellable + Dispatchers.IO) {
                    recordRepository.deleteTaskFiles(record.id)
                }
                updateRecord(record.id) {
                    it.copy(status = TaskStatus.CANCELLED, bytesPerSecond = 0, error = "")
                }
            } else {
                updateRecord(record.id) {
                    it.copy(status = TaskStatus.PAUSED, bytesPerSecond = 0, error = "")
                }
            }
            throw exception
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Download task failed (${exception.javaClass.simpleName}): ${safeMessage(exception)}"
            )
            updateRecord(record.id) {
                it.copy(
                    status = TaskStatus.FAILED,
                    bytesPerSecond = 0,
                    error = userMessage(exception)
                )
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

    /** Smoothed so the reported rate reflects the transfer rather than one buffer's timing. */
    private class TransferRate {
        private var lastBytes = 0L
        private var lastAt = 0L
        private var smoothed = 0.0

        fun sample(bytes: Long, nowMillis: Long): Long {
            if (lastAt == 0L) {
                lastBytes = bytes
                lastAt = nowMillis
                return 0
            }
            val elapsed = nowMillis - lastAt
            if (elapsed < MIN_SAMPLE_MS) return smoothed.toLong()
            val instant = (bytes - lastBytes).coerceAtLeast(0) * 1000.0 / elapsed
            lastBytes = bytes
            lastAt = nowMillis
            smoothed = if (smoothed == 0.0) instant else smoothed * (1 - ALPHA) + instant * ALPHA
            return smoothed.toLong()
        }

        private companion object {
            const val MIN_SAMPLE_MS = 400L
            const val ALPHA = 0.35
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
                gateway.resolvePlan(
                    record.packageName,
                    record.architectureChoice,
                    record.densityChoice,
                    record.deliveryProfiles
                ).copy(id = record.id)
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
        publishRecords(currentRecords().filterNot { it.id == record.id })
        viewModelScope.launch(Dispatchers.IO) { recordRepository.deleteTaskFiles(record.id) }
    }

    fun deleteOutput(record: DownloadRecord) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { recordRepository.deleteOutput(record) }
            updateRecord(record.id) {
                it.copy(outputUri = "", outputName = "", outputSize = 0, error = "")
            }
            _uiState.update {
                it.copy(
                    message = UiMessage(
                        string(if (deleted) R.string.message_saved_deleted else R.string.file_missing)
                    )
                )
            }
        }
    }

    /** Confirms the saved file is still there before an intent is built around its URI. */
    suspend fun verifyOutput(record: DownloadRecord): Boolean {
        val exists = withContext(Dispatchers.IO) { recordRepository.outputExists(record) }
        if (!exists && record.outputUri.isNotBlank()) {
            updateRecord(record.id) {
                it.copy(outputUri = "", outputName = "", outputSize = 0)
            }
            _uiState.update { it.copy(message = UiMessage(string(R.string.file_missing))) }
        }
        return exists
    }

    fun onForegroundChanged(isForeground: Boolean) {
        foreground = isForeground
        if (!isForeground) {
            discoveryJob?.cancel()
            if (activeTaskId != null) {
                coordinator.pause()
                activeJob?.cancel()
            }
        }
    }

    fun navigate(screen: Screen) {
        _uiState.update { it.copy(screen = screen) }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value.screen
        if (current == Screen.SEARCH) return false
        if (current == Screen.DETAILS) discoveryJob?.cancel()
        _uiState.update {
            it.copy(screen = if (current == Screen.ABOUT) Screen.SETTINGS else Screen.SEARCH)
        }
        return true
    }

    fun setThemeMode(mode: Int) {
        recordRepository.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        recordRepository.dynamicColor = enabled
        _uiState.update { it.copy(dynamicColor = enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        recordRepository.keepScreenOn = enabled
        _uiState.update { it.copy(keepScreenOn = enabled) }
    }

    fun setCustomFolder(uri: String) {
        recordRepository.customFolderUri = uri
        _uiState.update {
            it.copy(
                customFolderUri = uri,
                message = UiMessage(string(R.string.message_save_location_updated))
            )
        }
    }

    fun useDefaultFolder() = setCustomFolder("")

    fun clearTemporaryFiles() {
        if (activeJob != null) {
            _uiState.update {
                it.copy(message = UiMessage(string(R.string.message_active_download_first)))
            }
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
                    message = UiMessage(
                        string(
                            if (success) {
                                R.string.message_temporary_cleared
                            } else {
                                R.string.message_temporary_partial
                            }
                        )
                    )
                )
            }
        }
    }

    fun clearHistory() {
        val keep = currentRecords().filter { it.status.isActive || it.status == TaskStatus.PAUSED }
        val removed = currentRecords() - keep.toSet()
        publishRecords(keep)
        _uiState.update {
            it.copy(message = UiMessage(string(R.string.message_history_cleared)))
        }
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
                gateway.connectProfiles(
                    _uiState.value.architectureChoice,
                    DensityChoice.CURRENT,
                    force = true
                )
            }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            connection = ConnectionState.CONNECTED,
                            message = UiMessage(string(R.string.message_connection_ready))
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
        _uiState.update { it.copy(message = null) }
    }

    /**
     * Android 13 and newer shows its own copy confirmation. On older releases nothing does, so the
     * app confirms it rather than leaving a long-press with no visible result.
     */
    fun notifyCopied(value: String) {
        _uiState.update {
            it.copy(
                message = UiMessage(
                    getApplication<Application>().getString(R.string.message_copied, value)
                )
            )
        }
    }

    fun runMessageAction(action: MessageAction) {
        consumeMessage()
        when (action) {
            MessageAction.NONE -> Unit
            MessageAction.RETRY_SEARCH -> submitSearch()
            MessageAction.RETRY_DISCOVERY -> discoverVariants()
            MessageAction.OPEN_DOWNLOADS -> navigate(Screen.DOWNLOADS)
        }
    }

    private fun DownloadPlan.toRecord() = DownloadRecord(
        id = id,
        packageName = packageName,
        displayName = displayName,
        versionName = versionName,
        versionCode = versionCode,
        architectureChoice = architectureChoice,
        densityChoice = densityChoice,
        deliveryProfiles = deliveryProfiles,
        planFingerprint = fingerprint(),
        checkedAt = checkedAt,
        createdAt = System.currentTimeMillis(),
        status = TaskStatus.QUEUED,
        totalBytes = totalBytes,
        totalFiles = uniqueArtifacts.size,
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
        if (persist) updatedRecords?.let(persistRequests::tryEmit)
    }

    private fun publishRecords(records: List<DownloadRecord>) {
        val sorted = records.sortedByDescending(DownloadRecord::createdAt)
        _uiState.update { it.copy(records = sorted) }
        persistRequests.tryEmit(sorted)
    }

    private fun currentRecords() = _uiState.value.records
    private fun currentRecord(id: String) = currentRecords().firstOrNull { it.id == id }

    private fun setBusy(value: Boolean) {
        _uiState.update { it.copy(busy = value) }
    }

    private fun string(@StringRes id: Int): String = getApplication<Application>().getString(id)

    private fun showError(throwable: Throwable, action: MessageAction = MessageAction.NONE) {
        _uiState.update { it.copy(message = UiMessage(userMessage(throwable), action)) }
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
            raw == "Google Play returned no APK files for this device" ||
                raw == "Google Play returned no APK files for the selected delivery profiles" ||
                raw == "Google Play returned no APK files for any supported delivery profile" ->
                string(R.string.error_no_apk)
            raw == "Google Play returned no base APK for a package" ->
                string(R.string.error_no_base_apk)
            raw == "Google Play did not return every declared language split" ||
                raw == "Google Play could not resolve declared language splits" ->
                string(R.string.error_language_splits)
            raw == "Google Play returned different versions across selected architectures" ||
                raw == "Google Play returned different versions across selected delivery profiles" ->
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
        private const val UI_PROGRESS_INTERVAL_MS = 200L
        private const val PERSIST_INTERVAL_MS = 1_000L
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
