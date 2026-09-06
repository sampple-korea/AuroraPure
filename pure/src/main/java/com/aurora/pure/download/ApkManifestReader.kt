/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/** Reads only the signed identity fields needed to bind an APK to a download plan. */
object ApkManifestReader {
    data class Identity(
        val packageName: String,
        val versionCode: Long,
        val splitName: String?
    )

    fun read(apk: File): Identity {
        val bytes = ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry(MANIFEST_ENTRY)) {
                "APK has no AndroidManifest.xml"
            }
            require(entry.size in 1..MAX_MANIFEST_BYTES) { "APK manifest has an invalid size" }
            zip.getInputStream(entry).use { it.readBytes() }
        }
        require(bytes.size <= MAX_MANIFEST_BYTES) { "APK manifest is too large" }

        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT) continue
            if (parser.name != "manifest") continue

            val packageName = parser.stringAttribute("package").orEmpty()
            val versionCodeLow = parser.intAttribute("versionCode")
            val versionCodeMajor = parser.intAttribute("versionCodeMajor") ?: 0
            require(packageName.isNotBlank() && versionCodeLow != null) {
                "APK manifest identity is incomplete"
            }
            val longVersionCode = (versionCodeMajor.toLong() shl 32) or
                (versionCodeLow.toLong() and 0xffff_ffffL)
            return Identity(
                packageName = packageName,
                versionCode = longVersionCode,
                splitName = parser.stringAttribute("split")?.takeIf(String::isNotBlank)
            )
        }
        throw IllegalArgumentException("APK manifest root was not found")
    }

    private fun AndroidBinXmlParser.attributeIndex(name: String): Int? =
        (0 until attributeCount).firstOrNull { getAttributeName(it) == name }

    private fun AndroidBinXmlParser.stringAttribute(name: String): String? {
        val index = attributeIndex(name) ?: return null
        return if (getAttributeValueType(index) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
            getAttributeStringValue(index)
        } else {
            null
        }
    }

    private fun AndroidBinXmlParser.intAttribute(name: String): Int? {
        val index = attributeIndex(name) ?: return null
        return if (getAttributeValueType(index) == AndroidBinXmlParser.VALUE_TYPE_INT) {
            getAttributeIntValue(index)
        } else {
            null
        }
    }

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
}
