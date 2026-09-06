/*
 * The protocol flow in this file is derived from Aurora Store 4.8.3.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.play

import android.content.Context
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
import com.aurora.pure.network.PureHttpClient
import java.net.URI
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuroraGateway(private val context: Context) {
    val httpClient = PureHttpClient(context.cacheDir.resolve("http-cache"))

    @Volatile
    private var cachedAuth: AuthData? = null

    suspend fun connect(force: Boolean = false): AuthData = withContext(Dispatchers.IO) {
        val existing = cachedAuth
        if (!force && existing != null && AuthHelper.isValid(existing)) return@withContext existing

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

        AuthHelper.using(httpClient).build(
            email = email,
            token = token,
            tokenType = AuthHelper.Token.AUTH,
            isAnonymous = true,
            properties = properties,
            locale = Locale.getDefault()
        ).also { auth ->
            require(auth.authToken.isNotBlank() && auth.deviceConfigToken.isNotBlank()) {
                "Google Play did not create a usable anonymous session"
            }
            cachedAuth = auth
        }
    }

    fun disconnect() {
        cachedAuth = null
    }

    suspend fun search(query: String): List<AppSummary> = withContext(Dispatchers.IO) {
        val helper = WebSearchHelper().using(httpClient).with(Locale.getDefault())
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
            .with(Locale.getDefault())
            .getAppByPackageName(packageName)
            .toSummary()
    }

    suspend fun resolvePlan(packageName: String): DownloadPlan = withContext(Dispatchers.IO) {
        resolvePlanWithAuth(packageName, connect())
    }

    private fun resolvePlanWithAuth(packageName: String, auth: AuthData): DownloadPlan {
        val app = AppDetailsHelper(auth).using(httpClient).getAppByPackageName(packageName)
        require(app.packageName == packageName) { "Google Play returned a different package" }
        require(app.isFree) { "Paid apps are not supported by Aurora Pure" }

        val purchaseHelper = PurchaseHelper(auth).using(httpClient)
        val primaryFiles = app.fileList.ifEmpty {
            purchaseHelper.purchase(app.packageName, app.versionCode, app.offerType)
        }

        val allFiles = mutableListOf<Pair<App, PlayFile>>()
        primaryFiles.forEach { allFiles += app to it }
        app.dependencies.dependentLibraries.forEach { dependency ->
            val files = dependency.fileList.ifEmpty {
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
            require(isAllowedDeliveryUrl(file.url)) { "Rejected a non-Google or non-HTTPS delivery URL" }
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
        }.distinctBy { "${it.ownerPackage}/${it.name}" }

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

    private fun isAllowedDeliveryUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme.equals("https", ignoreCase = true) && ALLOWED_GOOGLE_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }.getOrDefault(false)

    private fun dispenserError(code: Int, serverMessage: String): String = when (code) {
        400 -> "Anonymous connection rejected the device profile"
        403 -> "Anonymous connection is unavailable for this network"
        404 -> "Anonymous connection service was not found"
        429 -> "Anonymous connection is rate limited; try again later"
        503 -> "Anonymous connection service is under maintenance"
        else -> serverMessage.ifBlank { "Anonymous connection failed (HTTP $code)" }
    }

    companion object {
        const val DISPENSER_URL = "https://auroraoss.com/api/auth"

        private val ALLOWED_GOOGLE_SUFFIXES = setOf(
            "google.com",
            "googleapis.com",
            "googleusercontent.com",
            "gvt1.com",
            "ggpht.com",
            "googlevideo.com"
        )
    }
}
