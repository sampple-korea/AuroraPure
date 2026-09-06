/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import android.content.Context
import com.aurora.pure.data.ArtifactPlan
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.network.PureHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Fetches only the ZIP directory and split metadata ranges from a base APK. If a delivery host
 * does not support byte ranges, it safely falls back to a verified cache file.
 */
class RemoteApkSplitReader(
    private val context: Context,
    private val httpClient: PureHttpClient
) {
    suspend fun read(base: ArtifactPlan): List<LanguageSplit> = withContext(Dispatchers.IO) {
        require(base.type == "BASE" && base.size > MIN_ZIP_BYTES) {
            "Google Play returned invalid base APK metadata"
        }
        require(DeliveryUrlPolicy.isAllowed(base.url)) {
            "Download URL is outside the approved Google delivery hosts"
        }

        try {
            val directory = readDirectory(base)
            val documents = directory.entries
                .filter { SPLIT_MANIFEST.matches(it.name) }
                .sortedBy(RemoteZipEntry::name)
                .map { entry -> readEntry(base, entry) }
            SplitManifestReader.readDocuments(documents)
        } catch (exception: RangeUnavailableException) {
            SplitManifestReader.read(downloadAndCache(base))
        }
    }

    private suspend fun readDirectory(base: ArtifactPlan): RemoteDirectory {
        val tailLength = minOf(base.size, MAX_EOCD_BYTES.toLong()).toInt()
        val tailStart = base.size - tailLength
        val tail = readRange(base, tailStart, base.size - 1)
        val eocd = ZipRangeIndex.findDirectory(tail, tailStart, base.size)
        require(eocd.size in 0..MAX_DIRECTORY_BYTES.toLong()) {
            "APK ZIP directory is too large"
        }
        val bytes = if (
            eocd.offset >= tailStart && eocd.offset + eocd.size <= base.size
        ) {
            val start = (eocd.offset - tailStart).toInt()
            tail.copyOfRange(start, start + eocd.size.toInt())
        } else if (eocd.size == 0L) {
            byteArrayOf()
        } else {
            readRange(base, eocd.offset, eocd.offset + eocd.size - 1)
        }
        return RemoteDirectory(ZipRangeIndex.readEntries(bytes, eocd.entryCount))
    }

    private suspend fun readEntry(base: ArtifactPlan, entry: RemoteZipEntry): ByteArray {
        require(entry.compressedSize in 0..MAX_DOCUMENT_BYTES.toLong()) {
            "APK split metadata is too large"
        }
        require(entry.uncompressedSize in 1..MAX_DOCUMENT_BYTES.toLong()) {
            "APK split metadata has an invalid size"
        }

        val localHeader = readRange(base, entry.localHeaderOffset, entry.localHeaderOffset + 29)
        require(localHeader.u32(0) == LOCAL_FILE_HEADER_SIGNATURE) {
            "APK ZIP local header is invalid"
        }
        val nameLength = localHeader.u16(26)
        val extraLength = localHeader.u16(28)
        val dataStart = entry.localHeaderOffset + 30L + nameLength + extraLength
        require(dataStart >= 0 && dataStart + entry.compressedSize <= base.size) {
            "APK ZIP entry points outside the file"
        }
        val compressed = if (entry.compressedSize == 0L) {
            byteArrayOf()
        } else {
            readRange(base, dataStart, dataStart + entry.compressedSize - 1)
        }
        val uncompressed = when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATED -> inflate(compressed, entry.uncompressedSize.toInt())
            else -> throw IllegalArgumentException("APK split metadata uses an unsupported compression")
        }
        require(uncompressed.size.toLong() == entry.uncompressedSize) {
            "APK split metadata size does not match its ZIP directory"
        }
        val crc = CRC32().apply { update(uncompressed) }.value
        require(crc == entry.crc32) { "APK split metadata checksum does not match" }
        return uncompressed
    }

    private suspend fun readRange(base: ArtifactPlan, start: Long, end: Long): ByteArray {
        require(start >= 0 && end >= start && end < base.size) { "Invalid APK byte range" }
        val expected = end - start + 1
        require(expected <= MAX_RANGE_BYTES) { "APK metadata range is too large" }
        val request = Request.Builder()
            .url(base.url)
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        val call = httpClient.downloadClient.newCall(request)
        return call.execute().use { response ->
            if (response.code == 200 && start == 0L && end == base.size - 1) {
                return@use response.body.byteStream().use { readExactly(it, expected) }
            }
            if (response.code != 206) throw RangeUnavailableException()
            val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                ?: throw IOException("Google Play returned an invalid APK byte range")
            val actualStart = match.groupValues[1].toLong()
            val actualEnd = match.groupValues[2].toLong()
            val actualTotal = match.groupValues[3].toLong()
            require(actualStart == start && actualEnd == end && actualTotal == base.size) {
                "Google Play returned the wrong APK byte range"
            }
            response.body.byteStream().use { readExactly(it, expected) }
        }
    }

    private suspend fun readExactly(input: java.io.InputStream, expected: Long): ByteArray {
        val output = ByteArrayOutputStream(expected.toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expected) { "Google Play returned too many APK bytes" }
            output.write(buffer, 0, count)
        }
        require(total == expected) { "Google Play returned an incomplete APK byte range" }
        return output.toByteArray()
    }

    private fun inflate(compressed: ByteArray, expected: Int): ByteArray {
        val output = ByteArrayOutputStream(expected)
        InflaterInputStream(ByteArrayInputStream(compressed), Inflater(true)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= expected) { "APK split metadata expands beyond its declared size" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private suspend fun downloadAndCache(base: ArtifactPlan): File {
        val target = cacheFile(context, base)
        if (isValidCachedBase(target, base)) return target
        target.parentFile?.mkdirs()
        val partial = File(target.absolutePath + ".partial")
        partial.delete()
        val request = Request.Builder().url(base.url).get().build()
        try {
            httpClient.downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed (HTTP ${response.code})")
                }
                FileOutputStream(partial).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= base.size) { "Google Play returned too many base APK bytes" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(isValidCachedBase(partial, base)) { "Downloaded base APK metadata did not verify" }
                if (target.exists()) target.delete()
                require(partial.renameTo(target)) { "Could not cache base APK metadata" }
                return target
            }
        } catch (exception: Exception) {
            partial.delete()
            throw exception
        }
    }

    private class RangeUnavailableException : IOException()

    companion object {
        private val SPLIT_MANIFEST = Regex("^res/xml/splits[0-9]+\\.xml$")
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
        private const val MIN_ZIP_BYTES = 22L
        private const val MAX_EOCD_BYTES = 65_557
        private const val MAX_DIRECTORY_BYTES = 16 * 1024 * 1024
        private const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
        private const val MAX_RANGE_BYTES = MAX_DIRECTORY_BYTES.toLong()
        private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        fun seedCachedBase(context: Context, artifact: ArtifactPlan, destination: File): Boolean {
            val cached = cacheFile(context, artifact)
            if (!isValidCachedBase(cached, artifact)) return false
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".seed")
            return runCatching {
                cached.copyTo(partial, overwrite = true)
                if (destination.exists()) destination.delete()
                require(partial.renameTo(destination))
                true
            }.getOrElse {
                partial.delete()
                false
            }
        }

        fun clearCache(context: Context): Boolean {
            val root = cacheRoot(context)
            return !root.exists() || root.deleteRecursively()
        }

        private fun cacheFile(context: Context, artifact: ArtifactPlan): File {
            val identity = listOf(
                artifact.ownerPackage,
                artifact.ownerVersionCode.toString(),
                artifact.size.toString(),
                artifact.sha256,
                artifact.sha1
            ).joinToString("|")
            val key = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return cacheRoot(context).resolve("$key.apk")
        }

        private fun cacheRoot(context: Context) = context.cacheDir.resolve("pure-language-discovery")

        private fun isValidCachedBase(file: File, artifact: ArtifactPlan): Boolean {
            if (!file.isFile || file.length() != artifact.size) return false
            val algorithmAndHash = when {
                artifact.sha256.isNotBlank() -> "SHA-256" to artifact.sha256
                artifact.sha1.isNotBlank() -> "SHA-1" to artifact.sha1
                else -> return false
            }
            val digest = MessageDigest.getInstance(algorithmAndHash.first)
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual.equals(algorithmAndHash.second, ignoreCase = true)
        }
    }
}

