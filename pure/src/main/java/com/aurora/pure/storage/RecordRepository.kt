/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.storage

import android.content.Context
import android.net.Uri
import com.aurora.pure.R
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryProfile
import com.aurora.pure.data.DensityChoice
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.download.RemoteApkSplitReader
import org.json.JSONArray
import org.json.JSONObject

class RecordRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("aurora_pure", Context.MODE_PRIVATE)

    var themeMode: Int
        get() = preferences.getInt("theme_mode", 0)
        set(value) = preferences.edit().putInt("theme_mode", value).apply()

    var keepScreenOn: Boolean
        get() = preferences.getBoolean("keep_screen_on", false)
        set(value) = preferences.edit().putBoolean("keep_screen_on", value).apply()

    var customFolderUri: String
        get() = preferences.getString("custom_folder_uri", "").orEmpty()
        set(value) = preferences.edit().putString("custom_folder_uri", value).apply()

    fun load(): List<DownloadRecord> {
        val raw = preferences.getString("records", "[]").orEmpty()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(item.toRecord())
                }
            }
        }.getOrDefault(emptyList()).map { record ->
            if (record.status in setOf(
                    TaskStatus.CHECKING,
                    TaskStatus.DOWNLOADING,
                    TaskStatus.VERIFYING,
                    TaskStatus.EXPORTING
                )
            ) {
                record.copy(
                    status = TaskStatus.PAUSED,
                    error = context.getString(R.string.message_previous_session_ended)
                )
            } else {
                record
            }
        }
    }

    fun save(records: List<DownloadRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        preferences.edit().putString("records", array.toString()).apply()
    }

    fun outputExists(record: DownloadRecord): Boolean {
        if (record.outputUri.isBlank()) return false
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(record.outputUri), "r")
                ?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun deleteOutput(record: DownloadRecord): Boolean {
        if (record.outputUri.isBlank()) return false
        return runCatching {
            context.contentResolver.delete(Uri.parse(record.outputUri), null, null) > 0
        }.getOrDefault(false)
    }

    fun clearTemporaryFiles(): Boolean {
        val root = jobRoot()
        val jobsCleared = !root.exists() || root.deleteRecursively()
        return RemoteApkSplitReader.clearCache(context) && jobsCleared
    }

    fun deleteTaskFiles(taskId: String): Boolean {
        val task = taskDirectory(taskId)
        return !task.exists() || task.deleteRecursively()
    }

    fun jobRoot() = context.cacheDir.resolve("pure-jobs")

    fun taskDirectory(taskId: String): java.io.File {
        require(TASK_ID_PATTERN.matches(taskId)) { "Invalid download task identifier" }
        return jobRoot().resolve(taskId)
    }

    private fun DownloadRecord.toJson() = JSONObject().apply {
        put("id", id)
        put("packageName", packageName)
        put("displayName", displayName)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("architectureChoice", architectureChoice.name)
        put("densityChoice", densityChoice.name)
        put("deliveryProfiles", JSONArray().apply {
            deliveryProfiles.forEach { profile ->
                put(JSONObject().apply {
                    put("variant", profile.variant.name)
                    put("platforms", JSONArray(profile.platforms))
                    put("densityDpi", profile.densityDpi)
                    put("sdkVersion", profile.sdkVersion)
                })
            }
        })
        put("planFingerprint", planFingerprint)
        put("checkedAt", checkedAt)
        put("createdAt", createdAt)
        put("completedAt", completedAt)
        put("status", status.name)
        put("downloadedBytes", downloadedBytes)
        put("totalBytes", totalBytes)
        put("completedFiles", completedFiles)
        put("totalFiles", totalFiles)
        put("outputUri", outputUri)
        put("outputName", outputName)
        put("outputSize", outputSize)
        put("verification", verification)
        put("hasAdditionalData", hasAdditionalData)
        put("error", error)
    }

    private fun JSONObject.toRecord() = DownloadRecord(
        id = optString("id"),
        packageName = optString("packageName"),
        displayName = optString("displayName"),
        versionName = optString("versionName"),
        versionCode = optLong("versionCode"),
        architectureChoice = ArchitectureChoice.fromStored(optString("architectureChoice")),
        densityChoice = DensityChoice.fromStored(optString("densityChoice")),
        deliveryProfiles = optJSONArray("deliveryProfiles")?.let { profiles ->
            buildList {
                for (index in 0 until profiles.length()) {
                    val profile = profiles.optJSONObject(index) ?: continue
                    val variant = runCatching {
                        ArchitectureVariant.valueOf(profile.optString("variant"))
                    }.getOrNull() ?: continue
                    val platformsJson = profile.optJSONArray("platforms")
                    val platforms = buildList {
                        if (platformsJson != null) {
                            for (platformIndex in 0 until platformsJson.length()) {
                                platformsJson.optString(platformIndex)
                                    .takeIf(String::isNotBlank)
                                    ?.let(::add)
                            }
                        }
                    }.ifEmpty { variant.platforms }
                    val density = profile.optInt("densityDpi")
                    val sdk = profile.optInt("sdkVersion")
                    if (density > 0 && sdk >= 21) {
                        add(DeliveryProfile(variant, platforms, density, sdk))
                    }
                }
            }
        }.orEmpty(),
        planFingerprint = optString("planFingerprint"),
        checkedAt = optLong("checkedAt"),
        createdAt = optLong("createdAt"),
        completedAt = optLong("completedAt"),
        status = runCatching { TaskStatus.valueOf(optString("status")) }
            .getOrDefault(TaskStatus.FAILED),
        downloadedBytes = optLong("downloadedBytes"),
        totalBytes = optLong("totalBytes"),
        completedFiles = optInt("completedFiles"),
        totalFiles = optInt("totalFiles"),
        outputUri = optString("outputUri"),
        outputName = optString("outputName"),
        outputSize = optLong("outputSize"),
        verification = optString("verification"),
        hasAdditionalData = optBoolean("hasAdditionalData"),
        error = optString("error")
    )

    companion object {
        private val TASK_ID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
    }
}
