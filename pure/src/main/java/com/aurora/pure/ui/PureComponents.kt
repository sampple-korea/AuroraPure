/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.data.VerificationState

/**
 * A blank screen with one line of grey text tells the user nothing. Every empty or failed state in
 * the app routes through here so it explains what happened and offers the next step.
 */
@Composable
internal fun PureEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PureSpacing.xl, vertical = PureSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PureSpacing.m, Alignment.CenterVertically)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(PureSpacing.xs))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A compact, colour-coded state badge. Reads faster in a list than a sentence of status text. */
@Composable
internal fun PureStatusBadge(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(container)
            .padding(horizontal = PureSpacing.s + PureSpacing.xxs, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PureSpacing.xs)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1
        )
    }
}

@Composable
internal fun statusBadgeColors(status: TaskStatus): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        TaskStatus.COMPLETED -> scheme.primaryContainer to scheme.onPrimaryContainer
        TaskStatus.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        TaskStatus.VERSION_CHANGED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        TaskStatus.CANCELLED, TaskStatus.PAUSED ->
            scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
}

@Composable
internal fun verificationBadgeColors(state: VerificationState): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        VerificationState.VERIFIED -> scheme.primaryContainer to scheme.onPrimaryContainer
        VerificationState.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        VerificationState.UNAVAILABLE -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

/** Label on the left, machine value on the right, set monospaced so columns line up. */
@Composable
internal fun PureDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(PureSpacing.m))
        Text(
            value,
            style = if (monospace) {
                technicalTextStyle(MaterialTheme.typography.bodyMedium)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The single card style the app uses, so panels stay visually consistent across screens. */
@Composable
internal fun PurePanel(
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    spacing: Dp = PureSpacing.s,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (tonal) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            Modifier.padding(PureSpacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

@Composable
internal fun PureSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}
