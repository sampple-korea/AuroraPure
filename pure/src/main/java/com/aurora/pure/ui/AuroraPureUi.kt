/*
 * The Material 3 presentation follows Aurora Store's Compose UI conventions.
 * SPDX-FileCopyrightText: 2025 Aurora OSS
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.pure.BuildConfig
import com.aurora.pure.PureViewModel
import com.aurora.pure.R
import com.aurora.pure.data.ConfirmAction
import com.aurora.pure.data.DownloadConfirmation
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.MessageAction
import com.aurora.pure.data.PureUiState
import com.aurora.pure.data.Screen
import com.aurora.pure.play.DeviceProfile

@Composable
fun AuroraPureApp(
    state: PureUiState,
    viewModel: PureViewModel,
    onChooseFolder: () -> Unit,
    onShare: (DownloadRecord) -> Unit,
    onOpen: (DownloadRecord) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.action_retry)
    val openLabel = stringResource(R.string.downloads)

    // Keyed by the message id rather than its text, so two identical failures in a row still
    // surface the second one instead of being silently swallowed.
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        val actionLabel = when (message.action) {
            MessageAction.NONE -> null
            MessageAction.OPEN_DOWNLOADS -> openLabel
            else -> retryLabel
        }
        val result = snackbar.showSnackbar(
            message = message.text,
            actionLabel = actionLabel,
            duration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.runMessageAction(message.action)
        } else {
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
    state.pendingConfirm?.let { pending ->
        DestructiveConfirmation(
            action = pending.action,
            onConfirm = viewModel::runPendingConfirm,
            onDismiss = viewModel::dismissConfirm
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            PureTopBar(
                screen = state.screen,
                onBack = viewModel::navigateBack
            )
        },
        bottomBar = {
            // The details screen owns the bottom of the window for its download action, so the
            // navigation bar steps aside rather than competing with it.
            AnimatedVisibility(
                visible = state.screen != Screen.DETAILS,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                PureNavigationBar(
                    screen = state.screen,
                    activeDownloads = state.activeDownloadCount,
                    onSelect = viewModel::navigate
                )
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.SEARCH -> SearchScreen(state, viewModel)
                Screen.DETAILS -> DetailsScreen(state, viewModel)
                Screen.DOWNLOADS -> DownloadsScreen(state, viewModel, onShare, onOpen)
                Screen.SETTINGS -> SettingsScreen(state, viewModel, onChooseFolder)
                Screen.ABOUT -> AboutScreen(onOpenUrl)
            }
        }
    }
}

@Composable
private fun PureTopBar(screen: Screen, onBack: () -> Boolean) {
    val title = when (screen) {
        Screen.SEARCH -> stringResource(R.string.app_name)
        Screen.DETAILS -> stringResource(R.string.details)
        Screen.DOWNLOADS -> stringResource(R.string.downloads)
        Screen.SETTINGS -> stringResource(R.string.settings)
        Screen.ABOUT -> stringResource(R.string.about)
    }
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            // Only sub-screens get a back arrow. Search, Downloads, and Settings are peer
            // destinations reached from the navigation bar, so an arrow there would misdescribe
            // where the user is.
            if (screen == Screen.DETAILS || screen == Screen.ABOUT) {
                IconButton(onClick = { onBack() }) {
                    Icon(PureIcons.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

/**
 * Search, Downloads, and Settings are peer destinations, so they belong in a persistent bar rather
 * than behind a text button and a gear in the app bar. The badge keeps running transfers visible
 * from anywhere in the app.
 */
@Composable
private fun PureNavigationBar(
    screen: Screen,
    activeDownloads: Int,
    onSelect: (Screen) -> Unit
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationItem(
                icon = PureIcons.Search,
                label = stringResource(R.string.nav_search),
                selected = screen == Screen.SEARCH,
                onClick = { onSelect(Screen.SEARCH) }
            )
            NavigationItem(
                icon = PureIcons.Download,
                label = stringResource(R.string.downloads),
                selected = screen == Screen.DOWNLOADS,
                badgeCount = activeDownloads,
                onClick = { onSelect(Screen.DOWNLOADS) }
            )
            NavigationItem(
                icon = PureIcons.Tune,
                label = stringResource(R.string.settings),
                selected = screen == Screen.SETTINGS || screen == Screen.ABOUT,
                onClick = { onSelect(Screen.SETTINGS) }
            )
        }
    }
}

@Composable
private fun RowScope.NavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            BadgedBox(
                badge = {
                    if (badgeCount > 0) {
                        Badge { Text(badgeCount.coerceAtMost(99).toString()) }
                    }
                }
            ) {
                Icon(icon, contentDescription = null)
            }
        },
        label = { Text(label) }
    )
}

@Composable
private fun DestructiveConfirmation(
    action: ConfirmAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when (action) {
        ConfirmAction.CLEAR_HISTORY -> R.string.confirm_clear_history_title
        ConfirmAction.CLEAR_TEMPORARY -> R.string.confirm_clear_temporary_title
        ConfirmAction.DELETE_OUTPUT -> R.string.confirm_delete_output_title
        ConfirmAction.REMOVE_RECORD -> R.string.confirm_remove_record_title
        ConfirmAction.CANCEL_DOWNLOAD -> R.string.confirm_cancel_download_title
    }
    val body = when (action) {
        ConfirmAction.CLEAR_HISTORY -> R.string.confirm_clear_history_body
        ConfirmAction.CLEAR_TEMPORARY -> R.string.confirm_clear_temporary_body
        ConfirmAction.DELETE_OUTPUT -> R.string.confirm_delete_output_body
        ConfirmAction.REMOVE_RECORD -> R.string.confirm_remove_record_body
        ConfirmAction.CANCEL_DOWNLOAD -> R.string.confirm_cancel_download_body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(PureIcons.Warning, contentDescription = null) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_keep)) }
        }
    )
}

