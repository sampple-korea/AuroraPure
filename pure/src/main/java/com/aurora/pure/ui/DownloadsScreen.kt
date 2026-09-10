/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.pure.PureViewModel
import com.aurora.pure.R
import com.aurora.pure.data.ConfirmAction
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.PureUiState
import com.aurora.pure.data.Screen
import com.aurora.pure.data.TaskStatus

@Composable
internal fun DownloadsScreen(
    state: PureUiState,
    viewModel: PureViewModel,
    onShare: (DownloadRecord) -> Unit,
    onOpen: (DownloadRecord) -> Unit
) {
    when {
        state.restoringRecords -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.records.isEmpty() -> PureEmptyState(
            icon = PureIcons.Download,
            title = stringResource(R.string.empty_downloads_title),
            description = stringResource(R.string.empty_downloads_body),
            actionLabel = stringResource(R.string.nav_search),
            onAction = { viewModel.navigate(Screen.SEARCH) },
            modifier = Modifier.fillMaxSize()
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = PureSpacing.l,
                vertical = PureSpacing.m
            ),
            verticalArrangement = Arrangement.spacedBy(PureSpacing.m)
        ) {
            items(state.records, key = DownloadRecord::id) { record ->
                DownloadCard(
                    record = record,
                    onPause = { viewModel.pause(record) },
                    onResume = { viewModel.resume(record) },
                    onCancel = { viewModel.requestConfirm(ConfirmAction.CANCEL_DOWNLOAD, record) },
                    onRemove = { viewModel.requestConfirm(ConfirmAction.REMOVE_RECORD, record) },
                    onDeleteFile = { viewModel.requestConfirm(ConfirmAction.DELETE_OUTPUT, record) },
                    onShare = { onShare(record) },
                    onOpen = { onOpen(record) }
                )
            }
        }
    }
}

/**
 * The card used to stack ten equally-weighted grey lines, so the state and the controls were as
 * quiet as the profile metadata. Progress and actions now lead, and everything a user only checks
 * occasionally sits behind one expander.
 */
@Composable
private fun DownloadCard(
    record: DownloadRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val transferring = record.status in TRANSFER_STATES
    val (badgeContainer, badgeContent) = statusBadgeColors(record.status)

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            Modifier.padding(PureSpacing.l),
            verticalArrangement = Arrangement.spacedBy(PureSpacing.m)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PureSpacing.xxs)
                ) {
                    Text(
                        record.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${record.versionName} · ${record.versionCode}",
                        style = technicalTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(PureSpacing.s))
                PureStatusBadge(
                    label = statusText(record.status),
                    container = badgeContainer,
                    content = badgeContent,
                    icon = when (record.status) {
                        TaskStatus.COMPLETED -> PureIcons.Check
                        TaskStatus.FAILED -> PureIcons.Warning
                        TaskStatus.PAUSED -> PureIcons.Pause
                        else -> null
                    }
                )
            }

            if (transferring) TransferProgress(record)

            if (record.error.isNotBlank()) {
                Text(
                    record.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.status == TaskStatus.COMPLETED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            if (record.status == TaskStatus.COMPLETED && record.outputName.isNotBlank()) {
                SavedFileSummary(record)
            }

            RecordActions(
                record = record,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRemove = onRemove,
                onDeleteFile = onDeleteFile,
                onShare = onShare,
                onOpen = onOpen
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable { expanded = !expanded }
                    .padding(vertical = PureSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        if (expanded) R.string.collapse_details else R.string.expand_details
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    PureIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)
                )
            }

            if (expanded) RecordDetails(record)
        }
    }
}

