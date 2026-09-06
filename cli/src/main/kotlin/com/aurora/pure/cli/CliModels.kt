/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.nio.file.Path
import java.security.MessageDigest

enum class AbiVariant(
    val label: String,
    val bitness: Int,
    val platforms: List<String>,
    val profileResource: String
) {
    ARM64("arm64-v8a", 64, listOf("arm64-v8a"), "gplayapi_px_9a.properties"),
    ARM32("armeabi-v7a", 32, listOf("armeabi-v7a", "armeabi"), "gplayapi_rm_5_pro.properties"),
    X86_64("x86_64", 64, listOf("x86_64"), "gplayapi_google_kiwi_x86_64.properties"),
    X86("x86", 32, listOf("x86"), "gplayapi_google_kiwi_x86_64.properties")
}

enum class ArchitectureMode(val cliName: String) {
    UNIVERSAL("universal"),
    BOTH("both"),
    BIT64("64"),
    BIT32("32"),
    ARM64("arm64"),
    ARM32("arm32"),
    X86_64("x86_64"),
    X86("x86");

    val variants: List<AbiVariant>
        get() = when (this) {
            UNIVERSAL -> AbiVariant.entries
            BOTH -> listOf(AbiVariant.ARM64, AbiVariant.ARM32)
            BIT64, ARM64 -> listOf(AbiVariant.ARM64)
            BIT32, ARM32 -> listOf(AbiVariant.ARM32)
            X86_64 -> listOf(AbiVariant.X86_64)
            X86 -> listOf(AbiVariant.X86)
        }

    companion object {
        fun parse(value: String): ArchitectureMode = entries.firstOrNull {
            it.cliName.equals(value.trim(), true) || it.name.equals(value.trim(), true)
        } ?: throw IllegalArgumentException(
            "Unknown architecture '$value' (use universal, both, 64, 32, arm64, arm32, x86_64, or x86)"
        )
    }
}

data class DensityMode(val name: String, val densities: List<Int>) {
    companion object {
        val standard = listOf(120, 160, 213, 240, 320, 480, 640)

        fun parse(value: String, currentDpi: Int = DEFAULT_DPI): DensityMode {
            val normalized = value.trim().lowercase()
            return when (normalized) {
                "current", "device" -> DensityMode("current", listOf(currentDpi))
                "all", "universal" -> DensityMode("all", standard)
                "ldpi" -> DensityMode("ldpi", listOf(120))
                "mdpi" -> DensityMode("mdpi", listOf(160))
                "tvdpi" -> DensityMode("tvdpi", listOf(213))
                "hdpi" -> DensityMode("hdpi", listOf(240))
                "xhdpi" -> DensityMode("xhdpi", listOf(320))
                "xxhdpi" -> DensityMode("xxhdpi", listOf(480))
                "xxxhdpi" -> DensityMode("xxxhdpi", listOf(640))
                else -> normalized.removeSuffix("dpi").toIntOrNull()
                    ?.takeIf { it in 72..1000 }
                    ?.let { DensityMode("${it}dpi", listOf(it)) }
                    ?: throw IllegalArgumentException(
                        "Unknown density '$value' (use current, all, ldpi…xxxhdpi, or a DPI number)"
                    )
            }
        }

        const val DEFAULT_DPI = 420
    }
}

data class DeliveryProfile(
    val abi: AbiVariant,
    val densityDpi: Int,
    val sdkVersion: Int
) {
    val id: String get() = "${abi.label}-${densityDpi}dpi-api$sdkVersion"
}

data class AppInfo(
    val packageName: String,
    val name: String,
    val developer: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val size: Long,
    val isFree: Boolean,
    val description: String
)

data class Artifact(
    val profile: DeliveryProfile,
    val ownerPackage: String,
    val ownerVersionCode: Long,
    val name: String,
    val url: String,
    val size: Long,
    val type: String,
    val sha1: String,
    val sha256: String,
    val dependency: Boolean,
    val locales: List<String> = emptyList()
) {
    val safeName: String
        get() = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "artifact.apk"

    val relativePath: String
        get() = listOf(
            profile.abi.label,
            "${profile.densityDpi}dpi",
            "api${profile.sdkVersion}",
            ownerPackage,
            safeName
        ).joinToString("/")

    fun identity(): String? {
        val digest = when {
            sha256.isNotBlank() -> "sha256:${sha256.lowercase()}"
            sha1.isNotBlank() -> "sha1:${sha1.lowercase()}"
            else -> return null
        }
        return listOf(ownerPackage, ownerVersionCode, name, type, size, digest).joinToString("|")
    }
}

data class DeliveryVariant(
    val id: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val profiles: List<DeliveryProfile>,
    val downloadProfiles: List<DeliveryProfile>,
    val artifactCount: Int,
    val totalBytes: Long,
    val aggregate: Boolean = false,
    val universal: Boolean = false
) {
    val architectures: List<String> get() = profiles.map { it.abi.label }.distinct()
    val densities: List<Int> get() = profiles.map { it.densityDpi }.distinct().sorted()
    val androidApis: List<Int> get() = profiles.map { it.sdkVersion }.distinct().sorted()
}

data class DownloadPlan(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val checkedAt: Long,
    val architectureMode: ArchitectureMode,
    val densityMode: DensityMode,
    val profiles: List<DeliveryProfile>,
    val requestedLocales: List<String>,
    val artifacts: List<Artifact>,
    val hasAdditionalData: Boolean
) {
    val uniqueArtifacts: List<Artifact>
        get() = artifacts.distinctBy { it.identity() ?: it.relativePath }
    val totalBytes: Long get() = uniqueArtifacts.sumOf { it.size.coerceAtLeast(0) }
    val isSingleApk: Boolean get() = uniqueArtifacts.size == 1 && !uniqueArtifacts.single().dependency

    val fingerprint: String
        get() = sha256(buildString {
            append(packageName).append('|').append(versionCode).append('|')
            append(minSdk).append('|').append(targetSdk).append('\n')
            profiles.sortedBy(DeliveryProfile::id).forEach { append(it.id).append('\n') }
            requestedLocales.sorted().forEach { append("locale|").append(it).append('\n') }
            uniqueArtifacts.sortedBy(Artifact::relativePath).forEach { artifact ->
                append(artifact.identity() ?: artifact.relativePath).append('\n')
            }
        })
}

data class DownloadResult(
    val path: Path,
    val apkCount: Int,
    val bytes: Long,
    val integrityVerified: Boolean,
    val signaturesVerified: Boolean
)

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return if (unit == 0) "$bytes ${units[unit]}" else "%.1f %s".format(value, units[unit])
}
