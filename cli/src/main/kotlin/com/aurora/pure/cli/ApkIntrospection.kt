/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val splitName: String?,
    val minSdk: Int,
    val targetSdk: Int
)

data class LanguageSplit(
    val moduleName: String,
    val splitName: String,
    val localeKeys: List<String>
)

object ApkIntrospection {
    fun manifest(apk: File): ApkIdentity {
        val bytes = ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) {
                "APK has no AndroidManifest.xml"
            }
            require(entry.size in 1..MAX_MANIFEST_BYTES.toLong()) {
                "APK manifest has an invalid size"
            }
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        var packageName = ""
        var versionCode: Long? = null
        var splitName: String? = null
        var minSdk = 1
        var targetSdk = 0
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT) continue
            when (parser.name) {
                "manifest" -> {
                    packageName = parser.stringAttribute("package").orEmpty()
                    val low = parser.intAttribute("versionCode")
                    val major = parser.intAttribute("versionCodeMajor") ?: 0
                    require(packageName.isNotBlank() && low != null) {
                        "APK manifest identity is incomplete"
                    }
                    versionCode = (major.toLong() shl 32) or (low.toLong() and 0xffff_ffffL)
                    splitName = parser.stringAttribute("split")?.takeIf(String::isNotBlank)
                }
                "uses-sdk" -> {
                    minSdk = parser.intAttribute("minSdkVersion") ?: minSdk
                    targetSdk = parser.intAttribute("targetSdkVersion") ?: targetSdk
                }
            }
        }
        return ApkIdentity(
            packageName = packageName.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("APK manifest root was not found"),
            versionCode = versionCode
                ?: throw IllegalArgumentException("APK manifest version is missing"),
            splitName = splitName,
            minSdk = minSdk,
            targetSdk = targetSdk
        )
    }

    fun languageSplits(apk: File): List<LanguageSplit> = ZipFile(apk).use { zip ->
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && SPLIT_XML.matches(it.name) }
            .sortedBy { it.name }
            .flatMap { entry ->
                require(entry.size in 1..MAX_SPLIT_XML_BYTES.toLong()) {
                    "APK split metadata has an invalid size"
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                readLanguageDocument(bytes).asSequence()
            }
            .toList()
        entries.groupBy { it.moduleName to it.splitName }
            .map { (key, matching) ->
                LanguageSplit(
                    moduleName = key.first,
                    splitName = key.second,
                    localeKeys = matching.flatMap(LanguageSplit::localeKeys).distinct().sorted()
                )
            }
            .sortedWith(compareBy(LanguageSplit::moduleName, LanguageSplit::splitName))
    }

    private fun readLanguageDocument(bytes: ByteArray): List<LanguageSplit> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_SPLIT_XML_BYTES)
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val entries = mutableListOf<LanguageSplit>()
        var moduleDepth: Int? = null
        var moduleName = ""
        var languageDepth: Int? = null
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            when (parser.eventType) {
                AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                    if (parser.name == "module") {
                        moduleDepth = parser.depth
                        moduleName = parser.stringAttribute("name").orEmpty().trim()
                        require(MODULE_NAME.matches(moduleName))
                    }
                    if (parser.name == "language" && moduleDepth != null) {
                        languageDepth = parser.depth
                    }
                    if (parser.name == "entry" && languageDepth != null && parser.depth > languageDepth) {
                        val locale = parser.stringAttribute("key").orEmpty().trim()
                        val split = parser.stringAttribute("split").orEmpty().trim()
                        require(LOCALE_KEY.matches(locale) && (split.isEmpty() || SPLIT_NAME.matches(split)))
                        entries += LanguageSplit(moduleName, split, listOf(locale))
                    }
                }
                AndroidBinXmlParser.EVENT_END_ELEMENT -> {
                    if (parser.name == "language" && parser.depth == languageDepth) languageDepth = null
                    if (parser.name == "module" && parser.depth == moduleDepth) {
                        moduleDepth = null
                        moduleName = ""
                    }
                }
            }
        }
        return entries
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

    private val SPLIT_XML = Regex("^res/xml/splits[0-9]+\\.xml$")
    private val LOCALE_KEY = Regex("^[A-Za-z0-9_-]{1,35}$")
    private val MODULE_NAME = Regex("^[A-Za-z0-9._-]{0,200}$")
    private val SPLIT_NAME = Regex("^[A-Za-z0-9._-]{1,200}$")
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val MAX_SPLIT_XML_BYTES = 8 * 1024 * 1024
}
