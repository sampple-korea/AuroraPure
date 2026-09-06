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
    val ownerPackage: String,
    val ownerVersionCode: Long,
    val name: String,
    val url: String,
    val size: Long,
    val type: String,
    val sha1: String,
    val sha256: String,
    val isDependency: Boolean
) {
    val relativePath: String
        get() {
            val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            return if (isDependency) {
                "dependencies/$ownerPackage/$safeName"
            } else {
                "app/$safeName"
            }
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
    val artifacts: List<ArtifactPlan>,
    val hasAdditionalData: Boolean
) {
    val totalBytes: Long get() = artifacts.sumOf { it.size.coerceAtLeast(0) }
    val isSingleApk: Boolean get() = artifacts.size == 1 && !artifacts.single().isDependency

    fun fingerprint(): String {
        val canonical = buildString {
            append(packageName).append('|').append(versionCode).append('|')
            artifacts.sortedBy { it.relativePath }.forEach {
                append(it.ownerPackage).append('|')
                append(it.ownerVersionCode).append('|')
                append(it.relativePath).append('|')
                append(it.size).append('|')
                append(it.sha256).append('|')
                append(it.sha1).append('\n')
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
    val reason: String
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
    val themeMode: Int = 0,
    val keepScreenOn: Boolean = false,
    val customFolderUri: String = ""
)
