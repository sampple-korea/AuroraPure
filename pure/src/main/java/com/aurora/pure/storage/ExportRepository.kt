/*
 * ZIP export is derived from Aurora Store's ExportWorker.
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.aurora.pure.data.DownloadOutcome
import com.aurora.pure.data.DownloadedArtifact
import com.aurora.pure.data.ExportResult
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ExportRepository(private val context: Context) {
    suspend fun export(outcome: DownloadOutcome, customTreeUri: String): ExportResult =
        withContext(Dispatchers.IO) {
            if (customTreeUri.isBlank()) {
                val available = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                    .availableBytes
                require(available >= outcome.plan.totalBytes + MINIMUM_FREE_BYTES) {
                    "Not enough space to save the completed file"
                }
            }
            val extension = if (outcome.plan.isSingleApk) "apk" else "apks"
            val mimeType = if (extension == "apk") APK_MIME else APKS_MIME
            val baseName = buildBaseName(outcome)
            val requestedName = uniqueDisplayName("$baseName.$extension", customTreeUri)
            val target = if (customTreeUri.isBlank()) {
                createMediaStoreTarget(requestedName, mimeType)
            } else {
                createDocumentTarget(Uri.parse(customTreeUri), requestedName, mimeType)
            }

            try {
                requireNotNull(context.contentResolver.openOutputStream(target.uri, "w")) {
                    "Android could not open the saved file"
                }.use { output ->
                    if (outcome.plan.isSingleApk) {
                        outcome.artifacts.single().file.inputStream().use {
                            copyCancellable(it, output)
                        }
                    } else {
                        writeArchive(outcome, output)
                    }
                }
                currentCoroutineContext().ensureActive()
                val finalUri = target.finish()
                verifySavedFile(finalUri, outcome)
                val size = context.contentResolver.openAssetFileDescriptor(finalUri, "r")
                    ?.use { descriptor -> descriptor.length.coerceAtLeast(0) } ?: 0L
                ExportResult(finalUri.toString(), requestedName, size)
            } catch (exception: Exception) {
                target.abort()
                throw exception
            }
        }

    suspend fun cleanupAbandonedExports() = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=?",
                arrayOf("Download/AuroraPure/", "1"),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    resolver.delete(
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn)
                        ),
                        null,
                        null
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not clean abandoned MediaStore exports (${it.javaClass.simpleName})")
        }
    }

    private fun createMediaStoreTarget(name: String, mimeType: String): PendingTarget {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AuroraPure")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Android could not create the download file" }
        return PendingTarget(
            uri = uri,
            finish = {
                val updated = context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
                require(updated == 1) { "Could not finalize the exported file" }
                uri
            },
            abort = { context.contentResolver.delete(uri, null, null) }
        )
    }

    private fun createDocumentTarget(treeUri: Uri, name: String, mimeType: String): PendingTarget {
        val tree = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) {
            "The selected folder is no longer available"
        }
        require(tree.canWrite()) { "The selected folder is read-only" }
        val partialName = "$name.partial"
        tree.findFile(partialName)?.delete()
        val document = requireNotNull(tree.createFile(mimeType, partialName)) {
            "Could not create a file in the selected folder"
        }
        return PendingTarget(
            uri = document.uri,
            finish = {
                require(document.renameTo(name)) { "Could not finalize the exported file" }
                document.uri
            },
            abort = { document.delete() }
        )
    }

    private fun uniqueDisplayName(requested: String, customTreeUri: String): String {
        val exists = if (customTreeUri.isBlank()) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf("Download/AuroraPure/", requested),
                null
            )?.use { it.moveToFirst() } == true
        } else {
            DocumentFile.fromTreeUri(context, Uri.parse(customTreeUri))?.findFile(requested) != null
        }
        if (!exists) return requested
        val dot = requested.lastIndexOf('.')
        val suffix = "_${System.currentTimeMillis()}"
        return if (dot > 0) requested.substring(0, dot) + suffix + requested.substring(dot) else requested + suffix
    }

    private fun buildBaseName(outcome: DownloadOutcome): String {
        val architectures = outcome.plan.deliveryProfiles
            .map { it.variant }
            .distinct()
        val abiTag = if (architectures.size > 1) {
            "universal"
        } else {
            architectures.singleOrNull()?.archiveDirectory.orEmpty()
        }
        val densities = outcome.plan.deliveryProfiles
            .map { it.densityDpi }
            .distinct()
        val densityTag = densities.singleOrNull()?.let { "${it}dpi" }.orEmpty()
        return listOf(
            outcome.plan.packageName,
            "v${outcome.plan.versionCode}",
            abiTag,
            densityTag
        )
            .filter(String::isNotBlank)
            .joinToString("_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private suspend fun writeArchive(outcome: DownloadOutcome, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            archiveEntries(outcome).forEach { archive ->
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(archive.name))
                archive.artifact.file.inputStream().use { copyCancellable(it, zip) }
                zip.closeEntry()
            }
        }
    }

    private fun archiveEntries(outcome: DownloadOutcome): List<ArchiveArtifact> {
        val items = outcome.artifacts.distinctBy {
            it.plan.contentIdentity() ?: it.plan.relativePath
        }
        val baseNames = items.associateWith { item ->
            safeArchiveName(item.plan.name.substringAfterLast('/').substringAfterLast('\\'))
        }
        val counts = baseNames.values.groupingBy(String::lowercase).eachCount()
        val used = mutableSetOf<String>()
        return items.map { item ->
            val base = baseNames.getValue(item)
            val needsPrefix = item.plan.isDependency || counts.getValue(base.lowercase()) > 1
            val preferred = if (needsPrefix) {
                listOf(
                    item.plan.ownerPackage.takeIf { item.plan.isDependency }.orEmpty(),
                    item.plan.variant.archiveDirectory,
                    "${item.plan.densityDpi}dpi",
                    "api${item.plan.sdkVersion}",
                    base
                ).filter(String::isNotBlank).joinToString("_")
            } else {
                base
            }
            var candidate = safeArchiveName(preferred)
            var suffix = 2
            while (!used.add(candidate.lowercase())) {
                candidate = safeArchiveName(preferred.removeSuffix(".apk") + "_$suffix.apk")
                suffix += 1
            }
            ArchiveArtifact(candidate, item)
        }
    }

    private fun safeArchiveName(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "artifact.apk"
        return if (sanitized.endsWith(".apk", ignoreCase = true)) sanitized else "$sanitized.apk"
    }

    private suspend fun copyCancellable(input: java.io.InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private suspend fun verifySavedFile(uri: Uri, outcome: DownloadOutcome) {
        val expectedByPath = outcome.verification.files.associate { it.relativePath to it.sha256 }
        if (outcome.plan.isSingleApk) {
            val actual = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Android could not reopen the saved file"
            }.use { digestCancellable(it) }
            require(actual.equals(expectedByPath.values.single(), ignoreCase = true)) {
                "Saved file verification failed"
            }
            return
        }

        val expected = archiveEntries(outcome).associate { archive ->
            archive.name to requireNotNull(expectedByPath[archive.artifact.plan.relativePath]) {
                "Downloaded verification report is incomplete"
            }
        }
        val observed = mutableMapOf<String, String>()
        requireNotNull(context.contentResolver.openInputStream(uri)) {
            "Android could not reopen the saved file"
        }.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name in expected -> {
                            require(!entry.isDirectory && entry.name !in observed) {
                                "Saved archive contains an invalid APK entry"
                            }
                            observed[entry.name] = digestCancellable(zip)
                        }
                        else -> throw IllegalArgumentException(
                            "Saved archive contains an unexpected entry"
                        )
                    }
                    zip.closeEntry()
                }
            }
        }
        require(observed.keys == expected.keys) {
            "Saved archive is missing required entries"
        }
        require(observed.all { (path, digest) -> digest.equals(expected[path], ignoreCase = true) }) {
            "Saved archive verification failed"
        }
    }

    private suspend fun digestCancellable(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class PendingTarget(
        val uri: Uri,
        val finish: () -> Uri,
        val abort: () -> Unit
    )

    private data class ArchiveArtifact(
        val name: String,
        val artifact: DownloadedArtifact
    )

    companion object {
        private const val TAG = "AuroraPure"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val APKS_MIME = "application/zip"
        private const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
    }
}
