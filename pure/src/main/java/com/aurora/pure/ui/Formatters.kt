/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aurora.pure.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "${value.toLong()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

internal fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMillis))
}

internal fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "" else "${formatBytes(bytesPerSecond)}/s"

/**
 * Deliberately coarse. A download's remaining time is an estimate, and showing it to the second
 * would imply a precision the transfer rate does not have.
 */
@Composable
internal fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.duration_seconds, seconds)
    seconds < 3600 -> stringResource(R.string.duration_minutes, seconds / 60)
    else -> stringResource(R.string.duration_hours, seconds / 3600, (seconds % 3600) / 60)
}
