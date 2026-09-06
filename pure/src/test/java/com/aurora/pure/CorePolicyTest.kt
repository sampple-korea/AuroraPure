/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure

import com.aurora.pure.data.ArtifactPlan
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryProfile
import com.aurora.pure.data.DensityChoice
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.download.ResumePolicy
import com.aurora.pure.network.DeliveryUrlPolicy
import com.aurora.pure.play.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePolicyTest {
    @Test
    fun deliveryPolicyAllowsOnlyHttpsGoogleHosts() {
        assertTrue(DeliveryUrlPolicy.isAllowed("https://play.googleapis.com/download"))
        assertTrue(DeliveryUrlPolicy.isAllowed("https://redirector.gvt1.com/file"))
        assertFalse(DeliveryUrlPolicy.isAllowed("http://play.googleapis.com/download"))
        assertFalse(DeliveryUrlPolicy.isAllowed("https://google.com.example.org/file"))
        assertFalse(DeliveryUrlPolicy.isAllowed("https://example.org/google.com/file"))
        assertFalse(DeliveryUrlPolicy.isAllowed("not a URL"))
    }

    @Test
    fun resumeRequiresAnExactContentRange() {
        assertEquals(
            ResumePolicy.Decision.APPEND,
            ResumePolicy.decide(4096, 206, "bytes 4096-8191/16384")
        )
        assertEquals(ResumePolicy.Decision.RESTART, ResumePolicy.decide(4096, 200, null))
        assertEquals(
            ResumePolicy.Decision.REJECT,
            ResumePolicy.decide(4096, 206, "bytes 0-8191/16384")
        )
        assertEquals(ResumePolicy.Decision.REJECT, ResumePolicy.decide(4096, 206, null))
        assertEquals(ResumePolicy.Decision.REJECT, ResumePolicy.decide(4096, 416, null))
    }

    @Test
    fun planFingerprintIgnoresEphemeralUrlButBindsDeviceAndFiles() {
        val original = plan()
        val refreshedUrl = original.copy(
            artifacts = original.artifacts.map { it.copy(url = "https://play.googleapis.com/new-token") }
        )
        assertEquals(original.fingerprint(), refreshedUrl.fingerprint())
        assertNotEquals(original.fingerprint(), original.copy(deviceDescription = "another device").fingerprint())
        assertNotEquals(original.fingerprint(), original.copy(hasAdditionalData = true).fingerprint())
        assertNotEquals(
            original.fingerprint(),
            original.copy(artifacts = original.artifacts.map { it.copy(size = it.size + 1) }).fingerprint()
        )
    }

    @Test
    fun artifactPathCannotEscapeTheArchive() {
        val artifact = plan().artifacts.single().copy(
            ownerPackage = "../../owner",
            name = "../../.."
        )
        assertFalse(artifact.relativePath.contains("../"))
        assertFalse(artifact.relativePath.endsWith("/.."))
    }

    @Test
    fun everyAuroraProfileLocaleIsRequested() {
        assertTrue(DeviceProfile.allPlayLocales.size > 150)
        assertTrue(DeviceProfile.allPlayLocales.containsAll(listOf("ko", "en_US", "ar", "zh_TW")))
        assertEquals(DeviceProfile.allPlayLocales.size, DeviceProfile.allPlayLocales.distinct().size)
    }

    @Test
    fun architectureProfilesAreSeparateAndFollowTheDeviceFamily() {
        val arm = DeviceProfile.deliveryProfiles(
            choice = ArchitectureChoice.BOTH,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            currentDensityDpi = 420,
            sdkVersions = listOf(35)
        )
        assertEquals(listOf(ArchitectureVariant.ARM_64, ArchitectureVariant.ARM_32), arm.map { it.variant })
        assertEquals(listOf("arm64-v8a"), arm[0].platforms)
        assertEquals(listOf("armeabi-v7a", "armeabi"), arm[1].platforms)

        val x86 = DeviceProfile.deliveryProfiles(
            choice = ArchitectureChoice.BOTH,
            supportedAbis = listOf("x86_64", "x86"),
            currentDensityDpi = 240,
            sdkVersions = listOf(34)
        )
        assertEquals(listOf("x86_64"), x86[0].platforms)
        assertEquals(listOf("x86"), x86[1].platforms)
    }

    @Test
    fun variantPathsCannotOverwriteEachOther() {
        val artifact = plan().artifacts.single()
        val otherVariant = artifact.copy(variant = ArchitectureVariant.ARM_32)
        assertNotEquals(artifact.relativePath, otherVariant.relativePath)
        assertTrue(artifact.relativePath.startsWith("profiles/arm64-v8a/420dpi/api35/"))
        assertTrue(otherVariant.relativePath.startsWith("profiles/armeabi-v7a/420dpi/api35/"))
    }

    @Test
    fun universalAllDensityProfilesCoverFourAbisAndSevenStandardBuckets() {
        val profiles = DeviceProfile.deliveryProfiles(
            choice = ArchitectureChoice.UNIVERSAL,
            densityChoice = DensityChoice.ALL,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            currentDensityDpi = 420,
            sdkVersions = listOf(35)
        )
        assertEquals(28, profiles.size)
        assertEquals(ArchitectureVariant.entries.toSet(), profiles.map { it.variant }.toSet())
        assertEquals(DensityChoice.STANDARD_DENSITIES.toSet(), profiles.map { it.densityDpi }.toSet())
        assertEquals(28, profiles.map { it.id }.distinct().size)
    }

    private fun plan() = DownloadPlan(
        id = "00000000-0000-4000-8000-000000000000",
        packageName = "com.example.app",
        displayName = "Example",
        versionName = "1.0",
        versionCode = 1,
        minSdk = 23,
        targetSdk = 35,
        checkedAt = 1,
        deviceDescription = "device-a",
        architectureChoice = ArchitectureChoice.BIT_64,
        densityChoice = DensityChoice.CURRENT,
        deliveryProfiles = listOf(
            DeliveryProfile(ArchitectureVariant.ARM_64, listOf("arm64-v8a"), 420, 35)
        ),
        requestedLocales = DeviceProfile.allPlayLocales,
        artifacts = listOf(
            ArtifactPlan(
                variant = ArchitectureVariant.ARM_64,
                densityDpi = 420,
                sdkVersion = 35,
                ownerPackage = "com.example.app",
                ownerVersionCode = 1,
                name = "base.apk",
                url = "https://play.googleapis.com/token",
                size = 123,
                type = "BASE",
                sha1 = "",
                sha256 = "abc",
                isDependency = false
            )
        ),
        hasAdditionalData = false
    )
}
