/*
 * ZIP export is derived from Aurora Store's ExportWorker.
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.aurora.pure.data.DownloadOutcome
import com.aurora.pure.data.ExportResult
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ExportRepository(private val context: Context) {
    suspend fun export(outcome: DownloadOutcome, customTreeUri: String): ExportResult =
        withContext(Dispatchers.IO) {
            val extension = if (outcome.plan.isSingleApk) "apk" else "zip"
            val mimeType = if (extension == "apk") APK_MIME else ZIP_MIME
            val baseName = buildBaseName(outcome)
            val requestedName = uniqueDisplayName("$baseName.$extension", customTreeUri)
            val target = if (customTreeUri.isBlank()) {
                createMediaStoreTarget(requestedName, mimeType)
            } else {
                createDocumentTarget(Uri.parse(customTreeUri), requestedName, mimeType)
            }

            try {
                context.contentResolver.openOutputStream(target.uri, "w")!!.use { output ->
                    if (outcome.plan.isSingleApk) {
                        outcome.artifacts.single().file.inputStream().use { it.copyTo(output) }
                    } else {
                        writeArchive(outcome, output)
                    }
                }
                target.finish()
                val size = context.contentResolver.openAssetFileDescriptor(target.uri, "r")
                    ?.use { descriptor -> descriptor.length.coerceAtLeast(0) } ?: 0L
                ExportResult(target.uri.toString(), requestedName, size)
            } catch (exception: Exception) {
                target.abort()
                throw exception
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
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
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
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return listOf(outcome.plan.packageName, "v${outcome.plan.versionCode}", abi)
            .filter(String::isNotBlank)
            .joinToString("_")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun writeArchive(outcome: DownloadOutcome, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            outcome.artifacts.forEach { item ->
                zip.putNextEntry(ZipEntry(item.plan.relativePath))
                item.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            val metadata = metadata(outcome).toString(2).toByteArray()
            zip.putNextEntry(ZipEntry("download-info.json"))
            zip.write(metadata)
            zip.closeEntry()

            val sums = outcome.verification.files.joinToString("\n", postfix = "\n") {
                "${it.sha256}  ${it.relativePath}"
            }.toByteArray()
            zip.putNextEntry(ZipEntry("SHA256SUMS.txt"))
            zip.write(sums)
            zip.closeEntry()
        }
    }

    private fun metadata(outcome: DownloadOutcome) = JSONObject().apply {
        put("formatVersion", 1)
        put("packageName", outcome.plan.packageName)
        put("versionName", outcome.plan.versionName)
        put("versionCode", outcome.plan.versionCode)
        put("source", "Google Play")
        put("deliveryCheckedAt", timestamp(outcome.plan.checkedAt))
        put("downloadCompletedAt", timestamp(System.currentTimeMillis()))
        put("deviceConfiguration", outcome.plan.deviceDescription)
        put("hasAdditionalNonApkData", outcome.plan.hasAdditionalData)
        put("files", JSONArray().apply {
            outcome.verification.files.forEach { file ->
                put(JSONObject().apply {
                    put("path", file.relativePath)
                    put("packageName", file.ownerPackage)
                    put("size", file.size)
                    put("sha256", file.sha256)
                    put("integrity", file.integrity.name.lowercase())
                    put("signature", file.signature.name.lowercase())
                    put("signerCertificateSha256", JSONArray(file.signerSha256))
                })
            }
        })
        put("verification", JSONObject().apply {
            put("integrity", outcome.verification.integrity.name.lowercase())
            put("signatures", outcome.verification.signatures.name.lowercase())
            put("packageAndVersion", outcome.verification.packageMatch.name.lowercase())
            put("limitations", JSONArray(outcome.verification.limitations))
        })
    }

    private fun timestamp(epochMillis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    private data class PendingTarget(
        val uri: Uri,
        val finish: () -> Unit,
        val abort: () -> Unit
    )

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val ZIP_MIME = "application/zip"
    }
}
