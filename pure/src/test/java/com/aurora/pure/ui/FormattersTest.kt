/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun unknownAndEmptySizesRenderAsADash() {
        assertEquals("—", formatBytes(0))
        assertEquals("—", formatBytes(-1))
    }

    @Test
    fun byteSizesStayWholeNumbers() {
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun largerSizesScaleToOneDecimal() {
        assertEquals("1.0 KB", formatBytes(1_024))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun sizesNeverScalePastGigabytes() {
        assertEquals("1024.0 GB", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun speedIsBlankUntilThereIsARateToReport() {
        assertEquals("", formatSpeed(0))
        assertEquals("", formatSpeed(-5))
    }

    @Test
    fun speedIsRenderedPerSecond() {
        assertEquals("1.5 MB/s", formatSpeed(1_572_864))
    }
}
