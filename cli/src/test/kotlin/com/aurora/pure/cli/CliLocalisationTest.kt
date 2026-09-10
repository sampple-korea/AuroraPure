/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliLocalisationTest {
    private val originalDefault: Locale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefault)
    }

    /**
     * ResourceBundle consults the JVM default locale before the base bundle, so asking for English
     * on a Korean or Japanese machine used to return that machine's language and made --language
     * appear to do nothing.
     */
    @Test
    fun `requested language wins over the machine locale`() {
        val english = Messages("en")["status.history_empty"]

        listOf(Locale.KOREAN, Locale.JAPANESE, Locale.SIMPLIFIED_CHINESE).forEach { machine ->
            Locale.setDefault(machine)
            assertEquals(
                "English must survive a $machine default locale",
                english,
                Messages("en")["status.history_empty"]
            )
        }
    }

    @Test
    fun `each translated language differs from English`() {
        Locale.setDefault(Locale.ENGLISH)
        val english = Messages("en")["status.history_empty"]

        listOf("ko", "ja", "zh").forEach { language ->
            assertNotEquals(
                "$language must have its own translation",
                english,
                Messages(language)["status.history_empty"]
            )
        }
    }

    @Test
    fun `auto follows the machine locale`() {
        Locale.setDefault(Locale.KOREAN)
        assertEquals("ko", Messages("auto").code)
        Locale.setDefault(Locale.ITALIAN)
        assertEquals("en", Messages("auto").code)
    }

    @Test
    fun `the reported version comes from the build`() {
        assertNotEquals("unknown", CliVersion.value)
        assertTrue(
            "version must look like a release: ${CliVersion.value}",
            Regex("""^\d+\.\d+\.\d+$""").matches(CliVersion.value)
        )
        assertTrue(CliVersion.banner.endsWith(CliVersion.value))
    }

    @Test
    fun `the banner reports the running version, not a frozen literal`() {
        val title = Messages("en").text("app.title", CliVersion.value)

        assertTrue("banner was '$title'", title.contains(CliVersion.value))
    }

    @Test
    fun `column padding accounts for double-width glyphs`() {
        assertEquals(2, "ab".displayWidth())
        assertEquals(4, "구분".displayWidth())
        assertEquals(4, "種別".displayWidth())

        assertEquals("구분  ", "구분".padTo(6))
        assertEquals("ab    ", "ab".padTo(6))
        assertEquals(6, "구분".padTo(6).displayWidth())
        assertEquals(6, "ab".padTo(6).displayWidth())
    }

    @Test
    fun `padding never truncates a value wider than the column`() {
        assertEquals("wide", "wide".padTo(2))
    }

    /** A key added to English but not translated would otherwise surface as a runtime crash. */
    @Test
    fun `every translation carries the same keys as English`() {
        Locale.setDefault(Locale.ENGLISH)
        val english = Messages("en").keys()

        listOf("ko", "ja", "zh").forEach { language ->
            assertEquals(
                "$language bundle must match the English key set",
                english,
                Messages(language).keys()
            )
        }
    }

    private fun Messages.keys(): Set<String> = bundleKeys()
}
