/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure

import com.aurora.pure.data.ArtifactPlan
import com.aurora.pure.data.DownloadPlan
import com.aurora.pure.download.ResumePolicy
import com.aurora.pure.network.DeliveryUrlPolicy
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

    private fun plan() = DownloadPlan(
        id = "00000000-0000-4000-8000-000000000000",
        packageName = "com.example.app",
        displayName = "Example",
        versionName = "1.0",
        versionCode = 1,
        checkedAt = 1,
        deviceDescription = "device-a",
        artifacts = listOf(
            ArtifactPlan(
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