@Composable
private fun TransferProgress(record: DownloadRecord) {
    val fraction by animateFloatAsState(record.fraction, label = "download")
    Column(verticalArrangement = Arrangement.spacedBy(PureSpacing.s)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.progress_summary,
                    formatBytes(record.downloadedBytes),
                    formatBytes(record.totalBytes),
                    record.completedFiles,
                    record.totalFiles
                ),
                style = technicalTextStyle(MaterialTheme.typography.labelMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // Bytes alone say nothing about whether a download is worth waiting for.
            val remaining = record.secondsRemaining
            if (record.status == TaskStatus.DOWNLOADING && record.bytesPerSecond > 0) {
                Text(
                    if (remaining != null) {
                        "${formatSpeed(record.bytesPerSecond)} · ${formatDuration(remaining)}"
                    } else {
                        formatSpeed(record.bytesPerSecond)
                    },
                    style = technicalTextStyle(MaterialTheme.typography.labelMedium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedFileSummary(record: DownloadRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(PureSpacing.xs)) {
        Text(
            record.outputName,
            style = technicalTextStyle(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (record.verification.isNotBlank()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(PureSpacing.xs)) {
                verificationParts(record.verification).forEach { part ->
                    val (container, content) = verificationBadgeColors(part.state)
                    PureStatusBadge(
                        label = "${part.label} ${verificationStateText(part.state)}",
                        container = container,
                        content = content
                    )
                }
            }
        }
        if (record.outputName.endsWith(".apks", ignoreCase = true)) {
            Text(
                stringResource(R.string.split_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (record.hasAdditionalData) {
            Text(
                stringResource(R.string.additional_data_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordActions(
    record: DownloadRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PureSpacing.s),
        verticalArrangement = Arrangement.spacedBy(PureSpacing.xs)
    ) {
        when (record.status) {
            TaskStatus.CHECKING, TaskStatus.DOWNLOADING, TaskStatus.VERIFYING, TaskStatus.EXPORTING -> {
                OutlinedButton(
                    onClick = onPause,
                    enabled = record.status == TaskStatus.DOWNLOADING
                ) {
                    Icon(PureIcons.Pause, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(PureSpacing.xs))
                    Text(stringResource(R.string.pause))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }

            TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.VERSION_CHANGED -> {
                Button(onClick = onResume) {
                    Icon(PureIcons.Play, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(PureSpacing.xs))
                    Text(stringResource(R.string.resume))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }

            TaskStatus.COMPLETED -> {
                if (record.outputUri.isNotBlank()) {
                    // Opening the saved file is the natural next step; sharing used to be the
                    // only thing offered.
                    Button(onClick = onOpen) {
                        Icon(PureIcons.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(PureSpacing.xs))
                        Text(stringResource(R.string.action_open))
                    }
                    OutlinedButton(onClick = onShare) {
                        Icon(PureIcons.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(PureSpacing.xs))
                        Text(stringResource(R.string.share))
                    }
                    TextButton(onClick = onDeleteFile) {
                        Text(stringResource(R.string.delete_file))
                    }
                } else {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.remove_record))
                    }
                }
            }

            TaskStatus.CANCELLED -> TextButton(onClick = onRemove) {
                Text(stringResource(R.string.remove_record))
            }

            TaskStatus.QUEUED -> TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun RecordDetails(record: DownloadRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(PureSpacing.s)) {
        HorizontalDivider()
        PureDetailRow(
            stringResource(R.string.package_name),
            record.packageName,
            monospace = true
        )
        PureDetailRow(
            stringResource(R.string.architecture),
            architectureText(record.architectureChoice)
        )
        PureDetailRow(
            stringResource(R.string.screen_density),
            densityText(record.densityChoice)
        )
        if (record.checkedAt > 0) {
            PureDetailRow(stringResource(R.string.checked_at), formatDate(record.checkedAt))
        }
        if (record.completedAt > 0) {
            PureDetailRow(stringResource(R.string.completed_at), formatDate(record.completedAt))
        }
        if (record.outputSize > 0) {
            PureDetailRow(stringResource(R.string.saved_size), formatBytes(record.outputSize))
        }
        val limitations = verificationLimitations(record.verification)
        if (limitations.isNotEmpty()) {
            Text(
                stringResource(R.string.verification_limitations),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            limitations.forEach { limitation ->
                Text(
                    "· $limitation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val TRANSFER_STATES = setOf(
    TaskStatus.DOWNLOADING,
    TaskStatus.PAUSED,
    TaskStatus.VERIFYING,
    TaskStatus.EXPORTING
)
