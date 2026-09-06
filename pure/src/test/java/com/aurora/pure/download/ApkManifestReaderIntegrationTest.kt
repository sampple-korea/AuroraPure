/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

import com.android.apksig.ApkVerifier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ApkManifestReaderIntegrationTest {
    @Test
    fun readsPackageVersionAndSplitIdentityFromExternalFixture() {
        val fixture = System.getenv(FIXTURE_ENV)?.let(::File)
        assumeTrue("Set $FIXTURE_ENV to run the signed APK integration fixture", fixture != null)
        requireNotNull(fixture)
        assumeTrue(fixture.isDirectory)

        val expectedPackage = requireNotNull(System.getenv(PACKAGE_ENV))
        val expectedVersion = requireNotNull(System.getenv(VERSION_ENV)).toLong()
        val expectedSplits = fixture.listFiles()
            .orEmpty()
            .filter { it.extension == "apk" }
            .associate { file ->
                file.name to if (file.name == "base.apk") null else file.name.removeSuffix(".apk")
            }
        assertTrue(expectedSplits.isNotEmpty())

        expectedSplits.forEach { (name, splitName) ->
            val apk = fixture.resolve(name)
            val identity = ApkManifestReader.read(apk)
            assertEquals(expectedPackage, identity.packageName)
            assertEquals(expectedVersion, identity.versionCode)
            assertEquals(splitName, identity.splitName)

            val signature = ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(23)
                .setMaxCheckedPlatformVersion(36)
                .build()
                .verify()
            assertTrue(
                "$name signature errors=${signature.errors}; warnings=${signature.warnings}",
                signature.isVerified
            )
        }
    }

    companion object {
        private const val FIXTURE_ENV = "AURORA_PURE_APK_FIXTURE_DIR"
        private const val PACKAGE_ENV = "AURORA_PURE_APK_FIXTURE_PACKAGE"
        private const val VERSION_ENV = "AURORA_PURE_APK_FIXTURE_VERSION"
    }
}
