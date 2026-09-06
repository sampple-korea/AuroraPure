/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.data

import java.io.File
import java.security.MessageDigest

enum class Screen {
    SEARCH,
    DETAILS,
    DOWNLOADS,
    SETTINGS,
    ABOUT
}

enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED
}

enum class TaskStatus {
    QUEUED,
    CHECKING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    EXPORTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    VERSION_CHANGED;

    val isActive: Boolean
        get() = this in setOf(QUEUED, CHECKING, DOWNLOADING, VERIFYING, EXPORTING)
}

enum class VerificationState {
    VERIFIED,
    FAILED,
    UNAVAILABLE
}

enum class ArchitectureChoice {
    BOTH,
    BIT_64,
    BIT_32;

    val variants: List<ArchitectureVariant>
        get() = when (this) {
            BOTH -> listOf(ArchitectureVariant.BIT_64, ArchitectureVariant.BIT_32)
            BIT_64 -> listOf(ArchitectureVariant.BIT_64)
            BIT_32 -> listOf(ArchitectureVariant.BIT_32)
        }

    companion object {
        fun fromStored(value: String): ArchitectureChoice =
            entries.firstOrNull { it.name == value } ?: BOTH
    }
}

enum class ArchitectureVariant(
    val archiveDirectory: String,
    val bitness: Int
) {
    BIT_64("64bit", 64),
    BIT_32("32bit", 32)
}

data class DeliveryProfile(
    val variant: ArchitectureVariant,
    val platforms: List<String>
) {
    init {
        require(platforms.isNotEmpty()) { "A delivery profile needs at least one ABI" }
    }

    val primaryAbi: String get() = platforms.first()
}

data class AppSummary(
    val packageName: String,
    val displayName: String,
    val developerName: String,
    val iconUrl: String,
    val versionName: String,
    val versionCode: Long,
    val size: Long,
    val isFree: Boolean,
    val shortDescription: String,
    val checkedAt: Long = System.currentTimeMillis()
)

data class ArtifactPlan(
    val variant: ArchitectureVariant,
    val ownerPackage: String,
    val ownerVersionCode: Long,
    val name: String,
    val url: String,
    val size: Long,
    val type: String,
    val sha1: String,
    val sha256: String,
    val isDependency: Boolean,
    val localeKeys: List<String> = emptyList()
) {
    val relativePath: String
        get() {
            val safeName = safePathSegment(name.substringAfterLast('/').substringAfterLast('\\'))
            val ownerPath = if (isDependency) {
                "dependencies/${safePathSegment(ownerPackage)}/$safeName"
            } else {
                "app/$safeName"
            }
            return "variants/${variant.archiveDirectory}/$ownerPath"
        }

    private fun safePathSegment(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "artifact.apk"
    }
}

data class DownloadPlan(
    val id: String,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val checkedAt: Long,
    val deviceDescription: String,
    val architectureChoice: ArchitectureChoice,
    val deliveryProfiles: List<DeliveryProfile>,
    val requestedLocales: List<String>,
    val artifacts: List<ArtifactPlan>,
    val hasAdditionalData: Boolean
) {
    val totalBytes: Long get() = artifacts.sumOf { it.size.coerceAtLeast(0) }
    val isSingleApk: Boolean get() = artifacts.size == 1 && !artifacts.single().isDependency

    fun fingerprint(): String {
        val canonical = buildString {
            append(packageName).append('|').append(versionCode).append('|')
            append(deviceDescription).append('|').append(architectureChoice.name).append('|')
            append(hasAdditionalData).append('\n')
            deliveryProfiles.forEach { profile ->
                append(profile.variant.name).append('|')
                append(profile.platforms.joinToString(",")).append('\n')
            }
            append("locales|").append(requestedLocales.joinToString(",")).append('\n')
            artifacts.sortedBy { it.relativePath }.forEach {
                append(it.variant.name).append('|')
                append(it.ownerPackage).append('|')
                append(it.ownerVersionCode).append('|')
                append(it.relativePath).append('|')
                append(it.size).append('|')
                append(it.sha256).append('|')
                append(it.sha1).append('|')
                append(it.localeKeys.sorted().joinToString(",")).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

data class DownloadRecord(
    val id: String,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val architectureChoice: ArchitectureChoice = ArchitectureChoice.BOTH,
    val planFingerprint: String,
    val checkedAt: Long,
    val createdAt: Long,
    val completedAt: Long = 0,
    val status: TaskStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val outputUri: String = "",
    val outputName: String = "",
    val outputSize: Long = 0,
    val verification: String = "",
    val hasAdditionalData: Boolean = false,
    val error: String = ""
)

data class FileVerification(
    val relativePath: String,
    val variant: ArchitectureVariant,
    val ownerPackage: String,
    val size: Long,
    val sha256: String,
    val integrity: VerificationState,
    val signature: VerificationState,
    val signerSha256: List<String>
)

data class VerificationReport(
    val files: List<FileVerification>,
    val integrity: VerificationState,
    val signatures: VerificationState,
    val packageMatch: VerificationState,
    val limitations: List<String>
) {
    val isExportable: Boolean
        get() = integrity != VerificationState.FAILED &&
            signatures != VerificationState.FAILED &&
            packageMatch != VerificationState.FAILED

    fun summary(): String = buildString {
        append("integrity=").append(integrity.name.lowercase())
        append(", signatures=").append(signatures.name.lowercase())
        append(", package=").append(packageMatch.name.lowercase())
        if (limitations.isNotEmpty()) append(", limitations=").append(limitations.joinToString("; "))
    }
}

data class ExportResult(
    val uri: String,
    val displayName: String,
    val size: Long
)

data class DownloadedArtifact(
    val plan: ArtifactPlan,
    val file: File
)

data class DownloadOutcome(
    val plan: DownloadPlan,
    val artifacts: List<DownloadedArtifact>,
    val verification: VerificationReport
)

data class DownloadConfirmation(
    val previous: AppSummary,
    val plan: DownloadPlan,
    val reason: String,
    val changed: Boolean
)

data class PureUiState(
    val screen: Screen = Screen.SEARCH,
    val query: String = "",
    val results: List<AppSummary> = emptyList(),
    val selected: AppSummary? = null,
    val records: List<DownloadRecord> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
    val connection: ConnectionState = ConnectionState.IDLE,
    val confirmation: DownloadConfirmation? = null,
    val architectureChoice: ArchitectureChoice = ArchitectureChoice.BOTH,
    val themeMode: Int = 0,
    val keepScreenOn: Boolean = false,
    val customFolderUri: String = ""
)
