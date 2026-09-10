/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.data

import androidx.compose.runtime.Immutable
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
    UNIVERSAL,
    BOTH,
    BIT_64,
    BIT_32;

    companion object {
        fun fromStored(value: String): ArchitectureChoice =
            entries.firstOrNull { it.name == value } ?: BOTH
    }
}

enum class ArchitectureVariant(
    val archiveDirectory: String,
    val bitness: Int,
    val platforms: List<String>
) {
    ARM_64("arm64-v8a", 64, listOf("arm64-v8a")),
    ARM_32("armeabi-v7a", 32, listOf("armeabi-v7a", "armeabi")),
    X86_64("x86_64", 64, listOf("x86_64")),
    X86("x86", 32, listOf("x86"))
}

enum class DensityChoice(val dpi: Int?) {
    CURRENT(null),
    ALL(-1),
    LDPI(120),
    MDPI(160),
    TVDPI(213),
    HDPI(240),
    XHDPI(320),
    XXHDPI(480),
    XXXHDPI(640);

    fun resolve(currentDpi: Int): List<Int> = when (this) {
        CURRENT -> listOf(currentDpi)
        ALL -> STANDARD_DENSITIES
        else -> listOf(requireNotNull(dpi))
    }

    companion object {
        val STANDARD_DENSITIES = listOf(120, 160, 213, 240, 320, 480, 640)

        fun fromStored(value: String): DensityChoice =
            entries.firstOrNull { it.name == value } ?: CURRENT
    }
}

@Immutable
data class DeliveryProfile(
    val variant: ArchitectureVariant,
    val platforms: List<String>,
    val densityDpi: Int,
    val sdkVersion: Int
) {
    init {
        require(platforms.isNotEmpty()) { "A delivery profile needs at least one ABI" }
        require(densityDpi > 0) { "A delivery profile needs a positive screen density" }
        require(sdkVersion >= 21) { "A delivery profile needs Android 5.0 or newer" }
    }

    val primaryAbi: String get() = platforms.first()
    val id: String get() = "${variant.archiveDirectory}-${densityDpi}dpi-api$sdkVersion"
}

@Immutable
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
    val architectures: List<ArchitectureVariant>
        get() = profiles.map(DeliveryProfile::variant).distinct()
    val densityDpis: List<Int>
        get() = profiles.map(DeliveryProfile::densityDpi).distinct().sorted()
    val testedSdkVersions: List<Int>
        get() = profiles.map(DeliveryProfile::sdkVersion).distinct().sorted()

    /**
     * The discovery screen always scans the complete matrix. These values describe only the
     * selected result and are carried into the immutable download plan; they are not filters
     * chosen before discovery.
     */
    val downloadArchitectureChoice: ArchitectureChoice
        get() {
            if (universal) return ArchitectureChoice.UNIVERSAL
            val selected = downloadProfiles.map(DeliveryProfile::variant).distinct()
            if (selected.size != 1) {
                val armFamily = selected.all {
                    it == ArchitectureVariant.ARM_64 || it == ArchitectureVariant.ARM_32
                }
                val x86Family = selected.all {
                    it == ArchitectureVariant.X86_64 || it == ArchitectureVariant.X86
                }
                return if (armFamily || x86Family) {
                    ArchitectureChoice.BOTH
                } else {
                    ArchitectureChoice.UNIVERSAL
                }
            }
            return if (selected.single().bitness == 64) {
                ArchitectureChoice.BIT_64
            } else {
                ArchitectureChoice.BIT_32
            }
        }

    val downloadDensityChoice: DensityChoice
        get() {
            val selected = downloadProfiles.map(DeliveryProfile::densityDpi).distinct()
            if (selected.size != 1) return DensityChoice.ALL
            return DensityChoice.entries.firstOrNull { it.dpi == selected.single() }
                ?: DensityChoice.CURRENT
        }
}

@Immutable
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

/**
 * Live shape of a complete discovery scan. [totalPaths] is known before the first request, so the
 * scan can be reported as a determinate fraction instead of an open-ended spinner.
 */
@Immutable
data class DiscoveryProgress(
    val probesCompleted: Int = 0,
    val pathsCompleted: Int = 0,
    val totalPaths: Int = 0,
    val active: List<DeliveryProfile> = emptyList()
) {
    val fraction: Float
        get() = if (totalPaths <= 0) 0f else (pathsCompleted.toFloat() / totalPaths).coerceIn(0f, 1f)
}

@Immutable
data class ArtifactPlan(
    val variant: ArchitectureVariant,
    val densityDpi: Int,
    val sdkVersion: Int,
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
            return "profiles/${variant.archiveDirectory}/${densityDpi}dpi/api$sdkVersion/$ownerPath"
        }

    fun contentIdentity(): String? {
        val referenceHash = when {
            sha256.isNotBlank() -> "sha256:${sha256.lowercase()}"
            sha1.isNotBlank() -> "sha1:${sha1.lowercase()}"
            else -> return null
        }
        return listOf(
            ownerPackage,
            ownerVersionCode.toString(),
            name,
            type,
            size.toString(),
            referenceHash
        ).joinToString("|")
    }

    private fun safePathSegment(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "artifact.apk"
    }
}

