/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.util.Properties
import picocli.CommandLine

/**
 * The released version, generated into the resources by the build. It used to be typed into
 * `Main.kt` and into every message bundle, which meant `--version` and the interactive banner kept
 * naming an older release than the one actually being run.
 */
object CliVersion {
    val value: String by lazy {
        runCatching {
            CliVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: FALLBACK
    }

    val banner: String get() = "Aurora Pure CLI $value"

    private const val RESOURCE = "/aurora-pure-version.properties"

    /** Only reached when the CLI runs from a classpath without its own resources, such as an IDE. */
    private const val FALLBACK = "unknown"
}

class CliVersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf(CliVersion.banner)
}
