/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.pure.PureViewModel
import com.aurora.pure.R
import com.aurora.pure.data.ConfirmAction
import com.aurora.pure.data.ConnectionState
import com.aurora.pure.data.PureUiState
import com.aurora.pure.data.Screen

@Composable
internal fun SettingsScreen(
    state: PureUiState,
    viewModel: PureViewModel,
    onChooseFolder: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PureSpacing.gutter, vertical = PureSpacing.m),
        verticalArrangement = Arrangement.spacedBy(PureSpacing.m)
    ) {
        PurePanel(spacing = PureSpacing.m) {
            SettingsHeader(
                icon = PureIcons.Shield,
                title = stringResource(R.string.connection)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PureStatusBadge(
                    label = connectionText(state.connection),
                    container = when (state.connection) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                        ConnectionState.FAILED -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    content = when (state.connection) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                        ConnectionState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::reconnect, enabled = !state.busy) {
                    Text(stringResource(R.string.reconnect))
                }
            }
            Text(
                stringResource(R.string.connection_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PurePanel(spacing = PureSpacing.m) {
            SettingsHeader(
                icon = PureIcons.Folder,
                title = stringResource(R.string.storage)
            )
            Text(
                state.customFolderUri.ifBlank { stringResource(R.string.default_folder) },
                style = technicalTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PureSpacing.s)) {
                OutlinedButton(onClick = onChooseFolder) {
                    Text(stringResource(R.string.choose_folder))
                }
                if (state.customFolderUri.isNotBlank()) {
                    TextButton(onClick = viewModel::useDefaultFolder) {
                        Text(stringResource(R.string.use_default_folder))
                    }
                }
            }
        }

        PurePanel(spacing = PureSpacing.m) {
            SettingsHeader(
                icon = PureIcons.Tune,
                title = stringResource(R.string.theme)
            )
            // A segmented control states the three options are mutually exclusive; three loose
            // filter chips did not.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val labels = listOf(
                    stringResource(R.string.theme_system),
                    stringResource(R.string.theme_light),
                    stringResource(R.string.theme_dark)
                )
                labels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = state.themeMode == index,
                        onClick = { viewModel.setThemeMode(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, labels.size)
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsSwitch(
                    title = stringResource(R.string.dynamic_color),
                    description = stringResource(R.string.dynamic_color_body),
                    checked = state.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
            }
            SettingsSwitch(
                title = stringResource(R.string.keep_screen_on),
                description = stringResource(R.string.keep_screen_on_body),
                checked = state.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn
            )
        }

        PurePanel(spacing = PureSpacing.m) {
            SettingsHeader(
                icon = PureIcons.Delete,
                title = stringResource(R.string.local_data)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PureSpacing.s)) {
                OutlinedButton(
                    onClick = { viewModel.requestConfirm(ConfirmAction.CLEAR_TEMPORARY) }
                ) {
                    Text(stringResource(R.string.clear_temporary))
                }
                OutlinedButton(
                    onClick = { viewModel.requestConfirm(ConfirmAction.CLEAR_HISTORY) }
                ) {
                    Text(stringResource(R.string.clear_history))
                }
            }
        }

        OutlinedButton(
            onClick = { viewModel.navigate(Screen.ABOUT) },
            modifier = Modifier.fillMaxWidth().heightIn(min = PureSpacing.touchTarget)
        ) {
            Icon(PureIcons.Info, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(PureSpacing.s))
            Text(stringResource(R.string.about), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.heightIn(min = PureSpacing.xl))
    }
}

@Composable
private fun SettingsHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(PureSpacing.s))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

/** The whole row is the target, not just the control, and each toggle explains what it does. */
@Composable
private fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = PureSpacing.touchTarget)
            .padding(vertical = PureSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PureSpacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(PureSpacing.m))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
