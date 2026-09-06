/*
 * The protocol flow in this file is derived from Aurora Store 4.8.3.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.play

import android.content.Context
import android.util.Base64
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
import com.aurora.pure.download.LanguageSplit
import com.aurora.pure.download.RemoteApkSplitReader
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.network.PureHttpClient
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AuroraGateway(private val context: Context) {
    val httpClient = PureHttpClient()
    private val splitReader = RemoteApkSplitReader(context, httpClient)

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
        val languageCache = mutableMapOf<LanguageCacheKey, LanguageResolution>()
        val resolutions = profiles.map { profile ->
            resolveVariant(packageName, profile, languageCache)
        }
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
            requestedLocales = resolutions
                .flatMap(ResolvedVariant::requestedLocales)
                .distinct()
                .sorted(),
            artifacts = artifacts,
            hasAdditionalData = resolutions.any(ResolvedVariant::hasAdditionalData)
        )
    }

    private suspend fun resolveVariant(
        packageName: String,
        profile: DeliveryProfile,
        languageCache: MutableMap<LanguageCacheKey, LanguageResolution>
    ): ResolvedVariant {
        val auth = connect(profile)
        try {
            return resolveVariantWithAuth(packageName, profile, auth, languageCache)
        } catch (exception: Exception) {
            if (httpClient.responseCode.value !in AUTH_FAILURE_CODES) throw exception
            Log.i(TAG, "Refreshing an expired ${profile.variant.name} anonymous session")
            cachedAuth.clear()
            invalidateCredentials()
            return resolveVariantWithAuth(
                packageName,
                profile,
                connect(profile, force = true),
                languageCache
            )
        }
    }

    private suspend fun resolveVariantWithAuth(
        packageName: String,
        profile: DeliveryProfile,
        auth: AuthData,
        languageCache: MutableMap<LanguageCacheKey, LanguageResolution>
    ): ResolvedVariant {
        Log.i(TAG, "Resolving ${profile.variant.name} delivery metadata for $packageName")
        val app = AppDetailsHelper(auth).using(httpClient).getAppByPackageName(packageName)
        require(app.packageName == packageName) { "Google Play returned a different package" }
        require(app.isFree) { "Paid apps are not supported by Aurora Pure" }

        val purchaseHelper = PurchaseHelper(auth).using(httpClient)
        val primaryFiles = app.fileList.takeIf(::hasDownloadUrls) ?: run {
            purchaseHelper.purchase(app.packageName, app.versionCode, app.offerType)
        }

        val allFiles = mutableListOf<OwnedFile>()
        primaryFiles.forEach { allFiles += OwnedFile(app, it) }
        app.dependencies.dependentLibraries.forEach { dependency ->
            val files = dependency.fileList.takeIf(::hasDownloadUrls) ?: run {
                purchaseHelper.purchase(
                    dependency.packageName,
                    dependency.versionCode,
                    dependency.offerType
                )
            }
            files.forEach { allFiles += OwnedFile(dependency, it) }
        }

        val hasAdditionalData = allFiles.any { owned ->
            owned.file.type == PlayFile.Type.OBB || owned.file.type == PlayFile.Type.PATCH
        }
        val requestedLocales = mutableSetOf<String>()
        allFiles.map(OwnedFile::owner).distinctBy(App::packageName).forEach { owner ->
            val ownerFiles = allFiles.filter { it.owner.packageName == owner.packageName }
            val baseFile = ownerFiles.firstOrNull { it.file.type == PlayFile.Type.BASE }
                ?: throw IllegalArgumentException("Google Play returned no base APK for a package")
            val baseArtifact = baseFile.toArtifact(profile, app)
            val deliveredNames = ownerFiles.map { it.file.name }.toSet()
            val declarations = splitReader.read(baseArtifact).filter { declaration ->
                declaration.moduleName.isBlank() || deliveredNames.any { fileName ->
                    val splitName = fileName.removeSuffix(".apk")
                    splitName == declaration.moduleName ||
                        splitName.startsWith("${declaration.moduleName}.")
                }
            }
            requestedLocales += declarations.flatMap(LanguageSplit::localeKeys)
            val languageSplits = declarations.filter { it.splitName.isNotBlank() }
            if (languageSplits.isEmpty()) return@forEach

            val expectedByName = languageSplits.associateBy { "${it.splitName}.apk" }
            allFiles.replaceAll { owned ->
                val language = expectedByName[owned.file.name]
                if (owned.owner.packageName == owner.packageName && language != null) {
                    owned.copy(localeKeys = language.localeKeys)
                } else {
                    owned
                }
            }

            val key = LanguageCacheKey.from(owner, baseFile.file)
            val existingNames = allFiles.asSequence()
                .filter { it.owner.packageName == owner.packageName }
                .map { it.file.name }
                .toSet()
            val cached = key?.let(languageCache::get)
                ?.takeIf { it.splitNames == expectedByName.keys }
            val resolved = cached ?: resolveLanguageFiles(
                owner = owner,
                auth = auth,
                purchaseHelper = purchaseHelper,
                expected = languageSplits,
                existingNames = existingNames
            ).also { resolution ->
                if (key != null) languageCache[key] = resolution
            }
            resolved.files.forEach { (name, file) ->
                if (name !in existingNames) {
                    allFiles += OwnedFile(
                        owner = owner,
                        file = file,
                        localeKeys = expectedByName.getValue(name).localeKeys
                    )
                }
            }
        }

        val apkFiles = allFiles.filter { owned ->
            owned.file.type == PlayFile.Type.BASE || owned.file.type == PlayFile.Type.SPLIT
        }
        require(apkFiles.isNotEmpty()) { "Google Play returned no APK files for this device" }

        val artifacts = apkFiles.map { it.toArtifact(profile, app) }
        require(artifacts.map { it.relativePath }.distinct().size == artifacts.size) {
            "Google Play returned conflicting APK file names"
        }
        val resolvedLanguageNames = artifacts
            .filter { it.localeKeys.isNotEmpty() }
            .map(ArtifactPlan::name)
            .toSet()
        val expectedLanguageCount = allFiles.asSequence()
            .filter { it.localeKeys.isNotEmpty() }
            .map { it.file.name }
            .distinct()
            .count()
        require(resolvedLanguageNames.size >= expectedLanguageCount) {
            "Google Play did not return every declared language split"
        }
        Log.i(
            TAG,
            "Resolved ${profile.variant.name}: ${artifacts.size} APKs, " +
                "${resolvedLanguageNames.size} language splits"
        )

        return ResolvedVariant(
            app = app,
            artifacts = artifacts,
            hasAdditionalData = hasAdditionalData,
            requestedLocales = requestedLocales.toList()
        )
    }

    private suspend fun resolveLanguageFiles(
        owner: App,
        auth: AuthData,
        purchaseHelper: PurchaseHelper,
        expected: List<LanguageSplit>,
        existingNames: Set<String>
    ): LanguageResolution {
        val expectedByName = expected.associateBy { "${it.splitName}.apk" }
        val resolved = mutableMapOf<String, PlayFile>()
        var deliveryToken = ""

        fun request(languages: String): List<PlayFile> {
            var response = httpClient.withProtocolLanguages(languages) {
                purchaseHelper.getDeliveryResponse(
                    packageName = owner.packageName,
                    updateVersionCode = owner.versionCode,
                    offerType = owner.offerType,
                    deliveryToken = deliveryToken
                )
            }
            if (response.status == DELIVERY_NOT_PURCHASED && deliveryToken.isEmpty()) {
                runCatching {
                    purchaseHelper.acquire(owner.packageName, owner.versionCode, owner.offerType)
                }
                deliveryToken = purchaseHelper.getDeliveryToken(
                    owner.packageName,
                    owner.versionCode,
                    owner.offerType,
                    null
                )
                response = httpClient.withProtocolLanguages(languages) {
                    purchaseHelper.getDeliveryResponse(
                        packageName = owner.packageName,
                        updateVersionCode = owner.versionCode,
                        offerType = owner.offerType,
                        deliveryToken = deliveryToken
                    )
                }
            }
            require(response.status == DELIVERY_OK) {
                "Google Play could not resolve declared language splits"
            }
            return response.appDeliveryData.splitDeliveryDataList.map { split ->
                PlayFile(
                    name = "${split.name}.apk",
                    url = split.downloadUrl,
                    size = split.downloadSize,
                    type = PlayFile.Type.SPLIT,
                    sha1 = decodeHash(split.sha1),
                    sha256 = decodeHash(split.sha256)
                )
            }
        }

        val allKeys = expected.flatMap(LanguageSplit::localeKeys).distinct()
        if (allKeys.isNotEmpty()) {
            request(allKeys.joinToString(",")).forEach { file ->
                if (file.name in expectedByName && file.name !in existingNames) {
                    resolved[file.name] = file
                }
            }
        }

        val missing = expectedByName.keys - existingNames - resolved.keys
        missing.forEachIndexed { index, name ->
            val declaration = expectedByName.getValue(name)
            for (localeKey in declaration.localeKeys) {
                if (index > 0) delay(LANGUAGE_REQUEST_INTERVAL_MS)
                val match = request(localeKey).firstOrNull { it.name == name }
                if (match != null) {
                    resolved[name] = match
                    break
                }
            }
        }

        val unresolved = expectedByName.keys - existingNames - resolved.keys
        require(unresolved.isEmpty()) {
            "Google Play did not return every declared language split"
        }
        return LanguageResolution(expectedByName.keys, resolved)
    }

    private fun OwnedFile.toArtifact(profile: DeliveryProfile, primary: App): ArtifactPlan {
        if (!DeliveryUrlPolicy.isAllowed(file.url)) {
            Log.w(TAG, "Rejected delivery endpoint ${DeliveryUrlPolicy.safeEndpoint(file.url)}")
            throw IllegalArgumentException("Rejected a non-Google or non-HTTPS delivery URL")
        }
        return ArtifactPlan(
            variant = profile.variant,
            ownerPackage = owner.packageName,
            ownerVersionCode = owner.versionCode,
            name = file.name,
            url = file.url,
            size = file.size,
            type = file.type.name,
            sha1 = file.sha1,
            sha256 = file.sha256,
            isDependency = owner.packageName != primary.packageName,
            localeKeys = localeKeys
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

    private fun decodeHash(encoded: String): String {
        if (encoded.isBlank()) return ""
        return Base64.decode(encoded, Base64.URL_SAFE)
            .joinToString("") { "%02x".format(it) }
    }

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
        val hasAdditionalData: Boolean,
        val requestedLocales: List<String>
    )

    private data class OwnedFile(
        val owner: App,
        val file: PlayFile,
        val localeKeys: List<String> = emptyList()
    )

    private data class LanguageResolution(
        val splitNames: Set<String>,
        val files: Map<String, PlayFile>
    )

    private data class LanguageCacheKey(
        val packageName: String,
        val versionCode: Long,
        val baseDigest: String,
        val baseSize: Long
    ) {
        companion object {
            fun from(owner: App, base: PlayFile): LanguageCacheKey? {
                val digest = when {
                    base.sha256.isNotBlank() -> "sha256:${base.sha256.lowercase()}"
                    base.sha1.isNotBlank() -> "sha1:${base.sha1.lowercase()}"
                    else -> return null
                }
                return LanguageCacheKey(owner.packageName, owner.versionCode, digest, base.size)
            }
        }
    }

    private data class AnonymousCredentials(
        val email: String,
        val token: String,
        val sourceVariant: ArchitectureVariant
    )

    companion object {
        private const val TAG = "AuroraPure"
        const val DISPENSER_URL = "https://auroraoss.com/api/auth"
        private const val DELIVERY_OK = 1
        private const val DELIVERY_NOT_PURCHASED = 3
        private const val LANGUAGE_REQUEST_INTERVAL_MS = 100L
        private val AUTH_FAILURE_CODES = setOf(401, 403)
    }
}
