/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aurora.pure.R
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ConnectionState
import com.aurora.pure.data.DensityChoice
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.data.VerificationState

@Composable
internal fun architectureText(choice: ArchitectureChoice): String = stringResource(
    when (choice) {
        ArchitectureChoice.UNIVERSAL -> R.string.architecture_universal
        ArchitectureChoice.BOTH -> R.string.architecture_both
        ArchitectureChoice.BIT_64 -> R.string.architecture_64
        ArchitectureChoice.BIT_32 -> R.string.architecture_32
    }
)

@Composable
internal fun densityText(choice: DensityChoice): String = when (choice) {
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
internal fun statusText(status: TaskStatus): String = stringResource(
    when (status) {
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
)

@Composable
internal fun connectionText(state: ConnectionState): String = stringResource(
    when (state) {
        ConnectionState.IDLE -> R.string.connection_idle
        ConnectionState.CONNECTING -> R.string.connection_connecting
        ConnectionState.CONNECTED -> R.string.connection_connected
        ConnectionState.FAILED -> R.string.connection_failed
    }
)

/** One parsed check out of the stored verification summary. */
internal data class VerificationPart(val label: String, val state: VerificationState)

/**
 * The verification result is persisted as a machine string. Splitting it back into typed parts lets
 * the download card show three small badges instead of one long, unreadable line.
 */
@Composable
internal fun verificationParts(summary: String): List<VerificationPart> {
    val labels = mapOf(
        "integrity" to stringResource(R.string.verification_integrity),
        "signatures" to stringResource(R.string.verification_signatures),
        "package" to stringResource(R.string.verification_package)
    )
    return summary.split(", ").mapNotNull { component ->
        val key = component.substringBefore('=')
        val value = component.substringAfter('=', "")
        val label = labels[key] ?: return@mapNotNull null
        val state = when (value) {
            "verified" -> VerificationState.VERIFIED
            "failed" -> VerificationState.FAILED
            else -> VerificationState.UNAVAILABLE
        }
        VerificationPart(label, state)
    }
}

@Composable
internal fun verificationLimitations(summary: String): List<String> {
    val translations = mapOf(
        "Google Play supplied no reference hash for one or more APKs" to
            stringResource(R.string.limitation_no_reference_hash),
        "APK signature verification was unavailable" to
            stringResource(R.string.limitation_signature_unavailable),
        "The delivery references non-APK data" to
            stringResource(R.string.limitation_non_apk_data)
    )
    val raw = summary.split(", ").firstOrNull { it.startsWith("limitations=") }
        ?.substringAfter('=')
        ?: return emptyList()
    return raw.split("; ").map { translations[it] ?: it }
}

@Composable
internal fun verificationStateText(state: VerificationState): String = stringResource(
    when (state) {
        VerificationState.VERIFIED -> R.string.verification_verified
        VerificationState.FAILED -> R.string.verification_failed
        VerificationState.UNAVAILABLE -> R.string.verification_unavailable
    }
)
