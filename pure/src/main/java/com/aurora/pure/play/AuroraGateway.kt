/*
 * The protocol flow in this file is derived from Aurora Store 4.8.3.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.play

import android.content.Context
import android.os.SystemClock
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
import com.aurora.pure.data.DeliveryVariant
import com.aurora.pure.data.DensityChoice
import com.aurora.pure.data.DiscoveryProgress
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.download.LanguageSplit
import com.aurora.pure.download.RemoteApkSplitReader
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.network.PureHttpClient
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random
import org.json.JSONObject

class AuroraGateway(private val context: Context) {
    val httpClient = PureHttpClient()
    private val splitReader = RemoteApkSplitReader(context, httpClient)

    private val cachedAuth = ConcurrentHashMap<String, AuthData>()
    private val credentialsMutex = Mutex()

    // Re-opening an app, or returning from it, used to replay the exact same web request. These
    // are short-lived on purpose: long enough to make navigation instant, short enough that a
    // deliberate refresh still sees a newly published version.
    private val searchCache = TimedCache<String, List<AppSummary>>(MAX_CACHED_SEARCHES, CACHE_TTL_MS)
    private val detailsCache = TimedCache<String, AppSummary>(MAX_CACHED_DETAILS, CACHE_TTL_MS)

    @Volatile
    private var cachedCredentials: AnonymousCredentials? = null

    suspend fun connectProfiles(
        architectureChoice: ArchitectureChoice,
        densityChoice: DensityChoice = DensityChoice.CURRENT,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        DeviceProfile.deliveryProfiles(architectureChoice, densityChoice).forEach { profile ->
            connect(profile, force)
        }
    }

    private suspend fun connect(
        profile: DeliveryProfile,
        force: Boolean = false
    ): AuthData = withContext(Dispatchers.IO) {
        val existing = cachedAuth[profile.id]
        if (!force && existing != null && AuthHelper.isValid(existing)) return@withContext existing

        if (force) cachedAuth.remove(profile.id)
        val properties = DeviceProfile.properties(context, profile)
        var credentials = anonymousCredentials(profile, properties)
        Log.i(TAG, "Creating ${profile.id} Google Play session")
        val auth = try {
            buildAuth(credentials, properties)
        } catch (exception: Exception) {
            if (credentials.sourceProfileId == profile.id) throw exception
            Log.i(TAG, "The shared credentials were not reusable; requesting a profile-specific set")
            invalidateCredentials()
            credentials = anonymousCredentials(profile, properties)
            buildAuth(credentials, properties)
        }
        auth.also {
            require(auth.authToken.isNotBlank() && auth.deviceConfigToken.isNotBlank()) {
                "Google Play did not create a usable anonymous session"
            }
            cachedAuth[profile.id] = auth
            Log.i(TAG, "Anonymous Google Play session is ready for ${profile.id}")
        }
    }

    fun disconnect() {
        cachedAuth.clear()
        cachedCredentials = null
        searchCache.clear()
        detailsCache.clear()
    }

    /** Bounded, time-limited memo. Sized for a session's browsing, not for offline use. */
    private class TimedCache<K : Any, V : Any>(
        private val maxEntries: Int,
        private val ttlMillis: Long
    ) {
        private class Entry<V>(val value: V, val storedAt: Long)

        private val entries = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>) =
                size > maxEntries
        }

        fun get(key: K): V? = synchronized(entries) {
            val entry = entries[key] ?: return null
            if (SystemClock.elapsedRealtime() - entry.storedAt > ttlMillis) {
                entries.remove(key)
                return null
            }
            entry.value
        }

        fun put(key: K, value: V) = synchronized(entries) {
            entries[key] = Entry(value, SystemClock.elapsedRealtime())
            Unit
        }

        fun clear() = synchronized(entries) { entries.clear() }
    }

    suspend fun search(query: String): List<AppSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        searchCache.get(trimmed)?.let { return@withContext it }
        val helper = WebSearchHelper().using(httpClient).with(currentLocale())
        helper.searchResults(trimmed)
            .streamClusters
            .values
            .flatMap { it.clusterAppList }
            .distinctBy { it.packageName }
            .map { app -> app.toSummary() }
            .also { results -> if (results.isNotEmpty()) searchCache.put(trimmed, results) }
    }

    /** The cached summary a details screen can paint immediately, before the network answers. */
    fun cachedDetails(packageName: String): AppSummary? = detailsCache.get(packageName)

    suspend fun details(packageName: String): AppSummary = withContext(Dispatchers.IO) {
        WebAppDetailsHelper()
            .using(httpClient)
            .with(currentLocale())
            .getAppByPackageName(packageName)
            .toSummary()
            .also { detailsCache.put(packageName, it) }
    }

    /**
     * Outcome of a complete scan. [incomplete] means Google Play kept throttling at least one
     * delivery path, so the matrix on screen is a subset of what the app would normally find.
     */
    data class DiscoveryResult(
        val variants: List<DeliveryVariant>,
        val incomplete: Boolean
    )

    suspend fun discoverVariants(
        packageName: String,
        onProgress: (DiscoveryProgress) -> Unit = {},
        onPartialResults: (List<DeliveryVariant>) -> Unit = {}
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val initial = DeviceProfile.completeDiscoveryProfiles(
            sdkVersions = listOf(LATEST_SUPPORTED_ANDROID_API)
        )
        val probesCompleted = AtomicInteger(0)
        val pathsCompleted = AtomicInteger(0)
        val active = linkedSetOf<DeliveryProfile>()
        val progressLock = Any()
        val collected = mutableListOf<ProfileSnapshot>()
        var firstResultAt = 0L

        fun report(active: List<DeliveryProfile>) = onProgress(
            DiscoveryProgress(
                probesCompleted = probesCompleted.get(),
                pathsCompleted = pathsCompleted.get(),
                totalPaths = initial.size,
                active = active
            )
        )

        fun reportStarted(profile: DeliveryProfile) {
            report(synchronized(progressLock) { active += profile; active.toList() })
        }

        fun reportFinished(profile: DeliveryProfile) {
            probesCompleted.incrementAndGet()
            report(synchronized(progressLock) { active -= profile; active.toList() })
        }

        // Every individual probe is a complete, publishable answer on its own, so a result reaches
        // the list the moment Google Play returns it rather than after its whole ABI/DPI path — let
        // alone after the slowest of 28 paths — has finished walking down the Android tiers.
        // Building and emitting under the same lock keeps probes from delivering a stale snapshot
        // out of order.
        fun publishSnapshot(snapshot: ProfileSnapshot) {
            synchronized(progressLock) {
                collected += snapshot
                if (firstResultAt == 0L) firstResultAt = SystemClock.elapsedRealtime()
                onPartialResults(buildVariants(collected.toList()))
            }
        }

        fun pathFinished() {
            pathsCompleted.incrementAndGet()
            report(synchronized(progressLock) { active.toList() })
        }

        val startedAt = SystemClock.elapsedRealtime()
        val outcomes = coroutineScope {
            val limiter = Semaphore(DISCOVERY_PARALLELISM)
            initial.map { initialProfile ->
                async {
                    limiter.withPermit {
                        discoverProfilePath(
                            packageName = packageName,
                            initialProfile = initialProfile,
                            onStarted = ::reportStarted,
                            onFinished = ::reportFinished,
                            onSnapshot = ::publishSnapshot
                        ).also { pathFinished() }
                    }
                }
            }.awaitAll()
        }
        val snapshots = outcomes.flatMap(PathOutcome::snapshots)
        val throttled = outcomes.any(PathOutcome::throttled)

        require(snapshots.isNotEmpty()) {
            if (throttled) {
                "Anonymous connection is rate limited; try again later"
            } else {
                "Google Play returned no APK files for any supported delivery profile"
            }
        }
        val stats = splitReader.memoStats()
        Log.i(
            TAG,
            "Scanned ${initial.size} delivery paths in " +
                "${SystemClock.elapsedRealtime() - startedAt} ms, " +
                "first results after ${firstResultAt - startedAt} ms " +
                "(${probesCompleted.get()} probes, base APK metadata: " +
                "${stats.reads} read, ${stats.hits} reused" +
                (if (throttled) ", rate limited" else "") + ")"
        )
        DiscoveryResult(buildVariants(snapshots), throttled)
    }

    private fun buildVariants(snapshots: List<ProfileSnapshot>): List<DeliveryVariant> {
        val groups = snapshots.groupBy { it.signature() }
            .values
            .map { matching -> matching.toDeliveryVariant() }
            .sortedWith(
                compareByDescending<DeliveryVariant> { it.versionCode }
                    .thenBy { it.minSdk }
                    .thenBy { it.architectures.first().ordinal }
                    .thenBy { it.densityDpis.first() }
            )

        val latestVersion = groups.maxOf(DeliveryVariant::versionCode)
        val latestGroups = groups.filter { it.versionCode == latestVersion }
        val discoveredArchitectures = snapshots.map { it.profile.variant }.toSet()
        val latestArtifacts = snapshots
            .filter { it.resolution.app.versionCode == latestVersion }
            .flatMap { it.resolution.artifacts }
            .distinctBy { it.contentIdentity() ?: it.relativePath }
        val aggregate = latestGroups.takeIf { it.size > 1 }?.let { variants ->
            DeliveryVariant(
                id = "aggregate-${stableId(variants.flatMap { it.profiles }.joinToString { it.id })}",
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
                universal = true
            )
        }
        return if (aggregate == null) {
            groups.map { variant ->
                if (variant.architectures.toSet() == discoveredArchitectures) {
                    variant.copy(aggregate = true, universal = true)
                } else {
                    variant
                }
            }
        } else {
            listOf(aggregate) + groups
        }
    }

    private class PathOutcome(
        val snapshots: List<ProfileSnapshot>,
        val throttled: Boolean
    )

    private suspend fun discoverProfilePath(
        packageName: String,
        initialProfile: DeliveryProfile,
        onStarted: (DeliveryProfile) -> Unit,
        onFinished: (DeliveryProfile) -> Unit,
        onSnapshot: (ProfileSnapshot) -> Unit
    ): PathOutcome {
        val snapshots = mutableListOf<ProfileSnapshot>()
        var throttled = false
        // Each ABI × DPI pair follows its own manifest minSdk boundaries. A low-density
        // result must never cause a higher-density Android tier to be skipped.
        var sdkVersion = LATEST_SUPPORTED_ANDROID_API
        val visited = mutableSetOf<Int>()
        while (sdkVersion >= MIN_SUPPORTED_ANDROID_API && visited.add(sdkVersion)) {
            val profile = initialProfile.copy(sdkVersion = sdkVersion)
            onStarted(profile)
            try {
                val resolved = resolveVariant(
                    packageName = packageName,
                    profile = profile,
                    languageCache = mutableMapOf(),
                    resolveLanguages = false
                )
                val snapshot = ProfileSnapshot(profile, resolved)
                snapshots += snapshot
                onSnapshot(snapshot)
                val nextSdk = resolved.minSdk - 1
                if (nextSdk < MIN_SUPPORTED_ANDROID_API || nextSdk >= sdkVersion) break
                sdkVersion = nextSdk
            } catch (exception: Exception) {
                // A path that is still throttled after its session was renewed stops here rather
                // than aborting every other path. The scan reports itself as incomplete instead of
                // silently presenting a truncated delivery matrix as if it were the whole picture.
                if (isRateLimited(exception)) {
                    Log.w(TAG, "Gave up on ${profile.id}: still rate limited after a new session")
                    throttled = true
                    break
                }
                if (!isUnsupportedProfile(exception)) throw exception
                Log.i(TAG, "No delivery for ${profile.id}")
                break
            } finally {
                onFinished(profile)
            }
        }
        return PathOutcome(snapshots, throttled)
    }

    suspend fun resolvePlan(
        packageName: String,
        architectureChoice: ArchitectureChoice = ArchitectureChoice.BOTH,
        densityChoice: DensityChoice = DensityChoice.CURRENT,
        selectedProfiles: List<DeliveryProfile> = emptyList()
    ): DownloadPlan = withContext(Dispatchers.IO) {
        val profiles = selectedProfiles.takeIf { it.isNotEmpty() }
            ?: DeviceProfile.deliveryProfiles(architectureChoice, densityChoice)
        require(profiles.size <= MAX_SELECTED_PROFILES && profiles.map { it.id }.distinct().size == profiles.size) {
            "The selected delivery profile list is invalid"
        }
        val languageCache = mutableMapOf<LanguageCacheKey, LanguageResolution>()
        val resolutions = profiles.map { profile ->
            resolveVariant(packageName, profile, languageCache, resolveLanguages = true)
        }
        val versionCodes = resolutions.map { it.app.versionCode }.distinct()
        require(versionCodes.size == 1) {
            "Google Play returned different versions across selected delivery profiles"
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
            minSdk = resolutions.minOf(ResolvedVariant::minSdk),
            targetSdk = resolutions.maxOf(ResolvedVariant::targetSdk),
            checkedAt = System.currentTimeMillis(),
            deviceDescription = DeviceProfile.description(profiles),
            architectureChoice = architectureChoice,
            densityChoice = densityChoice,
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
        languageCache: MutableMap<LanguageCacheKey, LanguageResolution>,
        resolveLanguages: Boolean
    ): ResolvedVariant {
        val auth = connect(profile)
        try {
            return resolveVariantWithAuth(
                packageName,
                profile,
                auth,
                languageCache,
                resolveLanguages
            )
        } catch (exception: Exception) {
            val throttled = isRateLimited(exception)
            if (!isAuthFailure(exception) && !throttled) throw exception
            // A throttled session clears the same way the Reconnect action clears it: drop the
            // cached credentials and ask for a new one. Doing that here means a burst of HTTP 429
            // no longer strands the user in Settings looking for a button to press.
            if (throttled) {
                Log.i(TAG, "Google Play throttled ${profile.id}; renewing the anonymous session")
                delay(RATE_LIMIT_BACKOFF_MS + Random.nextLong(RATE_LIMIT_JITTER_MS))
            } else {
                Log.i(TAG, "Refreshing an expired ${profile.id} anonymous session")
            }
            cachedAuth.clear()
            invalidateCredentials()
            return resolveVariantWithAuth(
                packageName,
                profile,
                connect(profile, force = true),
                languageCache,
                resolveLanguages
            )
        }
    }

    private suspend fun resolveVariantWithAuth(
        packageName: String,
        profile: DeliveryProfile,
        auth: AuthData,
        languageCache: MutableMap<LanguageCacheKey, LanguageResolution>,
        resolveLanguages: Boolean
    ): ResolvedVariant {
        Log.i(TAG, "Resolving ${profile.id} delivery metadata for $packageName")
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
        var primaryMinSdk = 1
        var primaryTargetSdk = app.targetSdk
        allFiles.map(OwnedFile::owner).distinctBy(App::packageName).forEach { owner ->
            val ownerFiles = allFiles.filter { it.owner.packageName == owner.packageName }
            val baseFile = ownerFiles.firstOrNull { it.file.type == PlayFile.Type.BASE }
                ?: throw IllegalArgumentException("Google Play returned no base APK for a package")
            val baseArtifact = baseFile.toArtifact(profile, app)
            val deliveredNames = ownerFiles.map { it.file.name }.toSet()
            val metadata = splitReader.inspect(baseArtifact)
            if (owner.packageName == app.packageName) {
                primaryMinSdk = metadata.identity.minSdk
                primaryTargetSdk = metadata.identity.targetSdk.takeIf { it > 0 } ?: app.targetSdk
            }
            val declarations = metadata.languageSplits.filter { declaration ->
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
            if (!resolveLanguages) return@forEach

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
            requestedLocales = requestedLocales.toList(),
            minSdk = primaryMinSdk,
            targetSdk = primaryTargetSdk
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
            densityDpi = profile.densityDpi,
            sdkVersion = profile.sdkVersion,
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
        Log.i(TAG, "Requesting anonymous credentials for ${profile.id}")
        val body = properties.toJson().toString().toByteArray()
        val response = httpClient.postAuth(DISPENSER_URL, body)
        if (!response.isSuccessful) {
            throw IllegalStateException(dispenserError(response.code, response.errorString))
        }

        val json = JSONObject(String(response.responseBytes))
        val credentials = AnonymousCredentials(
            email = json.optString("email"),
            token = json.optString("authToken"),
            sourceProfileId = profile.id
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
        val requestedLocales: List<String>,
        val minSdk: Int,
        val targetSdk: Int
    )

    private data class ProfileSnapshot(
        val profile: DeliveryProfile,
        val resolution: ResolvedVariant
    ) {
        fun signature(): String = buildString {
            append(resolution.app.versionCode).append('|')
            append(resolution.minSdk).append('|').append(resolution.targetSdk).append('\n')
            resolution.artifacts
                .distinctBy { it.contentIdentity() ?: it.relativePath }
                .map { artifact ->
                    artifact.contentIdentity() ?: listOf(
                        artifact.ownerPackage,
                        artifact.name,
                        artifact.type,
                        artifact.size.toString()
                    ).joinToString("|")
                }
                .sorted()
                .forEach { append(it).append('\n') }
        }
    }

    private fun List<ProfileSnapshot>.toDeliveryVariant(): DeliveryVariant {
        val first = first().resolution
        val uniqueArtifacts = first.artifacts.distinctBy {
            it.contentIdentity() ?: it.relativePath
        }
        val observedProfiles = map(ProfileSnapshot::profile).distinctBy(DeliveryProfile::id)
        return DeliveryVariant(
            id = "variant-${stableId(first().signature())}",
            versionName = first.app.versionName,
            versionCode = first.app.versionCode,
            minSdk = first.minSdk,
            targetSdk = first.targetSdk,
            profiles = observedProfiles,
            downloadProfiles = listOf(observedProfiles.first()),
            artifactCount = uniqueArtifacts.size,
            totalBytes = uniqueArtifacts.sumOf { it.size.coerceAtLeast(0) }
        )
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }

    private fun isUnsupportedProfile(exception: Exception): Boolean =
        generateSequence<Throwable>(exception) { it.cause }.any { cause ->
            cause.javaClass.name.endsWith("InternalException\$AppNotSupported") ||
                cause.javaClass.name.endsWith("InternalException\$EmptyDownloads") ||
                (cause is PureHttpClient.ProtocolHttpException && cause.status == 400) ||
                cause.message == "Google Play returned no APK files for this device"
        }

    private fun isAuthFailure(exception: Exception): Boolean =
        generateSequence<Throwable>(exception) { it.cause }.any { cause ->
            cause is PureHttpClient.ProtocolHttpException && cause.status in AUTH_FAILURE_CODES
        }

    /** Both Google Play and the anonymous token service answer a burst with a rate limit. */
    private fun isRateLimited(exception: Exception): Boolean =
        generateSequence<Throwable>(exception) { it.cause }.any { cause ->
            (cause is PureHttpClient.ProtocolHttpException && cause.status == RATE_LIMIT_STATUS) ||
                cause.message?.startsWith("Anonymous connection is rate limited") == true
        }

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
        val sourceProfileId: String
    )

    companion object {
        private const val TAG = "AuroraPure"
        const val DISPENSER_URL = "https://auroraoss.com/api/auth"
        private const val DELIVERY_OK = 1
        private const val DELIVERY_NOT_PURCHASED = 3
        private const val LANGUAGE_REQUEST_INTERVAL_MS = 100L
        private const val MIN_SUPPORTED_ANDROID_API = 21
        private const val LATEST_SUPPORTED_ANDROID_API = 36
        private const val MAX_SELECTED_PROFILES = 64
        // Google Play throttles a burst of anonymous sessions with HTTP 429, so this stays where it
        // is. The scan got faster by removing redundant work per probe, not by adding more probes
        // in flight — raising this to 8 measurably provoked rate limiting mid-scan.
        private const val DISCOVERY_PARALLELISM = 4
        private const val MAX_CACHED_SEARCHES = 24
        private const val MAX_CACHED_DETAILS = 48
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val RATE_LIMIT_BACKOFF_MS = 1_200L
        private const val RATE_LIMIT_JITTER_MS = 800L
        private const val RATE_LIMIT_STATUS = 429
        private val AUTH_FAILURE_CODES = setOf(401, 403)
    }
}
