/*
 * The resumable transfer flow is based on Aurora Store's DownloadWorker.
 * SPDX-FileCopyrightText: 2025 Aurora OSS
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import com.android.apksig.ApkVerifier
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    val currentFile: String = ""
)

class DownloadEngine(
    private val cacheRoot: Path,
    private val http: CliHttpClient,
    parallelism: Int = DEFAULT_PARALLELISM,
    private val cachedArtifact: (Artifact) -> Path? = { null }
) {
    private val concurrency = parallelism.coerceIn(1, MAX_PARALLELISM)
    private val cancelled = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun cancel() {
        cancelled.set(true)
        activeCalls.forEach(Call::cancel)
    }

    suspend fun download(
        plan: DownloadPlan,
        outputDirectory: Path,
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        cancelled.set(false)
        require(plan.uniqueArtifacts.isNotEmpty()) { "The delivery plan contains no APK files" }
        val jobRoot = safeJobDirectory(plan)
        val local = plan.uniqueArtifacts.map { artifact ->
            val cached = cachedArtifact(artifact)?.takeIf { validTransfer(it, artifact) }
            LocalArtifact(
                artifact = artifact,
                path = cached ?: jobRoot.resolve("${sha256(artifact.identity() ?: artifact.relativePath)}.apk"),
                externallyCached = cached != null
            )
        }
        val initialBytes = local.associate { item ->
            item.key to when {
                validTransfer(item.path, item.artifact) -> Files.size(item.path)
                item.externallyCached -> 0L
                Files.isRegularFile(item.partial) -> Files.size(item.partial)
                else -> 0L
            }
        }
        val initiallyComplete = local.count { validTransfer(it.path, it.artifact) }
        val tracker = ProgressTracker(plan, initialBytes, initiallyComplete, onProgress)
        tracker.emit()

        try {
            coroutineScope {
                val semaphore = Semaphore(concurrency)
                local.filterNot { validTransfer(it.path, it.artifact) }.map { item ->
                    async {
                        semaphore.withPermit {
                            checkActive()
                            downloadWithRetry(item, tracker)
                            tracker.completed(item.key, item.path)
                        }
                    }
                }.awaitAll()
            }
            checkActive()
            val verified = verifyArtifacts(local)
            checkActive()
            val output = export(plan, verified, outputDirectory)
            DownloadResult(
                path = output,
                apkCount = verified.size,
                bytes = Files.size(output),
                integrityVerified = verified.all { it.referenceVerified },
                signaturesVerified = true
            )
        } finally {
            activeCalls.forEach(Call::cancel)
            activeCalls.clear()
        }
    }

    private suspend fun downloadWithRetry(item: LocalArtifact, tracker: ProgressTracker) {
        var last: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            checkActive()
            try {
                downloadOne(item, tracker)
                return
            } catch (error: Exception) {
                if (error is DownloadCancelledException) throw error
                last = error
                if (!retryable(error) || attempt + 1 >= MAX_ATTEMPTS) throw error
                delay(600L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }

    private fun downloadOne(item: LocalArtifact, tracker: ProgressTracker) {
        require(DeliveryUrlPolicy.isAllowed(item.artifact.url)) {
            "Download URL is outside approved Google delivery hosts"
        }
        Files.createDirectories(item.path.parent)
        var offset = sizeOrZero(item.partial)
        if (item.artifact.size > 0 && offset > item.artifact.size) {
            Files.deleteIfExists(item.partial)
            offset = 0
            tracker.update(item.key, 0, item.artifact.name)
        }

        val request = Request.Builder().url(item.artifact.url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.get().build()
        val call = http.downloadClient.newCall(request)
        activeCalls += call
        try {
            call.execute().use { response ->
                checkActive()
                var append = false
                if (offset > 0) {
                    when (CliResumePolicy.decide(offset, item.artifact.size, response.code, response.header("Content-Range"))) {
                        CliResumePolicy.Decision.APPEND -> append = true
                        CliResumePolicy.Decision.COMPLETE -> {
                            require(validTransfer(item.partial, item.artifact)) {
                                "Server reported a complete range but the partial APK is invalid"
                            }
                            moveIntoPlace(item.partial, item.path)
                            tracker.update(item.key, Files.size(item.path), item.artifact.name)
                            return
                        }
                        CliResumePolicy.Decision.RESTART -> {
                            offset = 0
                            tracker.update(item.key, 0, item.artifact.name)
                        }
                        CliResumePolicy.Decision.REJECT -> throw PermanentDownloadException(
                            "The server could not safely resume ${item.artifact.safeName} (HTTP ${response.code})"
                        )
                    }
                }
                if (offset == 0L && !response.isSuccessful) throw HttpDownloadException(response.code)

                var received = offset
                FileOutputStream(item.partial.toFile(), append).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (item.artifact.size > 0 && received > item.artifact.size) {
                                throw PermanentDownloadException(
                                    "Google Play returned too many bytes for ${item.artifact.safeName}"
                                )
                            }
                            output.write(buffer, 0, count)
                            tracker.update(item.key, received, item.artifact.name)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (item.artifact.size > 0 && Files.size(item.partial) != item.artifact.size) {
                throw IOException(
                    "Size mismatch for ${item.artifact.safeName}: " +
                        "${Files.size(item.partial)} of ${item.artifact.size} bytes"
                )
            }
            if (!validTransfer(item.partial, item.artifact)) {
                Files.deleteIfExists(item.partial)
                tracker.update(item.key, 0, item.artifact.name)
                throw IOException("Integrity verification failed for ${item.artifact.safeName}")
            }
            moveIntoPlace(item.partial, item.path)
            tracker.update(item.key, Files.size(item.path), item.artifact.name)
        } finally {
            activeCalls -= call
        }
    }

    private fun verifyArtifacts(items: List<LocalArtifact>): List<VerifiedArtifact> {
        val signerSets = mutableMapOf<String, Set<String>>()
        return items.map { item ->
            checkActive()
            require(Files.isRegularFile(item.path)) { "Downloaded APK is missing: ${item.artifact.safeName}" }
            require(verifyReference(item.path, item.artifact)) {
                "APK transfer verification failed for ${item.artifact.safeName}"
            }
            val referenceVerified = item.artifact.sha256.isNotBlank() || item.artifact.sha1.isNotBlank()
            val identity = ApkIntrospection.manifest(item.path.toFile())
            require(identity.packageName == item.artifact.ownerPackage) {
                "APK package mismatch for ${item.artifact.safeName}"
            }
            require(identity.versionCode == item.artifact.ownerVersionCode) {
                "APK version mismatch for ${item.artifact.safeName}"
            }
            val expectedSplit = item.artifact.name.removeSuffix(".apk")
            require(
                when (item.artifact.type) {
                    "BASE" -> identity.splitName == null
                    "SPLIT" -> identity.splitName == expectedSplit
                    else -> false
                }
            ) { "APK split identity mismatch for ${item.artifact.safeName}" }

            val platform = item.artifact.profile.sdkVersion.coerceAtLeast(identity.minSdk)
            val signature = ApkVerifier.Builder(item.path.toFile())
                .setMinCheckedPlatformVersion(platform)
                .setMaxCheckedPlatformVersion(platform)
                .build()
                .verify()
            require(signature.isVerified) {
                "APK signature verification failed for ${item.artifact.safeName}"
            }
            val signerHashes = signature.signerCertificates
                .map(::certificateFingerprint)
                .toSet()
            require(signerHashes.isNotEmpty()) { "APK has no verified signer certificate" }
            val previous = signerSets.putIfAbsent(item.artifact.ownerPackage, signerHashes)
            require(previous == null || previous == signerHashes) {
                "APK signer mismatch within ${item.artifact.ownerPackage}"
            }
            VerifiedArtifact(
                local = item,
                sha256 = ArtifactCache.digest(item.path),
                referenceVerified = referenceVerified
            )
        }
    }

    private fun export(
        plan: DownloadPlan,
        artifacts: List<VerifiedArtifact>,
        outputDirectory: Path
    ): Path {
        Files.createDirectories(outputDirectory)
        require(Files.isDirectory(outputDirectory) && Files.isWritable(outputDirectory)) {
            "Output directory is not writable: $outputDirectory"
        }
        val extension = if (plan.isSingleApk) "apk" else "apks"
        val target = uniqueTarget(outputDirectory, "${outputBaseName(plan)}.$extension")
        val partial = outputDirectory.resolve(".${target.fileName}.${UUID.randomUUID()}.partial")
        try {
            if (plan.isSingleApk) {
                Files.newInputStream(artifacts.single().local.path).use { input ->
                    FileOutputStream(partial.toFile()).use { output ->
                        copy(input, output)
                        output.fd.sync()
                    }
                }
                require(ArtifactCache.digest(partial) == artifacts.single().sha256) {
                    "Final APK verification failed"
                }
            } else {
                val entries = archiveEntries(artifacts)
                FileOutputStream(partial.toFile()).use { fileOutput ->
                    val buffered = BufferedOutputStream(fileOutput, NETWORK_BUFFER_BYTES)
                    val zip = ZipOutputStream(buffered).apply { setLevel(Deflater.NO_COMPRESSION) }
                    entries.forEach { entry ->
                        checkActive()
                        val zipEntry = ZipEntry(entry.name).apply { time = 0L }
                        zip.putNextEntry(zipEntry)
                        Files.newInputStream(entry.artifact.local.path).use { copy(it, zip) }
                        zip.closeEntry()
                    }
                    zip.finish()
                    buffered.flush()
                    fileOutput.fd.sync()
                    zip.close()
                }
                verifyArchive(partial, entries)
            }
            moveIntoPlace(partial, target)
            return target
        } catch (error: Exception) {
            Files.deleteIfExists(partial)
            throw error
        }
    }

    private fun verifyArchive(path: Path, expected: List<ArchiveEntry>) {
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size == expected.size) { "Final .apks file count is incorrect" }
            require(entries.all { !it.isDirectory && it.name.endsWith(".apk", true) && '/' !in it.name }) {
                "Final .apks contains a non-APK or nested entry"
            }
            require(entries.map { it.name }.distinct().size == entries.size) {
                "Final .apks contains duplicate names"
            }
            val byName = expected.associateBy(ArchiveEntry::name)
            entries.forEach { entry ->
                val expectedEntry = requireNotNull(byName[entry.name]) {
                    "Final .apks contains an unexpected entry"
                }
                val digest = zip.getInputStream(entry).use { digest(it, "SHA-256") }
                require(digest == expectedEntry.artifact.sha256) {
                    "Final .apks verification failed for ${entry.name}"
                }
            }
        }
    }

    private fun archiveEntries(artifacts: List<VerifiedArtifact>): List<ArchiveEntry> {
        val baseNames = artifacts.associateWith { safeArchiveName(it.local.artifact.safeName) }
        val counts = baseNames.values.groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
        val used = mutableSetOf<String>()
        return artifacts.map { artifact ->
            val plan = artifact.local.artifact
            val base = baseNames.getValue(artifact)
            val prefix = plan.dependency || counts.getValue(base.lowercase(Locale.ROOT)) > 1
            val stem = if (prefix) {
                listOf(
                    plan.ownerPackage.takeIf { plan.dependency }.orEmpty(),
                    plan.profile.abi.label,
                    "${plan.profile.densityDpi}dpi",
                    "api${plan.profile.sdkVersion}",
                    base
                ).filter(String::isNotBlank).joinToString("_")
            } else {
                base
            }
            var candidate = safeArchiveName(stem)
            var suffix = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                candidate = safeArchiveName(stem.removeSuffix(".apk") + "_$suffix.apk")
                suffix += 1
            }
            ArchiveEntry(candidate, artifact)
        }
    }

    private fun outputBaseName(plan: DownloadPlan): String {
        val abis = plan.profiles.map { it.abi.label }.distinct()
        val densities = plan.profiles.map(DeliveryProfile::densityDpi).distinct()
        val abiTag = when {
            plan.architectureMode == ArchitectureMode.UNIVERSAL -> "universal"
            abis.size > 1 -> abis.joinToString("-")
            else -> abis.singleOrNull().orEmpty()
        }
        val densityTag = densities.singleOrNull()?.let { "${it}dpi" }.orEmpty()
        return listOf(plan.packageName, "v${plan.versionCode}", abiTag, densityTag)
            .filter(String::isNotBlank)
            .joinToString("_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(220)
    }

    private fun safeArchiveName(value: String): String {
        val clean = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(220)
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "artifact.apk"
        return if (clean.endsWith(".apk", true)) clean else "$clean.apk"
    }

    private fun uniqueTarget(directory: Path, requested: String): Path {
        var target = directory.resolve(requested)
        var suffix = 2
        val extension = requested.substringAfterLast('.', "")
        val stem = requested.removeSuffix(".$extension")
        while (Files.exists(target)) {
            target = directory.resolve("${stem}_$suffix.$extension")
            suffix += 1
        }
        return target
    }

    private fun safeJobDirectory(plan: DownloadPlan): Path {
        val root = cacheRoot.resolve("jobs").toAbsolutePath().normalize()
        Files.createDirectories(root)
        val job = root.resolve(plan.fingerprint).normalize()
        require(job.startsWith(root) && job != root) { "Invalid download cache directory" }
        Files.createDirectories(job)
        return job
    }

    private fun validTransfer(path: Path, artifact: Artifact): Boolean {
        if (!Files.isRegularFile(path)) return false
        if (artifact.size > 0 && Files.size(path) != artifact.size) return false
        return verifyReference(path, artifact)
    }

    private fun verifyReference(path: Path, artifact: Artifact): Boolean = when {
        artifact.sha256.isNotBlank() -> ArtifactCache.digest(path, "SHA-256")
            .equals(artifact.sha256, true)
        artifact.sha1.isNotBlank() -> ArtifactCache.digest(path, "SHA-1")
            .equals(artifact.sha1, true)
        else -> artifact.size <= 0 || Files.size(path) == artifact.size
    }

    private fun retryable(error: Exception): Boolean = when (error) {
        is PermanentDownloadException -> false
        is HttpDownloadException -> error.status in RETRYABLE_HTTP_CODES
        else -> error is IOException
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun checkActive() {
        if (cancelled.get()) throw DownloadCancelledException()
    }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    private fun digest(input: InputStream, algorithm: String): String {
        val value = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            value.update(buffer, 0, count)
        }
        return value.digest().joinToString("") { "%02x".format(it) }
    }

    private data class LocalArtifact(
        val artifact: Artifact,
        val path: Path,
        val externallyCached: Boolean
    ) {
        val key: String get() = artifact.identity() ?: artifact.relativePath
        val partial: Path get() = path.resolveSibling("${path.fileName}.part")
    }

    private data class VerifiedArtifact(
        val local: LocalArtifact,
        val sha256: String,
        val referenceVerified: Boolean
    )

    private data class ArchiveEntry(val name: String, val artifact: VerifiedArtifact)

    private class ProgressTracker(
        private val plan: DownloadPlan,
        initial: Map<String, Long>,
        completedFiles: Int,
        private val callback: (DownloadProgress) -> Unit
    ) {
        private val bytes = initial.toMutableMap()
        private val completed = AtomicInteger(completedFiles)

        @Synchronized
        fun emit() = callback(snapshot())

        @Synchronized
        fun update(key: String, value: Long, current: String) {
            bytes[key] = value.coerceAtLeast(0)
            callback(snapshot(current))
        }

        @Synchronized
        fun completed(key: String, path: Path) {
            bytes[key] = Files.size(path)
            completed.incrementAndGet()
            callback(snapshot())
        }

        private fun snapshot(current: String = "") = DownloadProgress(
            downloadedBytes = bytes.values.sum(),
            totalBytes = plan.totalBytes,
            completedFiles = completed.get(),
            totalFiles = plan.uniqueArtifacts.size,
            currentFile = current
        )
    }

    class DownloadCancelledException : IOException("Download cancelled; partial files were kept")
    private class PermanentDownloadException(message: String) : IOException(message)
    private class HttpDownloadException(val status: Int) : IOException("Download failed (HTTP $status)")

    companion object {
        const val DEFAULT_PARALLELISM = 4
        const val MAX_PARALLELISM = 8
        private const val MAX_ATTEMPTS = 3
        private const val NETWORK_BUFFER_BYTES = 256 * 1024
        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}

private fun sizeOrZero(path: Path): Long = if (Files.isRegularFile(path)) Files.size(path) else 0L

internal object CliResumePolicy {
    enum class Decision { APPEND, COMPLETE, RESTART, REJECT }

    fun decide(offset: Long, expectedSize: Long, status: Int, contentRange: String?): Decision = when {
        status == 206 && contentRangeStart(contentRange) == offset -> Decision.APPEND
        status == 200 -> Decision.RESTART
        status == 416 && expectedSize > 0 && offset == expectedSize -> Decision.COMPLETE
        else -> Decision.REJECT
    }

    private fun contentRangeStart(value: String?): Long? {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty().trim()) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
}
