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
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.ArtifactPlan
import com.aurora.pure.data.DeliveryProfile
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.network.PureHttpClient
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuroraGateway(private val context: Context) {
    val httpClient = PureHttpClient()

    private val cachedAuth = ConcurrentHashMap<ArchitectureVariant, AuthData>()
    private val credentialsMutex = Mutex()

    @Volatile
    private var cachedCredentials: AnonymousCredentials? = null

    suspend fun connectProfiles(
        architectureChoice: ArchitectureChoice,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        DeviceProfile.deliveryProfiles(architectureChoice).forEach { profile ->
            connect(profile, force)
        }
    }

    private suspend fun connect(
        profile: DeliveryProfile,
        force: Boolean = false
    ): AuthData = withContext(Dispatchers.IO) {
        val existing = cachedAuth[profile.variant]
        if (!force && existing != null && AuthHelper.isValid(existing)) return@withContext existing

        if (force) cachedAuth.remove(profile.variant)
        val properties = DeviceProfile.properties(context, profile)
        var credentials = anonymousCredentials(profile, properties)
        Log.i(TAG, "Creating ${profile.variant.name} Google Play session")
        val auth = try {
            buildAuth(credentials, properties)
        } catch (exception: Exception) {
            if (credentials.sourceVariant == profile.variant) throw exception
            Log.i(TAG, "The shared credentials were not reusable; requesting a profile-specific set")
            invalidateCredentials()
            credentials = anonymousCredentials(profile, properties)
            buildAuth(credentials, properties)
        }
        auth.also {
            require(auth.authToken.isNotBlank() && auth.deviceConfigToken.isNotBlank()) {
                "Google Play did not create a usable anonymous session"
            }
            cachedAuth[profile.variant] = auth
            Log.i(TAG, "Anonymous Google Play session is ready for ${profile.variant.name}")
        }
    }

    fun disconnect() {
        cachedAuth.clear()
        cachedCredentials = null
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

    suspend fun resolvePlan(
        packageName: String,
        architectureChoice: ArchitectureChoice = ArchitectureChoice.BOTH
    ): DownloadPlan = withContext(Dispatchers.IO) {
        val profiles = DeviceProfile.deliveryProfiles(architectureChoice)
        val resolutions = profiles.map { profile -> resolveVariant(packageName, profile) }
        val versionCodes = resolutions.map { it.app.versionCode }.distinct()
        require(versionCodes.size == 1) {
            "Google Play returned different versions across selected architectures"
        }

        val primary = resolutions.first().app
        val artifacts = resolutions.flatMap(ResolvedVariant::artifacts)
        require(artifacts.map { it.relativePath }.distinct().size == artifacts.size) {
            "Google Play returned conflicting APK file names"
        }

        DownloadPlan(
            id = UUID.randomUUID().toString(),
            packageName = primary.packageName,
            displayName = primary.displayName.ifBlank { primary.packageName },
            versionName = primary.versionName,
            versionCode = primary.versionCode,
            checkedAt = System.currentTimeMillis(),
            deviceDescription = DeviceProfile.description(profiles),
            architectureChoice = architectureChoice,
            deliveryProfiles = profiles,
            requestedLocales = DeviceProfile.allPlayLocales,
            artifacts = artifacts,
            hasAdditionalData = resolutions.any(ResolvedVariant::hasAdditionalData)
        )
    }

    private suspend fun resolveVariant(
        packageName: String,
        profile: DeliveryProfile
    ): ResolvedVariant {
        val auth = connect(profile)
        try {
            return resolveVariantWithAuth(packageName, profile, auth)
        } catch (exception: Exception) {
            if (httpClient.responseCode.value !in AUTH_FAILURE_CODES) throw exception
            Log.i(TAG, "Refreshing an expired ${profile.variant.name} anonymous session")
            cachedAuth.clear()
            invalidateCredentials()
            return resolveVariantWithAuth(packageName, profile, connect(profile, force = true))
        }
    }

    private fun resolveVariantWithAuth(
        packageName: String,
        profile: DeliveryProfile,
        auth: AuthData
    ): ResolvedVariant {
        Log.i(TAG, "Resolving ${profile.variant.name} delivery metadata for $packageName")
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
                variant = profile.variant,
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

        return ResolvedVariant(
            app = app,
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

    private suspend fun anonymousCredentials(
        profile: DeliveryProfile,
        properties: Properties
    ): AnonymousCredentials = credentialsMutex.withLock {
        cachedCredentials?.let { return@withLock it }
        Log.i(TAG, "Requesting anonymous credentials for ${profile.variant.name}")
        val body = properties.toJson().toString().toByteArray()
        val response = httpClient.postAuth(DISPENSER_URL, body)
        if (!response.isSuccessful) {
            throw IllegalStateException(dispenserError(response.code, response.errorString))
        }

        val json = JSONObject(String(response.responseBytes))
        val credentials = AnonymousCredentials(
            email = json.optString("email"),
            token = json.optString("authToken"),
            sourceVariant = profile.variant
        )
        require(credentials.email.isNotBlank() && credentials.token.isNotBlank()) {
            "Anonymous connection returned incomplete credentials"
        }
        cachedCredentials = credentials
        credentials
    }

    private fun buildAuth(
        credentials: AnonymousCredentials,
        properties: Properties
    ): AuthData = AuthHelper.using(httpClient).build(
        email = credentials.email,
        token = credentials.token,
        tokenType = AuthHelper.Token.AUTH,
        isAnonymous = true,
        properties = properties,
        locale = currentLocale()
    )

    private fun invalidateCredentials() {
        cachedCredentials = null
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

    private data class ResolvedVariant(
        val app: App,
        val artifacts: List<ArtifactPlan>,
        val hasAdditionalData: Boolean
    )

    private data class AnonymousCredentials(
        val email: String,
        val token: String,
        val sourceVariant: ArchitectureVariant
    )

    companion object {
        private const val TAG = "AuroraPure"
        const val DISPENSER_URL = "https://auroraoss.com/api/auth"
        private val AUTH_FAILURE_CODES = setOf(401, 403)
    }
}