@Composable
private fun VersionConfirmation(
    confirmation: DownloadConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (confirmation.changed) PureIcons.Warning else PureIcons.Shield,
                contentDescription = null
            )
        },
        title = {
            Text(
                stringResource(
                    if (confirmation.changed) R.string.version_changed_title else R.string.plan_review_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PureSpacing.s)
            ) {
                if (confirmation.changed) {
                    Text(
                        stringResource(R.string.version_changed_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (confirmation.reason.isNotBlank()) {
                    Text(
                        confirmation.reason,
                        style = technicalTextStyle(MaterialTheme.typography.bodyMedium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                PureDetailRow(
                    stringResource(R.string.version),
                    "${confirmation.plan.versionName} (${confirmation.plan.versionCode})",
                    monospace = true
                )
                PureDetailRow(
                    stringResource(R.string.architecture),
                    architectureText(confirmation.plan.architectureChoice)
                )
                PureDetailRow(
                    stringResource(R.string.screen_density),
                    densityText(confirmation.plan.densityChoice)
                )
                PureDetailRow(
                    stringResource(R.string.minimum_android),
                    stringResource(
                        R.string.android_api_value,
                        DeviceProfile.androidRelease(confirmation.plan.minSdk),
                        confirmation.plan.minSdk
                    )
                )
                PureDetailRow(
                    stringResource(R.string.languages),
                    stringResource(R.string.all_languages)
                )
                PureDetailRow(
                    stringResource(R.string.language_files),
                    stringResource(
                        R.string.language_split_count,
                        confirmation.plan.requestedLocales.size,
                        confirmation.plan.uniqueArtifacts.count { it.localeKeys.isNotEmpty() }
                    )
                )
                PureDetailRow(
                    stringResource(R.string.delivery_profiles),
                    confirmation.plan.deviceDescription,
                    monospace = true
                )
                PureDetailRow(
                    stringResource(R.string.checked_at),
                    formatDate(confirmation.plan.checkedAt)
                )
                PureDetailRow(
                    stringResource(R.string.file_layout),
                    stringResource(
                        R.string.apk_count_size,
                        confirmation.plan.uniqueArtifacts.size,
                        formatBytes(confirmation.plan.totalBytes)
                    )
                )
                PureDetailRow(
                    stringResource(R.string.save_format),
                    stringResource(
                        if (confirmation.plan.isSingleApk) R.string.single_apk else R.string.split_zip
                    )
                )
                HorizontalDivider(Modifier.padding(vertical = PureSpacing.xs))
                PureSectionTitle(stringResource(R.string.file_list))
                confirmation.plan.uniqueArtifacts.forEach { artifact ->
                    Column {
                        Text(
                            artifact.relativePath,
                            style = technicalTextStyle(MaterialTheme.typography.bodySmall)
                        )
                        Text(
                            if (artifact.isDependency) {
                                "${formatBytes(artifact.size)} · " +
                                    stringResource(R.string.dependency_artifact, artifact.ownerPackage)
                            } else {
                                formatBytes(artifact.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (artifact.isDependency) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                if (confirmation.plan.hasAdditionalData) {
                    Text(
                        stringResource(R.string.additional_data_warning),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    stringResource(
                        R.string.download_with_size,
                        formatBytes(confirmation.plan.totalBytes)
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun AboutScreen(onOpenUrl: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PureSpacing.gutter, vertical = PureSpacing.l),
        verticalArrangement = Arrangement.spacedBy(PureSpacing.l)
    ) {
        PurePanel {
            PureSectionTitle(stringResource(R.string.about_privacy_title))
            Text(
                stringResource(R.string.privacy_text),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        PurePanel {
            PureSectionTitle(stringResource(R.string.about_license_title))
            Text(
                stringResource(R.string.license_text),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        PurePanel {
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(Modifier.padding(vertical = PureSpacing.xs))
            PureDetailRow(
                stringResource(R.string.about_app_version),
                BuildConfig.VERSION_NAME,
                monospace = true
            )
            PureDetailRow(
                stringResource(R.string.about_upstream_version),
                BuildConfig.UPSTREAM_VERSION,
                monospace = true
            )
            PureDetailRow(
                stringResource(R.string.about_upstream_commit),
                BuildConfig.UPSTREAM_COMMIT.take(12),
                monospace = true
            )
        }
        LinkRow(
            label = stringResource(R.string.corresponding_source),
            url = "https://github.com/sampple-korea/AuroraPure",
            onOpenUrl = onOpenUrl
        )
        LinkRow(
            label = stringResource(R.string.aurora_upstream),
            url = "https://gitlab.com/AuroraOSS/AuroraStore",
            onOpenUrl = onOpenUrl
        )
        LinkRow(
            label = stringResource(R.string.gpl_license),
            url = "https://www.gnu.org/licenses/gpl-3.0.html",
            onOpenUrl = onOpenUrl
        )
        Box(Modifier.fillMaxWidth().padding(bottom = PureSpacing.xl))
    }
}

@Composable
private fun LinkRow(label: String, url: String, onOpenUrl: (String) -> Unit) {
    OutlinedButton(
        onClick = { onOpenUrl(url) },
        modifier = Modifier.fillMaxWidth().heightIn(min = PureSpacing.touchTarget)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Icon(
            PureIcons.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
    }
}
