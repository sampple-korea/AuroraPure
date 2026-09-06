/*
 * The protocol flow in this file is derived from Aurora Store and GPlayApi.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.web.WebAppDetailsHelper
import com.aurora.gplayapi.helpers.web.WebSearchHelper
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class PlayGateway(
    private val cacheRoot: Path,
    private val locale: Locale,
    val http: CliHttpClient = CliHttpClient()
) {
    private val artifactCache = ArtifactCache(cacheRoot.resolve("artifacts"), http)
    private val auth = ConcurrentHashMap<String, AuthData>()
    @Volatile private var credentials: Credentials? = null

    suspend fun search(query: String): List<AppInfo> = withContext(Dispatchers.IO) {
        WebSearchHelper().using(http).with(locale)
            .searchResults(query.trim())
            .streamClusters.values
            .flatMap { it.clusterAppList }
            .distinctBy(App::packageName)
            .map { it.toInfo() }
    }

    suspend fun details(packageName: String): AppInfo = withContext(Dispatchers.IO) {
        WebAppDetailsHelper().using(http).with(locale)
            .getAppByPackageName(packageName)
            .toInfo()
    }

    suspend fun discover(
        packageName: String,
        architecture: ArchitectureMode,
        density: DensityMode,
        maxSdk: Int = CURRENT_ANDROID_API,
        onProbe: (completed: Int, profile: DeliveryProfile) -> Unit = { _, _ -> }
    ): List<DeliveryVariant> = withContext(Dispatchers.IO) {
        require(maxSdk >= MIN_ANDROID_API) { "Android API must be 21 or newer" }
        val snapshots = mutableListOf<Snapshot>()
        var completed = 0
        architecture.variants.forEach { abi ->
            density.densities.forEach { dpi ->
                var sdk = maxSdk
                val visited = mutableSetOf<Int>()
                while (sdk >= MIN_ANDROID_API && visited.add(sdk)) {
                    val profile = DeliveryProfile(abi, dpi, sdk)
                    try {
                        val resolved = resolveProfile(
                            packageName,
                            profile,
                            mutableMapOf(),
                            resolveLanguages = false
                        )
                        snapshots += Snapshot(profile, resolved)
                        val nextSdk = resolved.minSdk - 1
                        if (nextSdk < MIN_ANDROID_API || nextSdk >= sdk) break
                        sdk = nextSdk
                    } catch (error: Exception) {
                        if (!isUnsupported(error)) throw error
                        break
                    } finally {
                        completed += 1
                        onProbe(completed, profile)
                    }
                }
            }
        }
        require(snapshots.isNotEmpty()) {
            "Google Play returned no APKs for the selected delivery scope"
        }

        val groups = snapshots.groupBy { it.signature() }.values
            .map(::variantFrom)
            .sortedWith(
                compareByDescending<DeliveryVariant> { it.versionCode }
                    .thenBy { it.minSdk }
                    .thenBy { it.architectures.first() }
                    .thenBy { it.densities.first() }
            )
        val latestVersion = groups.maxOf(DeliveryVariant::versionCode)
        val latestGroups = groups.filter { it.versionCode == latestVersion }
        val discoveredArchitectures = snapshots.map { it.profile.abi.label }.toSet()
        val latestArtifacts = snapshots
            .filter { it.resolution.app.versionCode == latestVersion }
            .flatMap { it.resolution.artifacts }
            .distinctBy { it.identity() ?: it.relativePath }
        val aggregate = latestGroups.takeIf { it.size > 1 }?.let { variants ->
            DeliveryVariant(
                id = "aggregate-${sha256(variants.flatMap { it.profiles }.joinToString { it.id }).take(16)}",
                versionName = variants.first().versionName,
                versionCode = latestVersion,
                minSdk = variants.minOf(DeliveryVariant::minSdk),
                targetSdk = variants.maxOf(DeliveryVariant::targetSdk),
                profiles = variants.flatMap(DeliveryVariant::profiles).distinctBy(DeliveryProfile::id),
                downloadProfiles = variants.flatMap(DeliveryVariant::downloadProfiles)
                    .distinctBy(DeliveryProfile::id),
                artifactCount = latestArtifacts.size,
                totalBytes = latestArtifacts.sumOf { it.size.coerceAtLeast(0) },
                aggregate = true,
                universal = architecture == ArchitectureMode.UNIVERSAL
            )
        }
        if (aggregate == null) {
            groups.map { variant ->
                if (architecture == ArchitectureMode.UNIVERSAL &&
                    variant.architectures.toSet() == discoveredArchitectures
                ) {
                    variant.copy(aggregate = true, universal = true)
                } else {
                    variant
                }
            }
        } else {
            listOf(aggregate) + groups
        }
    }

    suspend fun resolvePlan(
        packageName: String,
        architecture: ArchitectureMode,
        density: DensityMode,
        variant: DeliveryVariant
    ): DownloadPlan = withContext(Dispatchers.IO) {
        require(variant.downloadProfiles.isNotEmpty() && variant.downloadProfiles.size <= 64)
        val languageCache = mutableMapOf<LanguageCacheKey, LanguageResolution>()
        val resolutions = variant.downloadProfiles.map { profile ->
            resolveProfile(packageName, profile, languageCache, resolveLanguages = true)
        }
        val versions = resolutions.map { it.app.versionCode }.distinct()
        require(versions.size == 1 && versions.single() == variant.versionCode) {
            "Google Play delivery changed while the variant was being selected"
        }
        val first = resolutions.first()
        DownloadPlan(
            packageName = first.app.packageName,
            name = first.app.displayName.ifBlank { first.app.packageName },
            versionName = first.app.versionName,
            versionCode = first.app.versionCode,
            minSdk = resolutions.minOf(ProfileResolution::minSdk),
            targetSdk = resolutions.maxOf(ProfileResolution::targetSdk),
            checkedAt = System.currentTimeMillis(),
            architectureMode = architecture,
            densityMode = density,
            profiles = variant.profiles,
            requestedLocales = resolutions.flatMap(ProfileResolution::requestedLocales)
                .distinct().sorted(),
            artifacts = resolutions.flatMap(ProfileResolution::artifacts),
            hasAdditionalData = resolutions.any(ProfileResolution::hasAdditionalData)
        )
    }

    fun cachedArtifact(artifact: Artifact): Path? = artifactCache.find(artifact)

    private suspend fun connect(profile: DeliveryProfile, force: Boolean = false): AuthData =
        withContext(Dispatchers.IO) {
            val existing = auth[profile.id]
            if (!force && existing != null && AuthHelper.isValid(existing)) return@withContext existing
            if (force) auth.remove(profile.id)
            val properties = DeviceProfiles.properties(profile)
            var anonymous = anonymousCredentials(profile, properties)
            val result = try {
                buildAuth(anonymous, properties)
            } catch (error: Exception) {
                if (anonymous.sourceProfileId == profile.id) throw error
                credentials = null
                anonymous = anonymousCredentials(profile, properties)
                buildAuth(anonymous, properties)
            }
            require(result.authToken.isNotBlank() && result.deviceConfigToken.isNotBlank()) {
                "Google Play did not create a usable anonymous session"
            }
            auth[profile.id] = result
            result
        }

    private suspend fun resolveProfile(
        packageName: String,
        profile: DeliveryProfile,
        languageCache: MutableMap<LanguageCacheKey, LanguageResolution>,
        resolveLanguages: Boolean
    ): ProfileResolution {
        val session = connect(profile)
        return try {
            resolveProfileWithAuth(packageName, profile, session, languageCache, resolveLanguages)
        } catch (error: Exception) {
            if (http.responseCode.value !in setOf(401, 403)) throw error
            auth.clear()
            credentials = null
            resolveProfileWithAuth(
                packageName,
                profile,
                connect(profile, force = true),
                languageCache,
                resolveLanguages
            )
        }
    }

    private suspend fun resolveProfileWithAuth(
        packageName: String,
        profile: DeliveryProfile,
        session: AuthData,
        languageCache: MutableMap<LanguageCacheKey, LanguageResolution>,
        resolveLanguages: Boolean
    ): ProfileResolution {
        val app = AppDetailsHelper(session).using(http).getAppByPackageName(packageName)
        require(app.packageName == packageName) { "Google Play returned a different package" }
        require(app.isFree) { "Paid apps are not supported" }
        val purchase = PurchaseHelper(session).using(http)
        val primaryFiles = app.fileList.takeIf(::hasUrls)
            ?: purchase.purchase(app.packageName, app.versionCode, app.offerType)
        val files = primaryFiles.mapTo(mutableListOf()) { OwnedFile(app, it) }
        app.dependencies.dependentLibraries.forEach { dependency ->
            val dependencyFiles = dependency.fileList.takeIf(::hasUrls)
                ?: purchase.purchase(
                    dependency.packageName,
                    dependency.versionCode,
                    dependency.offerType
                )
            dependencyFiles.forEach { files += OwnedFile(dependency, it) }
        }

        val additionalData = files.any {
            it.file.type == PlayFile.Type.OBB || it.file.type == PlayFile.Type.PATCH
        }
        val requestedLocales = mutableSetOf<String>()
        var minSdk = 1
        var targetSdk = app.targetSdk
        files.map(OwnedFile::owner).distinctBy(App::packageName).forEach { owner ->
            val ownerFiles = files.filter { it.owner.packageName == owner.packageName }
            val base = ownerFiles.firstOrNull { it.file.type == PlayFile.Type.BASE }
                ?: throw IllegalArgumentException("Google Play returned no base APK")
            val baseArtifact = base.toArtifact(profile, app)
            val cachedBase = artifactCache.ensure(baseArtifact).toFile()
            val identity = ApkIntrospection.manifest(cachedBase)
            require(
                identity.packageName == owner.packageName &&
                    identity.versionCode == owner.versionCode &&
                    identity.splitName == null
            ) { "Google Play base APK identity did not match delivery metadata" }
            if (owner.packageName == app.packageName) {
                minSdk = identity.minSdk
                targetSdk = identity.targetSdk.takeIf { it > 0 } ?: app.targetSdk
            }
            val deliveredNames = ownerFiles.map { it.file.name }.toSet()
            val declarations = ApkIntrospection.languageSplits(cachedBase).filter { declaration ->
                declaration.moduleName.isBlank() || deliveredNames.any { fileName ->
                    val splitName = fileName.removeSuffix(".apk")
                    splitName == declaration.moduleName ||
                        splitName.startsWith("${declaration.moduleName}.")
                }
            }
            requestedLocales += declarations.flatMap(LanguageSplit::localeKeys)
            val expected = declarations.filter { it.splitName.isNotBlank() }
            if (expected.isEmpty()) return@forEach
            val expectedByName = expected.associateBy { "${it.splitName}.apk" }
            files.replaceAll { owned ->
                val language = expectedByName[owned.file.name]
                if (owned.owner.packageName == owner.packageName && language != null) {
                    owned.copy(locales = language.localeKeys)
                } else {
                    owned
                }
            }
            if (!resolveLanguages) return@forEach

            val existingNames = files.asSequence()
                .filter { it.owner.packageName == owner.packageName }
                .map { it.file.name }.toSet()
            val key = LanguageCacheKey.from(owner, base.file)
            val resolved = key?.let(languageCache::get)
                ?.takeIf { it.names == expectedByName.keys }
                ?: resolveLanguageFiles(owner, session, purchase, expected, existingNames).also {
                    if (key != null) languageCache[key] = it
                }
            resolved.files.forEach { (name, file) ->
                if (name !in existingNames) {
                    files += OwnedFile(owner, file, expectedByName.getValue(name).localeKeys)
                }
            }
        }

        val artifacts = files.filter {
            it.file.type == PlayFile.Type.BASE || it.file.type == PlayFile.Type.SPLIT
        }.map { it.toArtifact(profile, app) }
        require(artifacts.isNotEmpty()) { "Google Play returned no APK files" }
        val expectedLanguages = requestedLocales.toSet()
        if (resolveLanguages && expectedLanguages.isNotEmpty()) {
            val resolvedLocales = artifacts.flatMap(Artifact::locales).toSet()
            require(resolvedLocales.containsAll(expectedLanguages)) {
                "Google Play did not return every declared language split"
            }
        }
        return ProfileResolution(app, artifacts, additionalData, requestedLocales.toList(), minSdk, targetSdk)
    }

    private suspend fun resolveLanguageFiles(
        owner: App,
        session: AuthData,
        purchase: PurchaseHelper,
        expected: List<LanguageSplit>,
        existingNames: Set<String>
    ): LanguageResolution {
        val expectedByName = expected.associateBy { "${it.splitName}.apk" }
        val resolved = mutableMapOf<String, PlayFile>()
        var deliveryToken = ""

        fun request(languages: String): List<PlayFile> {
            var response = http.withProtocolLanguages(languages) {
                purchase.getDeliveryResponse(
                    packageName = owner.packageName,
                    updateVersionCode = owner.versionCode,
                    offerType = owner.offerType,
                    deliveryToken = deliveryToken
                )
            }
            if (response.status == DELIVERY_NOT_PURCHASED && deliveryToken.isEmpty()) {
                runCatching { purchase.acquire(owner.packageName, owner.versionCode, owner.offerType) }
                deliveryToken = purchase.getDeliveryToken(
                    owner.packageName,
                    owner.versionCode,
                    owner.offerType,
                    null
                )
                response = http.withProtocolLanguages(languages) {
                    purchase.getDeliveryResponse(
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
            for (localeKey in expectedByName.getValue(name).localeKeys) {
                if (index > 0) delay(100)
                val match = request(localeKey).firstOrNull { it.name == name }
                if (match != null) {
                    resolved[name] = match
                    break
                }
            }
        }
        require((expectedByName.keys - existingNames - resolved.keys).isEmpty()) {
            "Google Play did not return every declared language split"
        }
        return LanguageResolution(expectedByName.keys, resolved)
    }

    private fun anonymousCredentials(profile: DeliveryProfile, properties: Properties): Credentials {
        credentials?.let { return it }
        val json = JsonObject().apply {
            properties.stringPropertyNames().forEach { addProperty(it, properties.getProperty(it)) }
        }
        val response = http.postAuth(DISPENSER_URL, json.toString().toByteArray())
        require(response.isSuccessful) {
            when (response.code) {
                400 -> "Anonymous service rejected the device profile"
                403 -> "Anonymous service is unavailable for this network"
                429 -> "Anonymous service is rate limited"
                503 -> "Anonymous service is under maintenance"
                else -> "Anonymous connection failed (HTTP ${response.code})"
            }
        }
        val body = com.google.gson.JsonParser.parseString(String(response.responseBytes)).asJsonObject
        return Credentials(
            email = body.get("email")?.asString.orEmpty(),
            token = body.get("authToken")?.asString.orEmpty(),
            sourceProfileId = profile.id
        ).also {
            require(it.email.isNotBlank() && it.token.isNotBlank()) {
                "Anonymous service returned incomplete credentials"
            }
            credentials = it
        }
    }

    private fun buildAuth(credentials: Credentials, properties: Properties): AuthData =
        AuthHelper.using(http).build(
            email = credentials.email,
            token = credentials.token,
            tokenType = AuthHelper.Token.AUTH,
            isAnonymous = true,
            properties = properties,
            locale = locale
        )

    private fun OwnedFile.toArtifact(profile: DeliveryProfile, primary: App): Artifact {
        require(DeliveryUrlPolicy.isAllowed(file.url)) {
            "Google Play returned a non-approved download URL"
        }
        return Artifact(
            profile = profile,
            ownerPackage = owner.packageName,
            ownerVersionCode = owner.versionCode,
            name = file.name,
            url = file.url,
            size = file.size,
            type = file.type.name,
            sha1 = file.sha1,
            sha256 = file.sha256,
            dependency = owner.packageName != primary.packageName,
            locales = locales
        )
    }

    private fun variantFrom(snapshots: List<Snapshot>): DeliveryVariant {
        val first = snapshots.first().resolution
        val profiles = snapshots.map(Snapshot::profile).distinctBy(DeliveryProfile::id)
        val artifacts = first.artifacts.distinctBy { it.identity() ?: it.relativePath }
        return DeliveryVariant(
            id = "variant-${sha256(snapshots.first().signature()).take(16)}",
            versionName = first.app.versionName,
            versionCode = first.app.versionCode,
            minSdk = first.minSdk,
            targetSdk = first.targetSdk,
            profiles = profiles,
            downloadProfiles = listOf(profiles.first()),
            artifactCount = artifacts.size,
            totalBytes = artifacts.sumOf { it.size.coerceAtLeast(0) }
        )
    }

    private fun App.toInfo() = AppInfo(
        packageName = packageName,
        name = displayName.ifBlank { packageName },
        developer = developerName,
        versionName = versionName,
        versionCode = versionCode,
        targetSdk = targetSdk,
        size = size,
        isFree = isFree,
        description = shortDescription.ifBlank { description.take(500) }
    )

    private fun decodeHash(value: String): String {
        if (value.isBlank()) return ""
        return Base64.getUrlDecoder().decode(value).joinToString("") { "%02x".format(it) }
    }

    private fun hasUrls(files: List<PlayFile>): Boolean =
        files.isNotEmpty() && files.all { it.url.isNotBlank() }

    private fun isUnsupported(error: Exception): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any { cause ->
            cause.javaClass.name.endsWith("InternalException\$AppNotSupported") ||
                cause.javaClass.name.endsWith("InternalException\$EmptyDownloads") ||
                (cause is CliHttpClient.ProtocolHttpException && cause.status == 400) ||
                cause.message in setOf(
                    "Google Play returned no APK files",
                    "Google Play returned no base APK"
                )
        }

    private data class Credentials(
        val email: String,
        val token: String,
        val sourceProfileId: String
    )

    private data class OwnedFile(
        val owner: App,
        val file: PlayFile,
        val locales: List<String> = emptyList()
    )

    private data class ProfileResolution(
        val app: App,
        val artifacts: List<Artifact>,
        val hasAdditionalData: Boolean,
        val requestedLocales: List<String>,
        val minSdk: Int,
        val targetSdk: Int
    )

    private data class Snapshot(
        val profile: DeliveryProfile,
        val resolution: ProfileResolution
    ) {
        fun signature(): String = buildString {
            append(resolution.app.versionCode).append('|')
            append(resolution.minSdk).append('|').append(resolution.targetSdk).append('\n')
            resolution.artifacts.distinctBy { it.identity() ?: it.relativePath }
                .map { it.identity() ?: "${it.ownerPackage}|${it.name}|${it.type}|${it.size}" }
                .sorted().forEach { append(it).append('\n') }
        }
    }

    private data class LanguageResolution(
        val names: Set<String>,
        val files: Map<String, PlayFile>
    )

    private data class LanguageCacheKey(
        val packageName: String,
        val versionCode: Long,
        val digest: String,
        val size: Long
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

    companion object {
        const val CURRENT_ANDROID_API = 36
        const val MIN_ANDROID_API = 21
        const val DISPENSER_URL = "https://auroraoss.com/api/auth"
        private const val DELIVERY_OK = 1
        private const val DELIVERY_NOT_PURCHASED = 3
    }
}
