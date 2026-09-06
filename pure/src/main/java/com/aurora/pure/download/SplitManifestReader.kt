/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/** A language key and the APK split which Google Play declares for it. */
data class LanguageSplit(
    val moduleName: String,
    val splitName: String,
    val localeKeys: List<String>
)

/** Reads bundletool's binary res/xml/splits*.xml metadata without modifying the APK. */
object SplitManifestReader {
    fun read(apk: File): List<LanguageSplit> = ZipFile(apk).use { zip ->
        val documents = zip.entries().asSequence()
            .filter { entry -> !entry.isDirectory && SPLIT_MANIFEST.matches(entry.name) }
            .sortedBy { it.name }
            .map { entry ->
                require(entry.size in 1..MAX_DOCUMENT_BYTES) {
                    "APK split metadata has an invalid size"
                }
                zip.getInputStream(entry).use { it.readBytes() }.also { bytes ->
                    require(bytes.size <= MAX_DOCUMENT_BYTES) { "APK split metadata is too large" }
                }
            }
            .toList()
        readDocuments(documents)
    }

    fun readDocuments(documents: List<ByteArray>): List<LanguageSplit> {
        val entries = documents.flatMap(::readDocument)
        return entries.groupBy { it.moduleName to it.splitName }
            .map { (identity, matching) ->
                LanguageSplit(
                    moduleName = identity.first,
                    splitName = identity.second,
                    localeKeys = matching.map(LanguageEntry::localeKey).distinct().sorted()
                )
            }
            .sortedWith(compareBy(LanguageSplit::moduleName, LanguageSplit::splitName))
    }

    private fun readDocument(bytes: ByteArray): List<LanguageEntry> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_DOCUMENT_BYTES) {
            "APK split metadata has an invalid size"
        }
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val entries = mutableListOf<LanguageEntry>()
        var moduleDepth: Int? = null
        var moduleName = ""
        var languageDepth: Int? = null

        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            when (parser.eventType) {
                AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                    if (parser.name == "module") {
                        moduleDepth = parser.depth
                        moduleName = parser.stringAttribute("name").orEmpty().trim()
                        require(MODULE_NAME.matches(moduleName)) {
                            "APK language module metadata is malformed"
                        }
                    }
                    if (parser.name == "language" && moduleDepth != null) {
                        languageDepth = parser.depth
                    }
                    if (parser.name == "entry" && languageDepth != null && parser.depth > languageDepth) {
                        val key = parser.stringAttribute("key").orEmpty().trim()
                        val split = parser.stringAttribute("split").orEmpty().trim()
                        require(LOCALE_KEY.matches(key) && (split.isEmpty() || SPLIT_NAME.matches(split))) {
                            "APK language split metadata is malformed"
                        }
                        entries += LanguageEntry(moduleName, key, split)
                    }
                }

                AndroidBinXmlParser.EVENT_END_ELEMENT -> {
                    if (parser.name == "language" && parser.depth == languageDepth) {
                        languageDepth = null
                    }
                    if (parser.name == "module" && parser.depth == moduleDepth) {
                        moduleDepth = null
                        moduleName = ""
                    }
                }
            }
        }
        return entries
    }

    private fun AndroidBinXmlParser.stringAttribute(name: String): String? {
        val index = (0 until attributeCount).firstOrNull { getAttributeName(it) == name }
            ?: return null
        return if (getAttributeValueType(index) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
            getAttributeStringValue(index)
        } else {
            null
        }
    }

    private data class LanguageEntry(
        val moduleName: String,
        val localeKey: String,
        val splitName: String
    )

    private val SPLIT_MANIFEST = Regex("^res/xml/splits[0-9]+\\.xml$")
    private val LOCALE_KEY = Regex("^[A-Za-z0-9_-]{1,35}$")
    private val MODULE_NAME = Regex("^[A-Za-z0-9._-]{0,200}$")
    private val SPLIT_NAME = Regex("^[A-Za-z0-9._-]{1,200}$")
    private const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
}