@Immutable
data class DownloadPlan(
    val id: String,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val checkedAt: Long,
    val deviceDescription: String,
    val architectureChoice: ArchitectureChoice,
    val densityChoice: DensityChoice,
    val deliveryProfiles: List<DeliveryProfile>,
    val requestedLocales: List<String>,
    val artifacts: List<ArtifactPlan>,
    val hasAdditionalData: Boolean
) {
    val uniqueArtifacts: List<ArtifactPlan>
        get() = artifacts.distinctBy { it.contentIdentity() ?: it.relativePath }
    val totalBytes: Long get() = uniqueArtifacts.sumOf { it.size.coerceAtLeast(0) }
    val isSingleApk: Boolean
        get() = uniqueArtifacts.size == 1 && !uniqueArtifacts.single().isDependency

    fun fingerprint(): String {
        val canonical = buildString {
            append(packageName).append('|').append(versionCode).append('|')
            append(minSdk).append('|').append(targetSdk).append('|')
            append(deviceDescription).append('|').append(architectureChoice.name).append('|')
            append(densityChoice.name).append('|')
            append(hasAdditionalData).append('\n')
            deliveryProfiles.forEach { profile ->
                append(profile.variant.name).append('|')
                append(profile.platforms.joinToString(",")).append('|')
                append(profile.densityDpi).append('|')
                append(profile.sdkVersion).append('\n')
            }
            append("locales|").append(requestedLocales.joinToString(",")).append('\n')
            artifacts.sortedBy { it.relativePath }.forEach {
                append(it.variant.name).append('|')
                append(it.densityDpi).append('|')
                append(it.sdkVersion).append('|')
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

@Immutable
data class DownloadRecord(
    val id: String,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val architectureChoice: ArchitectureChoice = ArchitectureChoice.BOTH,
    val densityChoice: DensityChoice = DensityChoice.CURRENT,
    val deliveryProfiles: List<DeliveryProfile> = emptyList(),
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
    val error: String = "",
    /** Live transfer rate. Session-only: a resumed record starts measuring again from zero. */
    val bytesPerSecond: Long = 0
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    /**
     * Seconds left at the current rate, or `null` when there is nothing worth extrapolating from.
     * Anything under a second is reported as absent rather than as "0 seconds left", which reads
     * as a broken estimate rather than an almost-finished transfer.
     */
    val secondsRemaining: Long?
        get() {
            if (bytesPerSecond <= 0 || totalBytes <= 0) return null
            val remaining = totalBytes - downloadedBytes
            if (remaining <= 0) return null
            return (remaining / bytesPerSecond).takeIf { it > 0 }
        }
}

@Immutable
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

@Immutable
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

@Immutable
data class ExportResult(
    val uri: String,
    val displayName: String,
    val size: Long
)

@Immutable
data class DownloadedArtifact(
    val plan: ArtifactPlan,
    val file: File
)

@Immutable
data class DownloadOutcome(
    val plan: DownloadPlan,
    val artifacts: List<DownloadedArtifact>,
    val verification: VerificationReport
)

@Immutable
data class DownloadConfirmation(
    val previous: AppSummary,
    val plan: DownloadPlan,
    val reason: String,
    val changed: Boolean
)

/** What the user can act on next after a message, so a failure is never a dead end. */
enum class MessageAction {
    NONE,
    RETRY_SEARCH,
    RETRY_DISCOVERY,
    OPEN_DOWNLOADS
}

@Immutable
data class UiMessage(
    val text: String,
    val action: MessageAction = MessageAction.NONE,
    val id: Long = nextId()
) {
    companion object {
        private var counter = 0L

        @Synchronized
        private fun nextId(): Long = ++counter
    }
}

/** Destructive actions route through here so nothing irreversible happens on a single tap. */
enum class ConfirmAction {
    CLEAR_HISTORY,
    CLEAR_TEMPORARY,
    DELETE_OUTPUT,
    REMOVE_RECORD,
    CANCEL_DOWNLOAD
}

@Immutable
data class PendingConfirm(
    val action: ConfirmAction,
    val record: DownloadRecord? = null
)

@Immutable
data class PureUiState(
    val screen: Screen = Screen.SEARCH,
    val query: String = "",
    val results: List<AppSummary> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val searched: Boolean = false,
    val searching: Boolean = false,
    val selected: AppSummary? = null,
    val variants: List<DeliveryVariant> = emptyList(),
    val selectedVariantId: String = "",
    val discoveringVariants: Boolean = false,
    val discovery: DiscoveryProgress = DiscoveryProgress(),
    val discoveryFailed: Boolean = false,
    val discoveryIncomplete: Boolean = false,
    val records: List<DownloadRecord> = emptyList(),
    val restoringRecords: Boolean = true,
    val busy: Boolean = false,
    val message: UiMessage? = null,
    val connection: ConnectionState = ConnectionState.IDLE,
    val confirmation: DownloadConfirmation? = null,
    val pendingConfirm: PendingConfirm? = null,
    val architectureChoice: ArchitectureChoice = ArchitectureChoice.UNIVERSAL,
    val densityChoice: DensityChoice = DensityChoice.ALL,
    val themeMode: Int = 0,
    val dynamicColor: Boolean = false,
    val keepScreenOn: Boolean = false,
    val customFolderUri: String = ""
) {
    val activeDownloadCount: Int get() = records.count { it.status.isActive }
    val selectedVariant: DeliveryVariant?
        get() = variants.firstOrNull { it.id == selectedVariantId }
}
