/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CliArtifactIntegrationTest {
    @Test
    fun `real APK fixture exports an APK-only verified apks set`() {
        val configured = System.getenv("AURORA_PURE_CLI_FIXTURE_DIR").orEmpty()
        assumeTrue("Set AURORA_PURE_CLI_FIXTURE_DIR for the release integration test", configured.isNotBlank())
        val root = Path.of(configured).toAbsolutePath().normalize()
        assumeTrue(Files.isDirectory(root))

        val files = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk", true) }
                .sorted()
                .toList()
        }
        assertTrue(files.isNotEmpty())
        val artifacts = files.map { path ->
            val identity = ApkIntrospection.manifest(path.toFile())
            val relative = root.relativize(path).toString().replace('\\', '/')
            val abi = when {
                "32bit" in relative || "/x86/" in relative -> AbiVariant.X86
                else -> AbiVariant.X86_64
            }
            Artifact(
                profile = DeliveryProfile(abi, 480, 36),
                ownerPackage = identity.packageName,
                ownerVersionCode = identity.versionCode,
                name = identity.splitName?.let { "$it.apk" } ?: "base.apk",
                url = "https://play.googleapis.com/release-fixture",
                size = Files.size(path),
                type = if (identity.splitName == null) "BASE" else "SPLIT",
                sha1 = "",
                sha256 = ArtifactCache.digest(path),
                dependency = false,
                locales = identity.splitName?.removePrefix("config.")?.let(::listOf).orEmpty()
            )
        }
        val first = ApkIntrospection.manifest(files.first().toFile())
        val profiles = artifacts.map(Artifact::profile).distinctBy(DeliveryProfile::id)
        val plan = DownloadPlan(
            packageName = first.packageName,
            name = first.packageName,
            versionName = "fixture",
            versionCode = first.versionCode,
            minSdk = 21,
            targetSdk = 36,
            checkedAt = 0,
            architectureMode = ArchitectureMode.BOTH,
            densityMode = DensityMode.parse("480"),
            profiles = profiles,
            requestedLocales = artifacts.flatMap(Artifact::locales).distinct(),
            artifacts = artifacts,
            hasAdditionalData = false
        )
        val byIdentity = artifacts.zip(files).associate { (artifact, path) ->
            requireNotNull(artifact.identity()) to path
        }
        val temporary = Files.createTempDirectory("aurora-pure-cli-export-")
        try {
            val engine = DownloadEngine(
                cacheRoot = temporary.resolve("cache"),
                http = CliHttpClient(),
                cachedArtifact = { byIdentity[it.identity()] }
            )
            val result = runBlocking { engine.download(plan, temporary.resolve("output")) }
            assertTrue(result.path.fileName.toString().endsWith(".apks"))
            assertFalse(result.path.fileName.toString().contains("all-language", true))
            ZipFile(result.path.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(plan.uniqueArtifacts.size, names.size)
                assertTrue(names.all { it.endsWith(".apk", true) && '/' !in it && '\\' !in it })
                assertFalse(names.any { it.endsWith(".json", true) || it.endsWith(".txt", true) })
            }
            val verified = ArchiveVerifier.verify(result.path)
            assertEquals(plan.uniqueArtifacts.size, verified.apkCount)
            assertEquals(listOf(first.versionCode), verified.packages[first.packageName])
        } finally {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
