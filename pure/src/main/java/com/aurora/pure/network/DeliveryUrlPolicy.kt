/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object DeliveryUrlPolicy {
    fun isAllowed(url: String): Boolean = runCatching { isAllowed(url.toHttpUrl()) }
        .getOrDefault(false)

    fun isAllowed(url: HttpUrl): Boolean =
        url.isHttps && ALLOWED_GOOGLE_SUFFIXES.any { suffix ->
            url.host == suffix || url.host.endsWith(".$suffix")
        }

    fun safeEndpoint(url: String): String = runCatching {
        val parsed = url.toHttpUrl()
        "${parsed.scheme}://${parsed.host}"
    }.getOrDefault("<invalid URL>")

    private val ALLOWED_GOOGLE_SUFFIXES = setOf(
        "google.com",
        "googleapis.com",
        "googleusercontent.com",
        "gvt1.com",
        "ggpht.com",
        "googlevideo.com"
    )
}