internal data class RemoteZipEntry(
    val name: String,
    val method: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long
)

private data class RemoteDirectory(val entries: List<RemoteZipEntry>)

internal object ZipRangeIndex {
    data class DirectoryLocation(val offset: Long, val size: Long, val entryCount: Int)

    fun findDirectory(tail: ByteArray, tailOffset: Long, totalSize: Long): DirectoryLocation {
        require(tailOffset >= 0 && tailOffset + tail.size == totalSize) { "Invalid APK ZIP tail" }
        for (offset in tail.size - MIN_EOCD_SIZE downTo 0) {
            if (tail.u32(offset) != EOCD_SIGNATURE) continue
            val commentLength = tail.u16(offset + 20)
            if (offset + MIN_EOCD_SIZE + commentLength != tail.size) continue
            val disk = tail.u16(offset + 4)
            val directoryDisk = tail.u16(offset + 6)
            val entriesOnDisk = tail.u16(offset + 8)
            val totalEntries = tail.u16(offset + 10)
            val directorySize = tail.u32(offset + 12)
            val directoryOffset = tail.u32(offset + 16)
            require(disk == 0 && directoryDisk == 0 && entriesOnDisk == totalEntries) {
                "Multi-disk APK ZIP files are not supported"
            }
            require(
                totalEntries != ZIP64_U16 &&
                    directorySize != ZIP64_U32 &&
                    directoryOffset != ZIP64_U32
            ) { "ZIP64 APK metadata is not supported" }
            require(directoryOffset + directorySize <= totalSize) {
                "APK ZIP directory points outside the file"
            }
            return DirectoryLocation(directoryOffset, directorySize, totalEntries)
        }
        throw IllegalArgumentException("APK ZIP directory was not found")
    }

