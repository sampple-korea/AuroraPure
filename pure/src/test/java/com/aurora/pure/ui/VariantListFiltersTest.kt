/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryProfile
import com.aurora.pure.data.DeliveryVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class VariantListFiltersTest {
    @Test
    fun groupsUseDescendingNumericVersionCodes() {
        val variants = listOf(
            variant("old", "99.0", 99, ArchitectureVariant.ARM_64, 480),
            variant("new-a", "2.0", 101, ArchitectureVariant.ARM_64, 320),
            variant("new-b", "2.0", 101, ArchitectureVariant.X86_64, 240),
            variant("middle", "100.0", 100, ArchitectureVariant.ARM_32, 160)
        )

        val grouped = groupDeliveryVariantsByNewestVersion(variants)

        assertEquals(listOf(101L, 100L, 99L), grouped.keys.toList())
        assertEquals(listOf("new-a", "new-b"), grouped.getValue(101).map { it.id })
    }

    @Test
    fun combinesVersionArchitectureAndDpiFilters() {
        val matching = variant("matching", "2.0", 20, ArchitectureVariant.ARM_64, 480)
        val variants = listOf(
            matching,
            variant("wrong-version", "1.0", 10, ArchitectureVariant.ARM_64, 480),
            variant("wrong-architecture", "2.0", 20, ArchitectureVariant.ARM_32, 480),
            variant("wrong-density", "2.0", 20, ArchitectureVariant.ARM_64, 320)
        )

        val filtered = filterDeliveryVariants(
            variants,
            VariantListFilters(
                versionCode = 20,
                architecture = VariantArchitectureFilter.BIT_64,
                densityDpi = 480
            )
        )

        assertEquals(listOf(matching), filtered)
    }

    @Test
    fun universalFilterReturnsOnlyUniversalCards() {
        val universal = variant(
            id = "universal",
            versionName = "3.0",
            versionCode = 30,
            architecture = ArchitectureVariant.ARM_64,
            densityDpi = 320,
            universal = true
        )
        val regular = variant("regular", "3.0", 30, ArchitectureVariant.ARM_64, 320)

        val filtered = filterDeliveryVariants(
            listOf(regular, universal),
            VariantListFilters(versionCode = null, architecture = VariantArchitectureFilter.UNIVERSAL)
        )

        assertEquals(listOf(universal), filtered)
    }

    private fun variant(
        id: String,
        versionName: String,
        versionCode: Long,
        architecture: ArchitectureVariant,
        densityDpi: Int,
        universal: Boolean = false
    ): DeliveryVariant {
        val profile = DeliveryProfile(
            variant = architecture,
            platforms = architecture.platforms,
            densityDpi = densityDpi,
            sdkVersion = 36
        )
        return DeliveryVariant(
            id = id,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = 29,
            targetSdk = 36,
            profiles = listOf(profile),
            downloadProfiles = listOf(profile),
            artifactCount = 1,
            totalBytes = 1024,
            aggregate = universal,
            universal = universal
        )
    }
}
