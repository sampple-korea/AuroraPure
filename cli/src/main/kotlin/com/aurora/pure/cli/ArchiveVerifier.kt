/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import com.android.apksig.ApkVerifier
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.zip.ZipFile

data class VerifiedInput(
    val kind: String,
    val apkCount: Int,
    val packages: Map<String, List<Long>>,
    val sha256: String
)

object ArchiveVerifier {
    fun verify(path: Path): VerifiedInput {
        require(Files.isRegularFile(path) && Files.isReadable(path)) { "File is not readable: $path" }
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "apk" -> verifyApks(path, listOf(path), "apk")
            "apks" -> verifyArchive(path)
            else -> throw IllegalArgumentException("Expected an .apk or .apks file")
        }
    }

    private fun verifyArchive(path: Path): VerifiedInput {
        val temporary = Files.createTempDirectory("aurora-pure-verify-")
        return try {
            val extracted = ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.isNotEmpty() && entries.size <= MAX_APK_ENTRIES) {
                    "The .apks entry count is invalid"
                }
                require(entries.all { entry ->
                    !entry.isDirectory &&
                        entry.name.endsWith(".apk", true) &&
                        '/' !in entry.name && '\\' !in entry.name &&
                        entry.name !in setOf(".", "..")
                }) { "The .apks archive must contain only root-level APK files" }
                require(entries.map { it.name.lowercase(Locale.ROOT) }.distinct().size == entries.size) {
                    "The .apks archive contains duplicate APK names"
                }
                var total = 0L
                entries.mapIndexed { index, entry ->
                    require(entry.size in 1..MAX_SINGLE_APK_BYTES) { "An APK entry has an invalid size" }
                    total += entry.size
                    require(total <= MAX_TOTAL_BYTES) { "The .apks archive is too large to verify safely" }
                    val target = temporary.resolve("$index.apk")
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(target).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var written = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= entry.size && written <= MAX_SINGLE_APK_BYTES) {
                                    "An APK entry expanded beyond its declared size"
                                }
                                output.write(buffer, 0, count)
                            }
                            require(written == entry.size) { "An APK entry was truncated" }
                        }
                    }
                    target
                }
            }
            verifyApks(path, extracted, "apks")
        } finally {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun verifyApks(source: Path, apks: List<Path>, kind: String): VerifiedInput {
        val identities = mutableListOf<ApkIdentity>()
        val signers = mutableMapOf<String, Set<String>>()
        apks.forEach { apk ->
            val identity = ApkIntrospection.manifest(apk.toFile())
            val checkedApi = maxOf(identity.minSdk, PlayGateway.MIN_ANDROID_API)
            val result = ApkVerifier.Builder(apk.toFile())
                .setMinCheckedPlatformVersion(checkedApi)
                .setMaxCheckedPlatformVersion(PlayGateway.CURRENT_ANDROID_API)
                .build()
                .verify()
            require(result.isVerified) { "APK signature verification failed" }
            val signerHashes = result.signerCertificates.map(::fingerprint).toSet()
            require(signerHashes.isNotEmpty()) { "APK has no verified signer certificate" }
            val previous = signers.putIfAbsent(identity.packageName, signerHashes)
            require(previous == null || previous == signerHashes) {
                "APK signer mismatch within ${identity.packageName}"
            }
            identities += identity
        }
        identities.groupBy(ApkIdentity::packageName).forEach { (packageName, matching) ->
            require(matching.any { it.splitName == null }) { "$packageName has splits but no base APK" }
        }
        return VerifiedInput(
            kind = kind,
            apkCount = apks.size,
            packages = identities.groupBy(ApkIdentity::packageName)
                .mapValues { (_, matching) -> matching.map(ApkIdentity::versionCode).distinct().sorted() },
            sha256 = ArtifactCache.digest(source)
        )
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    private const val MAX_APK_ENTRIES = 4096
    private const val MAX_SINGLE_APK_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024
}
