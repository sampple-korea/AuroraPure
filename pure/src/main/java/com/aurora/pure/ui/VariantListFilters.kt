/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryVariant

internal enum class VariantArchitectureFilter {
    ALL,
    UNIVERSAL,
    BIT_64,
    BIT_32,
    ARM_64,
    ARM_32,
    X86_64,
    X86
}

internal data class VariantListFilters(
    val versionCode: Long?,
    val architecture: VariantArchitectureFilter = VariantArchitectureFilter.ALL,
    val densityDpi: Int? = null
)

internal fun filterDeliveryVariants(
    variants: List<DeliveryVariant>,
    filters: VariantListFilters
): List<DeliveryVariant> = variants.filter { variant ->
    (filters.versionCode == null || variant.versionCode == filters.versionCode) &&
        variant.matches(filters.architecture) &&
        (filters.densityDpi == null || filters.densityDpi in variant.densityDpis)
}

internal fun groupDeliveryVariantsByNewestVersion(
    variants: List<DeliveryVariant>
): Map<Long, List<DeliveryVariant>> = variants
    .groupBy(DeliveryVariant::versionCode)
    .toSortedMap(reverseOrder())

private fun DeliveryVariant.matches(filter: VariantArchitectureFilter): Boolean = when (filter) {
    VariantArchitectureFilter.ALL -> true
    VariantArchitectureFilter.UNIVERSAL -> universal
    VariantArchitectureFilter.BIT_64 -> architectures.any { it.bitness == 64 }
    VariantArchitectureFilter.BIT_32 -> architectures.any { it.bitness == 32 }
    VariantArchitectureFilter.ARM_64 -> ArchitectureVariant.ARM_64 in architectures
    VariantArchitectureFilter.ARM_32 -> ArchitectureVariant.ARM_32 in architectures
    VariantArchitectureFilter.X86_64 -> ArchitectureVariant.X86_64 in architectures
    VariantArchitectureFilter.X86 -> ArchitectureVariant.X86 in architectures
}
