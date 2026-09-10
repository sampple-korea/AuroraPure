/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** A tap target inside a component that already draws its own ripple. */
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

internal fun Context.copyToClipboard(value: String, label: String = value) {
    runCatching {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
    }
}

/**
 * Android 13 and newer shows a system confirmation whenever an app writes the clipboard. Below
 * that the app has to say so itself, or a long-press appears to have done nothing.
 */
internal val needsOwnCopyConfirmation: Boolean
    get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

/** Copies [value] and reports back only when the platform will not confirm it for us. */
internal fun Context.copyWithFeedback(value: String, onCopied: (String) -> Unit) {
    copyToClipboard(value)
    if (needsOwnCopyConfirmation) onCopied(value)
}

/**
 * True when the clipboard holds plain text. This inspects only the clip *description*, which does
 * not count as reading clipboard content, so it never triggers the system's paste notification —
 * the content itself is read once, on an explicit tap.
 */
internal fun Context.clipboardHasText(): Boolean = runCatching {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.hasPrimaryClip() &&
        clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
}.getOrDefault(false)

internal fun Context.readClipboardText(): String = runCatching {
    getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        .orEmpty()
}.getOrDefault("")
