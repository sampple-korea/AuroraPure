/*
 * The resumable transfer flow is based on Aurora Store's DownloadWorker.
 * SPDX-FileCopyrightText: 2025 Aurora OSS
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.android.apksig.ApkVerifier
import com.aurora.pure.data.ArtifactPlan
import com.aurora.pure.data.DownloadOutcome
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.data.DownloadedArtifact
import com.aurora.pure.data.FileVerification
import com.aurora.pure.data.VerificationReport
import com.aurora.pure.data.VerificationState
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.network.PureHttpClient
import com.aurora.pure.storage.RecordRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

class DownloadCoordinator(
    private val context: Context,
    private val httpClient: PureHttpClient,
    private val records: RecordRepository
) {
    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeCall: Call? = null

    fun pause() {
        pauseRequested = true
        activeCall?.cancel()
    }

    fun cancel() {
        cancelRequested = true
        activeCall?.cancel()
    }

    suspend fun execute(
        plan: DownloadPlan,
        onProgress: (downloadedBytes: Long, completedFiles: Int) -> Unit,
        onVerifying: () -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        pauseRequested = false
        cancelRequested = false
        val taskRoot = records.taskDirectory(plan.id).apply { mkdirs() }
        require(taskRoot.canonicalPath.startsWith(records.jobRoot().apply { mkdirs() }.canonicalPath)) {
            "Invalid task directory"
        }

        val localArtifacts = plan.artifacts.map { artifact ->
            DownloadedArtifact(artifact, localFile(taskRoot, artifact))
        }
        localArtifacts.forEach { item ->
            if (!item.file.exists() && item.plan.type == "BASE") {
                if (RemoteApkSplitReader.seedCachedBase(context, item.plan, item.file)) {
                    partFile(item.file).delete()
                }
            }
        }
        val presentBytes = localArtifacts.sumOf { item ->
            maxOf(item.file.takeIf(File::exists)?.length() ?: 0L, partFile(item.file).takeIf(File::exists)?.length() ?: 0L)
        }
        val remainingBytes = (plan.totalBytes - presentBytes).coerceAtLeast(0)
        require(taskRoot.usableSpace >= remainingBytes + MINIMUM_FREE_BYTES) {
            "Not enough temporary storage for this download"
        }

        val reusable = localArtifacts.associateWith(::isReusableCompletedFile)
        val completedSources = mutableMapOf<String, DownloadedArtifact>()
        reusable.filterValues { it }.keys.forEach { item ->
            artifactIdentity(item.plan)?.let { completedSources.putIfAbsent(it, item) }
        }
        var downloadedBytes = localArtifacts.sumOf { item ->
            when {
                reusable[item] == true -> item.file.length()
                partFile(item.file).exists() -> partFile(item.file).length()
                else -> 0L
            }
        }
        var completedFiles = reusable.count { it.value }
        onProgress(downloadedBytes, completedFiles)

        try {
            localArtifacts.forEach { item ->
                checkControlState()
                if (reusable[item] != true) {
                    if (item.file.exists()) item.file.delete()
                    val matching = artifactIdentity(item.plan)?.let(completedSources::get)
                        ?.takeIf(::isReusableCompletedFile)
                    if (matching != null) {
                        partFile(item.file).delete()
                        copyCompletedArtifact(matching.file, item.file)
                        downloadedBytes = localArtifacts.sumOf { local ->
                            when {
                                local.file.exists() -> local.file.length()
                                partFile(local.file).exists() -> partFile(local.file).length()
                                else -> 0L
                            }
                        }
                    } else {
                        for (attempt in 0 until MAX_ATTEMPTS) {
                            try {
                                val diskBytes = localArtifacts.sumOf { local ->
                                    when {
                                        local.file.exists() -> local.file.length()
                                        partFile(local.file).exists() -> partFile(local.file).length()
                                        else -> 0L
                                    }
                                }
                                downloadedBytes = downloadOne(item, diskBytes, completedFiles, onProgress)
                                break
                            } catch (exception: IOException) {
                                checkControlState()
                                if (!isRetryable(exception) || attempt + 1 >= MAX_ATTEMPTS) {
                                    throw exception
                                }
                                delay(750L * (attempt + 1))
                            }
                        }
                    }
                    artifactIdentity(item.plan)?.let { completedSources.putIfAbsent(it, item) }
                    completedFiles += 1
                    onProgress(downloadedBytes, completedFiles)
                }
            }

            checkControlState()
            onVerifying()
            val report = verify(plan, localArtifacts)
            require(report.isExportable) { "Downloaded APK verification failed: ${report.summary()}" }
            return@withContext DownloadOutcome(plan, localArtifacts, report)
        } finally {
            activeCall = null
            if (cancelRequested) records.deleteTaskFiles(plan.id)
        }
    }

    private suspend fun downloadOne(
        item: DownloadedArtifact,
        alreadyDownloaded: Long,
        completedFiles: Int,
        onProgress: (Long, Int) -> Unit
    ): Long {
        item.file.parentFile?.mkdirs()
        require(DeliveryUrlPolicy.isAllowed(item.plan.url)) {
            "Download URL is outside the approved Google delivery hosts"
        }
        val part = partFile(item.file)
        var offset = part.takeIf(File::exists)?.length() ?: 0L
        if (item.plan.size > 0 && offset > item.plan.size) {
            part.delete()
            offset = 0L
        }

        val request = Request.Builder().url(item.plan.url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = httpClient.downloadClient.newCall(request)
        activeCall = call
        val response = call.execute()

        response.use {
            checkControlState()
            var append = false
            if (offset > 0) {
                when (ResumePolicy.decide(offset, it.code, it.header("Content-Range"))) {
                    ResumePolicy.Decision.APPEND -> append = true
                    ResumePolicy.Decision.RESTART -> offset = 0L
                    ResumePolicy.Decision.REJECT -> {
                        throw PermanentDownloadException(
                            "Server rejected a safe resume (HTTP ${it.code})"
                        )
                    }
                }
            }
            if (offset == 0L && !it.isSuccessful) {
                throw HttpDownloadException(it.code)
            }

            var total = alreadyDownloaded - part.length() + offset
            FileOutputStream(part, append).use { output ->
                it.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkControlState()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        onProgress(total, completedFiles)
                    }
                    output.fd.sync()
                }
            }
            if (item.plan.size > 0 && part.length() != item.plan.size) {
                throw IOException(
                    "Size mismatch for ${item.plan.name}: ${part.length()} of ${item.plan.size} bytes"
                )
            }
            if (!part.renameTo(item.file)) {
                throw PermanentDownloadException("Could not finalize ${item.plan.name}")
            }
            return total
        }
    }

    private fun verify(
        plan: DownloadPlan,
        artifacts: List<DownloadedArtifact>
    ): VerificationReport {
        val fileReports = mutableListOf<FileVerification>()
        val signerSets = mutableMapOf<String, Set<String>>()
        var integrity = VerificationState.VERIFIED
        var signatures = VerificationState.VERIFIED
        var packages = VerificationState.VERIFIED
        val limitations = mutableSetOf<String>()

        artifacts.forEach { item ->
            checkControlState()
            val localSha256 = digest(item.file, "SHA-256")
            val hashState = when {
                item.plan.sha256.isNotBlank() -> if (
                    localSha256.equals(item.plan.sha256, ignoreCase = true)
                ) VerificationState.VERIFIED else VerificationState.FAILED

                item.plan.sha1.isNotBlank() -> if (
                    digest(item.file, "SHA-1").equals(item.plan.sha1, ignoreCase = true)
                ) VerificationState.VERIFIED else VerificationState.FAILED

                else -> VerificationState.UNAVAILABLE
            }
            if (hashState == VerificationState.FAILED) integrity = VerificationState.FAILED
            if (hashState == VerificationState.UNAVAILABLE && integrity != VerificationState.FAILED) {
                integrity = VerificationState.UNAVAILABLE
                limitations += "Google Play supplied no reference hash for one or more APKs"
            }

            // The delivery plan is resolved for this device. Requiring an APK
            // to verify on older Android releases can reject a valid modern
            // v2/v3-only APK merely because it has no legacy v1 signature.
            val currentPlatformApi = Build.VERSION.SDK_INT
            val apkResult = runCatching {
                ApkVerifier.Builder(item.file)
                    .setMinCheckedPlatformVersion(currentPlatformApi)
                    .setMaxCheckedPlatformVersion(currentPlatformApi)
                    .build()
                    .verify()
            }.getOrNull()
            val signatureState = when {
                apkResult == null -> VerificationState.UNAVAILABLE
                apkResult.isVerified -> VerificationState.VERIFIED
                else -> VerificationState.FAILED
            }
            val signerHashes = apkResult?.signerCertificates.orEmpty()
                .map(::certificateFingerprint)
                .sorted()
            if (signatureState == VerificationState.FAILED) signatures = VerificationState.FAILED
            if (signatureState == VerificationState.UNAVAILABLE && signatures != VerificationState.FAILED) {
                signatures = VerificationState.UNAVAILABLE
                limitations += "APK signature verification was unavailable"
            }
            if (signerHashes.isNotEmpty()) {
                val previous = signerSets.putIfAbsent(item.plan.ownerPackage, signerHashes.toSet())
                if (previous != null && previous != signerHashes.toSet()) signatures = VerificationState.FAILED
            }

            val packageInfo = context.packageManager.getPackageArchiveInfo(
                item.file.absolutePath,
                PackageManager.GET_META_DATA
            )
            val manifestIdentity = runCatching { ApkManifestReader.read(item.file) }.getOrNull()
            Log.i(
                TAG,
                "Parsed ${item.plan.type} ${item.plan.name}: " +
                    "manifestPackage=${manifestIdentity?.packageName ?: "<unavailable>"}, " +
                    "manifestVersion=${manifestIdentity?.versionCode ?: -1}, " +
                    "split=${manifestIdentity?.splitName ?: "<base>"}, " +
                    "platformPackage=${packageInfo?.packageName ?: "<unavailable>"}"
            )
            val expectedSplitName = item.plan.name.removeSuffix(".apk")
            val manifestMatches = manifestIdentity != null &&
                manifestIdentity.packageName == item.plan.ownerPackage &&
                manifestIdentity.versionCode == item.plan.ownerVersionCode &&
                when (item.plan.type) {
                    "BASE" -> manifestIdentity.splitName == null
                    "SPLIT" -> manifestIdentity.splitName == expectedSplitName
                    else -> false
                }
            val platformMatches = item.plan.type != "BASE" || (
                packageInfo != null &&
                    packageInfo.packageName == item.plan.ownerPackage &&
                    packageInfo.longVersionCode == item.plan.ownerVersionCode
                )
            val packageState = if (manifestMatches && platformMatches) {
                VerificationState.VERIFIED
            } else {
                VerificationState.FAILED
            }
            if (packageState == VerificationState.FAILED) packages = VerificationState.FAILED

            fileReports += FileVerification(
                relativePath = item.plan.relativePath,
                variant = item.plan.variant,
                ownerPackage = item.plan.ownerPackage,
                size = item.file.length(),
                sha256 = localSha256,
                integrity = hashState,
                signature = signatureState,
                signerSha256 = signerHashes
            )
        }

        if (plan.hasAdditionalData) limitations += "The delivery references non-APK data"
        checkControlState()
        return VerificationReport(fileReports, integrity, signatures, packages, limitations.toList())
    }

    private fun isReusableCompletedFile(item: DownloadedArtifact): Boolean {
        if (!item.file.exists()) return false
        if (item.plan.size > 0 && item.file.length() != item.plan.size) return false
        return when {
            item.plan.sha256.isNotBlank() -> digest(item.file, "SHA-256")
                .equals(item.plan.sha256, ignoreCase = true)
            item.plan.sha1.isNotBlank() -> digest(item.file, "SHA-1")
                .equals(item.plan.sha1, ignoreCase = true)
            else -> false
        }
    }

    private fun artifactIdentity(artifact: ArtifactPlan): String? {
        val referenceHash = when {
            artifact.sha256.isNotBlank() -> "sha256:${artifact.sha256.lowercase()}"
            artifact.sha1.isNotBlank() -> "sha1:${artifact.sha1.lowercase()}"
            else -> return null
        }
        return listOf(
            artifact.ownerPackage,
            artifact.ownerVersionCode.toString(),
            artifact.name,
            artifact.type,
            artifact.size.toString(),
            referenceHash
        ).joinToString("|")
    }

    private fun copyCompletedArtifact(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.absolutePath + ".copy")
        partial.delete()
        try {
            source.inputStream().use { input ->
                FileOutputStream(partial).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            require(partial.renameTo(destination)) { "Could not finalize ${destination.name}" }
        } catch (exception: Exception) {
            partial.delete()
            throw exception
        }
    }

    private fun localFile(taskRoot: File, artifact: ArtifactPlan): File {
        val relative = artifact.relativePath.split('/').joinToString(File.separator) { safeSegment(it) }
        val file = taskRoot.resolve(relative)
        require(file.canonicalPath.startsWith(taskRoot.canonicalPath + File.separator)) {
            "Unsafe APK file name"
        }
        return file
    }

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun partFile(file: File) = File(file.absolutePath + ".part")

    private fun isRetryable(exception: IOException): Boolean = when (exception) {
        is PermanentDownloadException -> false
        is HttpDownloadException -> exception.status in RETRYABLE_HTTP_CODES
        is FileNotFoundException -> false
        else -> generateSequence<Throwable>(exception) { it.cause }
            .none { cause ->
                cause is android.system.ErrnoException && cause.errno in setOf(
                    android.system.OsConstants.ENOSPC,
                    android.system.OsConstants.EACCES,
                    android.system.OsConstants.EROFS
                )
            }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    private fun checkControlState() {
        if (cancelRequested) throw CancelRequestedException()
        if (pauseRequested) throw PauseRequestedException()
    }

    class PauseRequestedException : IOException("Download paused")
    class CancelRequestedException : IOException("Download cancelled")
    private class PermanentDownloadException(message: String) : IOException(message)
    private class HttpDownloadException(val status: Int) :
        IOException("Download failed (HTTP $status)")

    companion object {
        private const val TAG = "AuroraPure"
        private const val MAX_ATTEMPTS = 3
        private const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        private val RETRYABLE_HTTP_CODES = setOf(408, 500, 502, 503, 504)
    }
}