    fun readEntries(bytes: ByteArray, expectedCount: Int): List<RemoteZipEntry> {
        val entries = ArrayList<RemoteZipEntry>(expectedCount)
        var offset = 0
        repeat(expectedCount) {
            require(offset + CENTRAL_HEADER_SIZE <= bytes.size) { "APK ZIP directory is truncated" }
            require(bytes.u32(offset) == CENTRAL_HEADER_SIGNATURE) {
                "APK ZIP directory entry is invalid"
            }
            val flags = bytes.u16(offset + 8)
            require(flags and ENCRYPTED_FLAG == 0) { "Encrypted APK ZIP entries are not supported" }
            val method = bytes.u16(offset + 10)
            val crc32 = bytes.u32(offset + 16)
            val compressedSize = bytes.u32(offset + 20)
            val uncompressedSize = bytes.u32(offset + 24)
            val nameLength = bytes.u16(offset + 28)
            val extraLength = bytes.u16(offset + 30)
            val commentLength = bytes.u16(offset + 32)
            val localOffset = bytes.u32(offset + 42)
            require(
                compressedSize != ZIP64_U32 &&
                    uncompressedSize != ZIP64_U32 &&
                    localOffset != ZIP64_U32
            ) { "ZIP64 APK entries are not supported" }
            val end = offset + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
            require(end <= bytes.size) { "APK ZIP directory entry is truncated" }
            val charset = if (flags and UTF8_FLAG != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
            val name = bytes.copyOfRange(
                offset + CENTRAL_HEADER_SIZE,
                offset + CENTRAL_HEADER_SIZE + nameLength
            ).toString(charset)
            entries += RemoteZipEntry(
                name = name,
                method = method,
                crc32 = crc32,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localHeaderOffset = localOffset
            )
            offset = end
        }
        require(offset <= bytes.size) { "APK ZIP directory is invalid" }
        return entries
    }

    private const val MIN_EOCD_SIZE = 22
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val ZIP64_U16 = 0xffff
    private const val ZIP64_U32 = 0xffff_ffffL
    private const val ENCRYPTED_FLAG = 1
    private const val UTF8_FLAG = 0x0800
}

private fun ByteArray.u16(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

private fun ByteArray.u32(offset: Int): Long =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
