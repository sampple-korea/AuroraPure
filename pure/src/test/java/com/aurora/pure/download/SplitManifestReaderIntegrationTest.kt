/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SplitManifestReaderIntegrationTest {
    @Test
    fun readsLanguageDeclarationsFromExternalBaseApk() {
        val fixture = System.getenv(FIXTURE_ENV)?.let(::File)
        assumeTrue("Set $FIXTURE_ENV to run the binary split metadata fixture", fixture?.isFile == true)

        val declarations = SplitManifestReader.read(requireNotNull(fixture))
        val localeKeys = declarations.flatMap(LanguageSplit::localeKeys)

        assertTrue(declarations.size > 10)
        assertTrue("English split missing", "en" in localeKeys)
        assertTrue("Korean split missing", "ko" in localeKeys)
        assertTrue(declarations.all { it.localeKeys.isNotEmpty() })
    }

    companion object {
        private const val FIXTURE_ENV = "AURORA_PURE_BASE_APK"
    }
}
