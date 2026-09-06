/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipRangeIndexTest {
    @Test
    fun locatesSplitMetadataInAStandardZipDirectory() {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("res/xml/splits0.xml"))
                zip.write(ByteArray(512) { it.toByte() })
                zip.closeEntry()
            }
        }.toByteArray()

        val directory = ZipRangeIndex.findDirectory(archive, 0, archive.size.toLong())
        val directoryBytes = archive.copyOfRange(
            directory.offset.toInt(),
            (directory.offset + directory.size).toInt()
        )
        val entries = ZipRangeIndex.readEntries(directoryBytes, directory.entryCount)

        assertEquals(2, entries.size)
        val split = entries.single { it.name == "res/xml/splits0.xml" }
        assertEquals(512, split.uncompressedSize)
        assertTrue(split.compressedSize > 0)
        assertTrue(split.localHeaderOffset >= 0)
    }
}
