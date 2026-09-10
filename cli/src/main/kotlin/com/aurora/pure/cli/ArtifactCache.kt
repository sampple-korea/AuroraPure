/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request

class ArtifactCache(
    private val root: Path,
    private val http: CliHttpClient
) {
    private val artifactLocks = ConcurrentHashMap<Path, Any>()

    /**
     * A complete scan asks for the same base APK once per probe, and each ask used to re-hash the
     * whole cached file. Once this process has verified a file against a content-addressed identity,
     * re-verifying it costs a full read of tens of megabytes and proves nothing new, so the size is
     * rechecked and the digest is not.
     */
    private val verified = ConcurrentHashMap<Path, VerifiedEntry>()

    private data class VerifiedEntry(val identity: String, val size: Long)

    init {
        Files.createDirectories(root)
    }

    fun ensure(artifact: Artifact): Path {
        val target = pathFor(artifact)
        val lock = artifactLocks.computeIfAbsent(target.toAbsolutePath().normalize()) { Any() }
        return synchronized(lock) {
            if (isAlreadyVerified(target, artifact)) return@synchronized target
            if (isValid(target, artifact)) {
                rememberVerified(target, artifact)
                return@synchronized target
            }
            require(DeliveryUrlPolicy.isAllowed(artifact.url)) {
                "Download URL is outside approved Google delivery hosts"
            }
            Files.createDirectories(target.parent)
            val partial = target.resolveSibling("${target.fileName}.partial")
            Files.deleteIfExists(partial)
            try {
                http.downloadClient.newCall(Request.Builder().url(artifact.url).get().build())
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Download failed (HTTP ${response.code})")
                        }
                        FileOutputStream(partial.toFile()).use { output ->
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(artifact.size <= 0 || total <= artifact.size) {
                                        "Google Play returned too many APK bytes"
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                    }
                require(isValid(partial, artifact)) { "Cached base APK failed integrity verification" }
                moveIntoPlace(partial, target)
                rememberVerified(target, artifact)
                target
            } catch (error: Exception) {
                Files.deleteIfExists(partial)
                throw error
            }
        }
    }

    fun find(artifact: Artifact): Path? = pathFor(artifact).takeIf {
        isAlreadyVerified(it, artifact) || isValid(it, artifact)
    }

    /** Only a content-addressed identity can stand in for a fresh digest. */
    private fun identityOf(artifact: Artifact): String? = artifact.identity()

    private fun isAlreadyVerified(path: Path, artifact: Artifact): Boolean {
        val identity = identityOf(artifact) ?: return false
        val entry = verified[path.toAbsolutePath().normalize()] ?: return false
        if (entry.identity != identity) return false
        return runCatching { Files.isRegularFile(path) && Files.size(path) == entry.size }
            .getOrDefault(false)
    }

    private fun rememberVerified(path: Path, artifact: Artifact) {
        val identity = identityOf(artifact) ?: return
        runCatching {
            verified[path.toAbsolutePath().normalize()] = VerifiedEntry(identity, Files.size(path))
        }
    }

    private fun pathFor(artifact: Artifact): Path {
        val identity = artifact.identity() ?: listOf(
            artifact.ownerPackage,
            artifact.ownerVersionCode,
            artifact.size,
            artifact.name
        ).joinToString("|")
        return root.resolve("${sha256(identity)}.apk")
    }

    private fun isValid(path: Path, artifact: Artifact): Boolean {
        if (!Files.isRegularFile(path)) return false
        if (artifact.size > 0 && Files.size(path) != artifact.size) return false
        val expected = when {
            artifact.sha256.isNotBlank() -> "SHA-256" to artifact.sha256
            artifact.sha1.isNotBlank() -> "SHA-1" to artifact.sha1
            else -> return false
        }
        return digest(path, expected.first).equals(expected.second, true)
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun digest(path: Path, algorithm: String = "SHA-256"): String {
            val digest = MessageDigest.getInstance(algorithm)
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
