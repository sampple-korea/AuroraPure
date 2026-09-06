/*
 * The protocol flow in this file is derived from Aurora Store 4.8.3.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.play

import android.content.Context
import android.util.Log
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.web.WebAppDetailsHelper
import com.aurora.gplayapi.helpers.web.WebSearchHelper
import com.aurora.pure.data.AppSummary
import com.aurora.pure.data.ArtifactPlan
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.network.PureHttpClient
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuroraGateway(private val context: Context) {
    val httpClient = PureHttpClient()

    @Volatile
    private var cachedAuth: AuthData? = null

    suspend fun connect(force: Boolean = false): AuthData = withContext(Dispatchers.IO) {
        val existing = cachedAuth
        if (!force && existing != null && AuthHelper.isValid(existing)) return@withContext existing

        Log.i(TAG, "Requesting anonymous credentials")
        val properties = DeviceProfile.properties(context)
        val body = properties.toJson().toString().toByteArray()
        val response = httpClient.postAuth(DISPENSER_URL, body)
        if (!response.isSuccessful) {
            throw IllegalStateException(dispenserError(response.code, response.errorString))
        }

        val json = JSONObject(String(response.responseBytes))
        val email = json.optString("email")
        val token = json.optString("authToken")
        require(email.isNotBlank() && token.isNotBlank()) {
            "Anonymous connection returned incomplete credentials"
        }

        Log.i(TAG, "Anonymous credentials received; creating Google Play session")
        AuthHelper.using(httpClient).build(
            email = email,
            token = token,
            tokenType = AuthHelper.Token.AUTH,
            isAnonymous = true,
            properties = properties,
            locale = currentLocale()
        ).also { auth ->
            require(auth.authToken.isNotBlank() && auth.deviceConfigToken.isNotBlank()) {
                "Google Play did not create a usable anonymous session"
            }
            cachedAuth = auth
            Log.i(TAG, "Anonymous Google Play session is ready")
        }
    }

    fun disconnect() {
        cachedAuth = null
    }

    suspend fun search(query: String): List<AppSummary> = withContext(Dispatchers.IO) {
        val helper = WebSearchHelper().using(httpClient).with(currentLocale())
        helper.searchResults(query.trim())
            .streamClusters
            .values
            .flatMap { it.clusterAppList }
            .distinctBy { it.packageName }
            .map { app -> app.toSummary() }
    }

    suspend fun details(packageName: String): AppSummary = withContext(Dispatchers.IO) {
        WebAppDetailsHelper()
            .using(httpClient)
            .with(currentLocale())
            .getAppByPackageName(packageName)
            .toSummary()
    }

    suspend fun resolvePlan(packageName: String): DownloadPlan = withContext(Dispatchers.IO) {
        val auth = connect()
        try {
            resolvePlanWithAuth(packageName, auth)
        } catch (exception: Exception) {
            if (httpClient.responseCode.value !in AUTH_FAILURE_CODES) throw exception
            Log.i(TAG, "Refreshing an expired anonymous Google Play session")
            cachedAuth = null
            resolvePlanWithAuth(packageName, connect(force = true))
        }
    }

    private fun resolvePlanWithAuth(packageName: String, auth: AuthData): DownloadPlan {
        Log.i(TAG, "Resolving native delivery metadata for $packageName")
        val app = AppDetailsHelper(auth).using(httpClient).getAppByPackageName(packageName)
        require(app.packageName == packageName) { "Google Play returned a different package" }
        require(app.isFree) { "Paid apps are not supported by Aurora Pure" }

        val purchaseHelper = PurchaseHelper(auth).using(httpClient)
        val primaryFiles = app.fileList.takeIf(::hasDownloadUrls) ?: run {
            purchaseHelper.purchase(app.packageName, app.versionCode, app.offerType)
        }

        val allFiles = mutableListOf<Pair<App, PlayFile>>()
        primaryFiles.forEach { allFiles += app to it }
        app.dependencies.dependentLibraries.forEach { dependency ->
            val files = dependency.fileList.takeIf(::hasDownloadUrls) ?: run {
                purchaseHelper.purchase(
                    dependency.packageName,
                    dependency.versionCode,
                    dependency.offerType
                )
            }
            files.forEach { allFiles += dependency to it }
        }

        val hasAdditionalData = allFiles.any { (_, file) ->
            file.type == PlayFile.Type.OBB || file.type == PlayFile.Type.PATCH
        }
        val apkFiles = allFiles.filter { (_, file) ->
            file.type == PlayFile.Type.BASE || file.type == PlayFile.Type.SPLIT
        }
        require(apkFiles.isNotEmpty()) { "Google Play returned no APK files for this device" }

        val artifacts = apkFiles.map { (owner, file) ->
            if (!DeliveryUrlPolicy.isAllowed(file.url)) {
                Log.w(TAG, "Rejected delivery endpoint ${DeliveryUrlPolicy.safeEndpoint(file.url)}")
                throw IllegalArgumentException("Rejected a non-Google or non-HTTPS delivery URL")
            }
            ArtifactPlan(
                ownerPackage = owner.packageName,
                ownerVersionCode = owner.versionCode,
                name = file.name,
                url = file.url,
                size = file.size,
                type = file.type.name,
                sha1 = file.sha1,
                sha256 = file.sha256,
                isDependency = owner.packageName != app.packageName
            )
        }
        require(artifacts.map { it.relativePath }.distinct().size == artifacts.size) {
            "Google Play returned conflicting APK file names"
        }

        return DownloadPlan(
            id = UUID.randomUUID().toString(),
            packageName = app.packageName,
            displayName = app.displayName.ifBlank { app.packageName },
            versionName = app.versionName,
            versionCode = app.versionCode,
            checkedAt = System.currentTimeMillis(),
            deviceDescription = DeviceProfile.description(),
            artifacts = artifacts,
            hasAdditionalData = hasAdditionalData
        )
    }

    private fun App.toSummary() = AppSummary(
        packageName = packageName,
        displayName = displayName.ifBlank { packageName },
        developerName = developerName,
        iconUrl = iconArtwork.url,
        versionName = versionName,
        versionCode = versionCode,
        size = size,
        isFree = isFree,
        shortDescription = shortDescription.ifBlank { description.take(280) }
    )

    private fun Properties.toJson() = JSONObject().also { json ->
        stringPropertyNames().forEach { name -> json.put(name, getProperty(name)) }
    }

    private fun hasDownloadUrls(files: List<PlayFile>): Boolean =
        files.isNotEmpty() && files.all { it.url.isNotBlank() }

    private fun currentLocale(): Locale =
        context.resources.configuration.locales[0] ?: Locale.getDefault()

    private fun dispenserError(code: Int, serverMessage: String): String = when (code) {
        400 -> "Anonymous connection rejected the device profile"
        403 -> "Anonymous connection is unavailable for this network"
        404 -> "Anonymous connection service was not found"
        429 -> "Anonymous connection is rate limited; try again later"
        503 -> "Anonymous connection service is under maintenance"
        else -> serverMessage.ifBlank { "Anonymous connection failed (HTTP $code)" }
    }

    companion object {
        private const val TAG = "AuroraPure"
        const val DISPENSER_URL = "https://auroraoss.com/api/auth"
        private val AUTH_FAILURE_CODES = setOf(401, 403)
    }
}
