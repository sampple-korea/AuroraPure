/*
 * The Material 3 presentation follows Aurora Store's Compose UI conventions.
 * SPDX-FileCopyrightText: 2025 Aurora OSS
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aurora.pure.BuildConfig
import com.aurora.pure.PureViewModel
import com.aurora.pure.R
import com.aurora.pure.data.AppSummary
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ConnectionState
import com.aurora.pure.data.DownloadConfirmation
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.DeliveryVariant
import com.aurora.pure.data.DensityChoice
import com.aurora.pure.data.PureUiState
import com.aurora.pure.data.Screen
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.play.DeviceProfile
import java.util.Locale

@Composable
fun AuroraPureApp(
    state: PureUiState,
    viewModel: PureViewModel,
    onChooseFolder: () -> Unit,
    onShare: (DownloadRecord) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            snackbar.showSnackbar(state.message)
            viewModel.consumeMessage()
        }
    }
    BackHandler(enabled = state.screen != Screen.SEARCH) { viewModel.navigateBack() }

    state.confirmation?.let { confirmation ->
        VersionConfirmation(
            confirmation = confirmation,
            onConfirm = viewModel::confirmDownload,
            onDismiss = viewModel::dismissConfirmation
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            PureTopBar(
                screen = state.screen,
                onBack = viewModel::navigateBack,
                onDownloads = { viewModel.navigate(Screen.DOWNLOADS) },
                onSettings = { viewModel.navigate(Screen.SETTINGS) }
            )
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.SEARCH -> SearchScreen(state, viewModel)
                Screen.DETAILS -> DetailsScreen(state, viewModel)
                Screen.DOWNLOADS -> DownloadsScreen(state, viewModel, onShare)
                Screen.SETTINGS -> SettingsScreen(state, viewModel, onChooseFolder)
                Screen.ABOUT -> AboutScreen()
            }
        }
    }
}

@Composable
private fun PureTopBar(
    screen: Screen,
    onBack: () -> Boolean,
    onDownloads: () -> Unit,
    onSettings: () -> Unit
) {
    val title = when (screen) {
        Screen.SEARCH -> stringResource(R.string.app_name)
        Screen.DETAILS -> stringResource(R.string.details)
        Screen.DOWNLOADS -> stringResource(R.string.downloads)
        Screen.SETTINGS -> stringResource(R.string.settings)
        Screen.ABOUT -> stringResource(R.string.about)
    }
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (screen != Screen.SEARCH) {
                IconButton(onClick = { onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        actions = {
            if (screen == Screen.SEARCH) {
                TextButton(onClick = onDownloads) { Text(stringResource(R.string.downloads)) }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings))
                }
            }
        }
    )
}

@Composable
private fun SearchScreen(state: PureUiState, viewModel: PureViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) }
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    text?.let(viewModel::setQuery)
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.paste)) }
            Button(
                onClick = viewModel::submitSearch,
                enabled = state.query.isNotBlank() && !state.busy,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.search)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.source_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (state.busy) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.results, key = { it.packageName }) { app ->
                    AppResultCard(app) { viewModel.openDetails(app.packageName) }
                }
            }
        }
    }
}

@Composable
private fun AppResultCard(app: AppSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = app.iconUrl,
                contentDescription = null,
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.developerName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!app.isFree) Text(stringResource(R.string.paid_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DetailsScreen(state: PureUiState, viewModel: PureViewModel) {
    val app = state.selected ?: return
    val variantsByVersion = state.variants
        .groupBy(DeliveryVariant::versionCode)
        .toSortedMap(reverseOrder())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = app.iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(78.dp).clip(RoundedCornerShape(18.dp))
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(app.developerName, style = MaterialTheme.typography.bodyMedium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.google_play_delivery), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    DetailRow(stringResource(R.string.version), app.versionName.ifBlank { "—" })
                    DetailRow(stringResource(R.string.version_code), app.versionCode.takeIf { it > 0 }?.toString() ?: "—")
                    DetailRow(stringResource(R.string.file_layout), stringResource(R.string.checked_before_download))
                    DetailRow(stringResource(R.string.save_format), stringResource(R.string.single_apk) + " / " + stringResource(R.string.split_zip))
                    DetailRow(stringResource(R.string.languages), stringResource(R.string.all_languages))
                    DetailRow(stringResource(R.string.current_device), android.os.Build.MODEL)
                    DetailRow(stringResource(R.string.checked_at), formatDate(app.checkedAt))
                    if (app.size > 0) DetailRow(stringResource(R.string.size), formatBytes(app.size))
                }
            }
        }
        if (app.shortDescription.isNotBlank()) item {
            Text(app.shortDescription, style = MaterialTheme.typography.bodyLarge)
        }
        if (!app.isFree) item {
            Text(stringResource(R.string.paid_unavailable), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        if (state.discoveringVariants) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.discovering_variants),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        state.discoveryProbeDescription.ifBlank {
                            stringResource(R.string.connecting_google_play)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(
                            R.string.discovery_probe_count,
                            state.discoveryProbeCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else item {
            OutlinedButton(
                onClick = viewModel::discoverVariants,
                enabled = app.isFree && !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    stringResource(
                        if (state.variants.isEmpty()) {
                            R.string.discover_variants
                        } else {
                            R.string.refresh_variants
                        }
                    )
                )
            }
        }
        if (state.variants.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.available_variants),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            variantsByVersion.forEach { (versionCode, variants) ->
                item(key = "version-$versionCode") {
                    Text(
                        stringResource(
                            R.string.version_group_header,
                            variants.first().versionName,
                            versionCode.toString()
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(variants, key = DeliveryVariant::id) { variant ->
                    VariantCard(
                        variant = variant,
                        selected = variant.id == state.selectedVariantId,
                        onClick = { viewModel.selectVariant(variant.id) }
                    )
                }
            }
            item {
                Button(
                    onClick = viewModel::prepareDownload,
                    enabled = app.isFree && !state.busy && state.selectedVariantId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (state.busy && !state.discoveringVariants) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.latest_files))
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantCard(
    variant: DeliveryVariant,
    selected: Boolean,
    onClick: () -> Unit
) {
    val resources = LocalResources.current
    val observedTargets = variant.profiles
        .groupBy { it.variant to it.densityDpi }
        .toSortedMap(compareBy<Pair<com.aurora.pure.data.ArchitectureVariant, Int>>(
            { it.first.ordinal },
            { it.second }
        ))
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (variant.aggregate) {
                        stringResource(
                            if (variant.universal) {
                                R.string.universal_variant
                            } else {
                                R.string.combined_variant
                            }
                        )
                    } else {
                        stringResource(
                            R.string.variant_version,
                            variant.versionName,
                            variant.versionCode.toString()
                        )
                    },
                    fontWeight = FontWeight.Bold
                )
                if (variant.aggregate) {
                    Text(
                        stringResource(
                            R.string.variant_version,
                            variant.versionName,
                            variant.versionCode.toString()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                DetailRow(
                    stringResource(R.string.architecture),
                    variant.architectures.joinToString(" + ") { it.archiveDirectory }
                )
                DetailRow(
                    stringResource(R.string.minimum_android),
                    stringResource(
                        R.string.android_api_value,
                        DeviceProfile.androidRelease(variant.minSdk),
                        variant.minSdk
                    )
                )
                DetailRow(
                    stringResource(R.string.screen_density),
                    variant.densityDpis.joinToString(", ", postfix = " dpi")
                )
                DetailRow(
                    stringResource(R.string.probed_android),
                    variant.testedSdkVersions.joinToString(", ") { "API $it" }
                )
                DetailRow(
                    stringResource(R.string.file_layout),
                    stringResource(
                        R.string.apk_count_size,
                        variant.artifactCount,
                        formatBytes(variant.totalBytes)
                    )
                )
                Text(
                    stringResource(R.string.actual_combinations),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                for ((target, profiles) in observedTargets) {
                    val apiText = profiles
                        .map { it.sdkVersion }
                        .distinct()
                        .sortedDescending()
                        .joinToString(", ") { sdk ->
                            resources.getString(
                                R.string.actual_combination_api,
                                DeviceProfile.androidRelease(sdk),
                                sdk
                            )
                        }
                    Text(
                        stringResource(
                            R.string.actual_combination_value,
                            target.first.archiveDirectory,
                            target.second,
                            apiText
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    stringResource(R.string.equivalent_combinations_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DownloadsScreen(
    state: PureUiState,
    viewModel: PureViewModel,
    onShare: (DownloadRecord) -> Unit
) {
    if (state.records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.empty_downloads), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(state.records, key = { it.id }) { record ->
            DownloadCard(record, viewModel, onShare)
        }
    }
}

@Composable
private fun DownloadCard(
    record: DownloadRecord,
    viewModel: PureViewModel,
    onShare: (DownloadRecord) -> Unit
) {
    val fraction = if (record.totalBytes > 0) {
        (record.downloadedBytes.toFloat() / record.totalBytes).coerceIn(0f, 1f)
    } else 0f
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(record.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(
                    R.string.record_summary,
                    record.versionName,
                    record.versionCode.toString(),
                    statusText(record.status)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(
                    R.string.verification_value,
                    stringResource(R.string.architecture),
                    architectureText(record.architectureChoice)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(
                    R.string.verification_value,
                    stringResource(R.string.screen_density),
                    densityText(record.densityChoice)
                ),
                style = MaterialTheme.typography.bodySmall
            )
            if (record.status in setOf(TaskStatus.DOWNLOADING, TaskStatus.PAUSED, TaskStatus.VERIFYING, TaskStatus.EXPORTING)) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        R.string.progress_summary,
                        formatBytes(record.downloadedBytes),
                        formatBytes(record.totalBytes),
                        record.completedFiles,
                        record.totalFiles
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (record.status == TaskStatus.VERIFYING) Text(stringResource(R.string.verifying_files))
            if (record.status == TaskStatus.EXPORTING) Text(stringResource(R.string.exporting_file))
            if (record.verification.isNotBlank()) {
                Text(
                    stringResource(
                        R.string.verification_value,
                        stringResource(R.string.verification),
                        verificationText(record.verification)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (record.checkedAt > 0) {
                Text(
                    stringResource(
                        R.string.verification_value,
                        stringResource(R.string.checked_at),
                        formatDate(record.checkedAt)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (record.completedAt > 0) {
                Text(
                    stringResource(
                        R.string.verification_value,
                        stringResource(R.string.completed_at),
                        formatDate(record.completedAt)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (record.outputName.isNotBlank()) {
                Text(record.outputName, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (record.outputSize > 0) {
                Text(
                    stringResource(
                        R.string.verification_value,
                        stringResource(R.string.saved_size),
                        formatBytes(record.outputSize)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (record.hasAdditionalData) {
                Text(stringResource(R.string.additional_data_warning), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            if (record.outputName.endsWith(".apks", true)) {
                Text(stringResource(R.string.split_notice), style = MaterialTheme.typography.bodySmall)
            }
            if (record.error.isNotBlank()) {
                Text(record.error, color = if (record.status == TaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            when (record.status) {
                TaskStatus.CHECKING, TaskStatus.DOWNLOADING, TaskStatus.VERIFYING, TaskStatus.EXPORTING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.pause(record) }, enabled = record.status == TaskStatus.DOWNLOADING) { Text(stringResource(R.string.pause)) }
                        TextButton(onClick = { viewModel.cancel(record) }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.VERSION_CHANGED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.resume(record) }) { Text(stringResource(R.string.resume)) }
                        TextButton(onClick = { viewModel.cancel(record) }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                TaskStatus.COMPLETED -> {
                    if (record.outputUri.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onShare(record) }) { Text(stringResource(R.string.share)) }
                            OutlinedButton(onClick = { viewModel.deleteOutput(record) }) {
                                Text(stringResource(R.string.delete_file))
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.removeRecord(record) }) { Text(stringResource(R.string.remove_record)) }
                }
                TaskStatus.CANCELLED -> TextButton(onClick = { viewModel.removeRecord(record) }) { Text(stringResource(R.string.remove_record)) }
                TaskStatus.QUEUED -> TextButton(onClick = { viewModel.cancel(record) }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: PureUiState,
    viewModel: PureViewModel,
    onChooseFolder: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SettingsSection(stringResource(R.string.connection)) {
            Text(connectionText(state.connection), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = viewModel::reconnect, enabled = !state.busy) { Text(stringResource(R.string.reconnect)) }
        }
        HorizontalDivider()
        SettingsSection(stringResource(R.string.storage)) {
            Text(if (state.customFolderUri.isBlank()) stringResource(R.string.default_folder) else state.customFolderUri, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseFolder) { Text(stringResource(R.string.choose_folder)) }
                if (state.customFolderUri.isNotBlank()) TextButton(onClick = viewModel::useDefaultFolder) { Text(stringResource(R.string.use_default_folder)) }
            }
        }
        HorizontalDivider()
        SettingsSection(stringResource(R.string.theme)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    0 to stringResource(R.string.theme_system),
                    1 to stringResource(R.string.theme_light),
                    2 to stringResource(R.string.theme_dark)
                ).forEach { (mode, label) ->
                    FilterChip(selected = state.themeMode == mode, onClick = { viewModel.setThemeMode(mode) }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { viewModel.setKeepScreenOn(!state.keepScreenOn) }) {
                Checkbox(checked = state.keepScreenOn, onCheckedChange = viewModel::setKeepScreenOn)
                Text(stringResource(R.string.keep_screen_on))
            }
        }
        HorizontalDivider()
        SettingsSection(stringResource(R.string.local_data)) {
            OutlinedButton(onClick = viewModel::clearTemporaryFiles) { Text(stringResource(R.string.clear_temporary)) }
            OutlinedButton(onClick = viewModel::clearHistory) { Text(stringResource(R.string.clear_history)) }
        }
        HorizontalDivider()
        TextButton(onClick = { viewModel.navigate(Screen.ABOUT) }) { Text(stringResource(R.string.about)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun AboutScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.license_text), style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider()
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), fontWeight = FontWeight.Bold)
        Text(
            stringResource(
                R.string.based_on_upstream,
                BuildConfig.UPSTREAM_VERSION,
                BuildConfig.UPSTREAM_COMMIT
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(onClick = { context.openUrl("https://github.com/sampple-korea/AuroraPure") }) {
            Text(stringResource(R.string.corresponding_source))
        }
        TextButton(onClick = { context.openUrl("https://gitlab.com/AuroraOSS/AuroraStore") }) {
            Text(stringResource(R.string.aurora_upstream))
        }
        TextButton(onClick = { context.openUrl("https://www.gnu.org/licenses/gpl-3.0.html") }) {
            Text(stringResource(R.string.gpl_license))
        }
    }
}

@Composable
private fun VersionConfirmation(
    confirmation: DownloadConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (confirmation.changed) R.string.version_changed_title else R.string.plan_review_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(
                        if (confirmation.changed) {
                            R.string.version_changed_body
                        } else {
                            R.string.plan_review_body
                        }
                    )
                )
                if (confirmation.reason.isNotBlank()) {
                    Text(confirmation.reason, fontWeight = FontWeight.Bold)
                }
                DetailRow(
                    stringResource(R.string.version),
                    "${confirmation.plan.versionName} (${confirmation.plan.versionCode})"
                )
                DetailRow(stringResource(R.string.source), stringResource(R.string.google_play_delivery))
                DetailRow(
                    stringResource(R.string.delivery_condition),
                    stringResource(R.string.current_condition_latest)
                )
                DetailRow(
                    stringResource(R.string.distribution_track),
                    stringResource(R.string.track_unknown)
                )
                DetailRow(
                    stringResource(R.string.architecture),
                    architectureText(confirmation.plan.architectureChoice)
                )
                DetailRow(
                    stringResource(R.string.screen_density),
                    densityText(confirmation.plan.densityChoice)
                )
                DetailRow(
                    stringResource(R.string.minimum_android),
                    stringResource(
                        R.string.android_api_value,
                        DeviceProfile.androidRelease(confirmation.plan.minSdk),
                        confirmation.plan.minSdk
                    )
                )
                DetailRow(stringResource(R.string.languages), stringResource(R.string.all_languages))
                DetailRow(
                    stringResource(R.string.language_files),
                    stringResource(
                        R.string.language_split_count,
                        confirmation.plan.requestedLocales.size,
                        confirmation.plan.uniqueArtifacts.count { it.localeKeys.isNotEmpty() }
                    )
                )
                DetailRow(stringResource(R.string.delivery_profiles), confirmation.plan.deviceDescription)
                DetailRow(stringResource(R.string.checked_at), formatDate(confirmation.plan.checkedAt))
                DetailRow(
                    stringResource(R.string.file_layout),
                    stringResource(
                        R.string.apk_count_size,
                        confirmation.plan.uniqueArtifacts.size,
                        formatBytes(confirmation.plan.totalBytes)
                    )
                )
                DetailRow(
                    stringResource(R.string.save_format),
                    stringResource(
                        if (confirmation.plan.isSingleApk) R.string.single_apk else R.string.split_zip
                    )
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.file_list), fontWeight = FontWeight.Bold)
                confirmation.plan.uniqueArtifacts.forEach { artifact ->
                    Column {
                        Text(artifact.relativePath, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(formatBytes(artifact.size), style = MaterialTheme.typography.bodySmall)
                            if (artifact.isDependency) {
                                Text(
                                    stringResource(R.string.dependency_artifact, artifact.ownerPackage),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
                if (confirmation.plan.hasAdditionalData) {
                    Text(
                        stringResource(R.string.additional_data_warning),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (confirmation.plan.deliveryProfiles.size > 1) {
                    Text(
                        stringResource(R.string.combined_variants_notice),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.continue_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun architectureText(choice: ArchitectureChoice): String = stringResource(
    when (choice) {
        ArchitectureChoice.UNIVERSAL -> R.string.architecture_universal
        ArchitectureChoice.BOTH -> R.string.architecture_both
        ArchitectureChoice.BIT_64 -> R.string.architecture_64
        ArchitectureChoice.BIT_32 -> R.string.architecture_32
    }
)

@Composable
private fun densityText(choice: DensityChoice): String = when (choice) {
    DensityChoice.CURRENT -> stringResource(R.string.density_current)
    DensityChoice.ALL -> stringResource(R.string.density_all)
    DensityChoice.LDPI -> "ldpi · 120"
    DensityChoice.MDPI -> "mdpi · 160"
    DensityChoice.TVDPI -> "tvdpi · 213"
    DensityChoice.HDPI -> "hdpi · 240"
    DensityChoice.XHDPI -> "xhdpi · 320"
    DensityChoice.XXHDPI -> "xxhdpi · 480"
    DensityChoice.XXXHDPI -> "xxxhdpi · 640"
}

@Composable
private fun statusText(status: TaskStatus): String {
    val resource = when (status) {
        TaskStatus.QUEUED -> R.string.status_queued
        TaskStatus.CHECKING -> R.string.status_checking
        TaskStatus.DOWNLOADING -> R.string.status_downloading
        TaskStatus.PAUSED -> R.string.status_paused
        TaskStatus.VERIFYING -> R.string.status_verifying
        TaskStatus.EXPORTING -> R.string.status_exporting
        TaskStatus.COMPLETED -> R.string.status_completed
        TaskStatus.FAILED -> R.string.status_failed
        TaskStatus.CANCELLED -> R.string.status_cancelled
        TaskStatus.VERSION_CHANGED -> R.string.status_version_changed
    }
    return stringResource(resource)
}

@Composable
private fun connectionText(state: ConnectionState): String {
    val resource = when (state) {
        ConnectionState.IDLE -> R.string.connection_idle
        ConnectionState.CONNECTING -> R.string.connection_connecting
        ConnectionState.CONNECTED -> R.string.connection_connected
        ConnectionState.FAILED -> R.string.connection_failed
    }
    return stringResource(resource)
}

@Composable
private fun verificationText(summary: String): String {
    val states = mapOf(
        "verified" to stringResource(R.string.verification_verified),
        "failed" to stringResource(R.string.verification_failed),
        "unavailable" to stringResource(R.string.verification_unavailable)
    )
    val labels = mapOf(
        "integrity" to stringResource(R.string.verification_integrity),
        "signatures" to stringResource(R.string.verification_signatures),
        "package" to stringResource(R.string.verification_package)
    )
    val limitations = mapOf(
        "Google Play supplied no reference hash for one or more APKs" to
            stringResource(R.string.limitation_no_reference_hash),
        "APK signature verification was unavailable" to
            stringResource(R.string.limitation_signature_unavailable),
        "The delivery references non-APK data" to
            stringResource(R.string.limitation_non_apk_data)
    )
    val limitationsLabel = stringResource(R.string.verification_limitations)
    return summary.split(", ").joinToString(" · ") { component ->
        val key = component.substringBefore('=')
        val value = component.substringAfter('=', "")
        if (key == "limitations") {
            val translated = value.split("; ").joinToString("; ") { limitations[it] ?: it }
            "$limitationsLabel: $translated"
        } else {
            "${labels[key] ?: key}: ${states[value] ?: value}"
        }
    }
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    val context = java.text.SimpleDateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT
    )
    return context.format(java.util.Date(epochMillis))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
