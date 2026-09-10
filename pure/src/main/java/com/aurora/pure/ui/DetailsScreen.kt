/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aurora.pure.PureViewModel
import com.aurora.pure.R
import com.aurora.pure.data.AppSummary
import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryVariant
import com.aurora.pure.data.PureUiState
import com.aurora.pure.play.DeviceProfile

@Composable
internal fun DetailsScreen(state: PureUiState, viewModel: PureViewModel) {
    val app = state.selected
    if (app == null) {
        // Opening a package by link has no cached summary to paint, so this states what is being
        // loaded rather than leaving a bare spinner on an otherwise empty screen.
        Column(
            Modifier.fillMaxSize().padding(PureSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(PureSpacing.l, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.loading_app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.query.isNotBlank()) {
                Text(
                    state.query,
                    style = technicalTextStyle(MaterialTheme.typography.labelSmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        return
    }
    val variants = state.variants
    val resultKey = remember(variants) { variants.joinToString("|") { "${it.id}:${it.versionCode}" } }
    val latestVersionCode = remember(variants) { variants.maxOfOrNull(DeliveryVariant::versionCode) }
    var versionFilter by rememberSaveable(app.packageName) { mutableStateOf<Long?>(null) }
    var architectureFilterName by rememberSaveable(app.packageName) {
        mutableStateOf(VariantArchitectureFilter.ALL.name)
    }
    var densityFilter by rememberSaveable(app.packageName) { mutableStateOf<Int?>(null) }
    var versionFilterChosen by rememberSaveable(app.packageName) { mutableStateOf(false) }
    var expandedVariantId by rememberSaveable(app.packageName, resultKey) { mutableStateOf("") }
    var expandedVersionCodes by rememberSaveable(app.packageName) { mutableStateOf("") }

    // Results stream in while the scan runs, so the default "newest version" view follows the
    // newest version discovered so far. Once the user picks a version themselves it stays put —
    // a later discovery must not yank the list out from under them.
    LaunchedEffect(latestVersionCode, versionFilterChosen) {
        if (!versionFilterChosen) versionFilter = latestVersionCode
    }

    val architectureFilter = runCatching {
        VariantArchitectureFilter.valueOf(architectureFilterName)
    }.getOrDefault(VariantArchitectureFilter.ALL)
    val filters = VariantListFilters(versionFilter, architectureFilter, densityFilter)
    val visibleVariants = remember(variants, filters) { filterDeliveryVariants(variants, filters) }
    val variantsByVersion = remember(visibleVariants) {
        groupDeliveryVariantsByNewestVersion(visibleVariants)
    }
    val expandedVersions = remember(expandedVersionCodes) {
        expandedVersionCodes.split(',').mapNotNull(String::toLongOrNull).toSet()
    }
    val activeFilterCount = listOf(
        versionFilter != latestVersionCode,
        architectureFilter != VariantArchitectureFilter.ALL,
        densityFilter != null
    ).count { it }

    LaunchedEffect(visibleVariants, state.selectedVariantId) {
        if (state.selectedVariantId.isNotBlank() &&
            visibleVariants.none { it.id == state.selectedVariantId }
        ) {
            expandedVariantId = ""
            viewModel.clearVariantSelection()
        }
    }

    Column(Modifier.fillMaxSize()) {
        CompactAppHeader(app, onCopied = viewModel::notifyCopied)
        Box(Modifier.weight(1f)) {
            when {
                !app.isFree -> PureEmptyState(
                    icon = PureIcons.Warning,
                    title = stringResource(R.string.paid_unavailable),
                    description = stringResource(R.string.paid_unavailable_body),
                    modifier = Modifier.fillMaxSize()
                )

                variants.isEmpty() && state.discoveringVariants ->
                    DiscoveryProgressPanel(state, Modifier.fillMaxSize())

                variants.isEmpty() && state.discoveryFailed -> PureEmptyState(
                    icon = PureIcons.Warning,
                    title = stringResource(R.string.discovery_failed_title),
                    description = stringResource(R.string.discovery_failed_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::discoverVariants,
                    modifier = Modifier.fillMaxSize()
                )

                variants.isEmpty() -> PureEmptyState(
                    icon = PureIcons.Apps,
                    title = stringResource(R.string.discover_variants),
                    description = stringResource(R.string.discover_variants_body),
                    actionLabel = stringResource(R.string.action_start_scan),
                    onAction = viewModel::discoverVariants,
                    modifier = Modifier.fillMaxSize()
                )

                else -> VariantResults(
                    state = state,
                    viewModel = viewModel,
                    variants = variants,
                    visibleVariants = visibleVariants,
                    variantsByVersion = variantsByVersion,
                    versionFilter = versionFilter,
                    architectureFilter = architectureFilter,
                    densityFilter = densityFilter,
                    activeFilterCount = activeFilterCount,
                    expandedVersions = expandedVersions,
                    expandedVariantId = expandedVariantId,
                    onVersionSelected = {
                        versionFilterChosen = true
                        versionFilter = it
                    },
                    onArchitectureSelected = { architectureFilterName = it.name },
                    onDensitySelected = { densityFilter = it },
                    onResetFilters = {
                        versionFilterChosen = false
                        versionFilter = latestVersionCode
                        architectureFilterName = VariantArchitectureFilter.ALL.name
                        densityFilter = null
                    },
                    onToggleVersion = { code ->
                        val updated = if (code in expandedVersions) {
                            expandedVersions - code
                        } else {
                            expandedVersions + code
                        }
                        expandedVersionCodes = updated.sortedDescending().joinToString(",")
                    },
                    onToggleVariant = { id ->
                        viewModel.selectVariant(id)
                        expandedVariantId = if (expandedVariantId == id) "" else id
                    }
                )
            }
        }
        DownloadActionBar(state = state, app = app, onDownload = viewModel::prepareDownload)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactAppHeader(app: AppSummary, onCopied: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.copyWithFeedback(app.packageName, onCopied)
                }
            )
            .padding(horizontal = PureSpacing.gutter, vertical = PureSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(PureSpacing.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PureSpacing.xxs)) {
            Text(
                app.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = technicalTextStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (app.versionName.isNotBlank()) {
            Spacer(Modifier.width(PureSpacing.s))
            PureStatusBadge(
                label = app.versionName,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The scan's length is known before it starts, so it is reported as a real fraction. An
 * indeterminate bar for a scan that can run tens of seconds reads as a hang.
 */
@Composable
private fun DiscoveryProgressPanel(state: PureUiState, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(state.discovery.fraction, label = "discovery")
    Column(
        modifier.padding(PureSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(PureSpacing.m, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PurePanel(tonal = true, spacing = PureSpacing.m) {
            Text(
                stringResource(R.string.discovering_variants),
                style = MaterialTheme.typography.titleMedium
            )
            // Nothing has come back until the first path completes, so a determinate bar would sit
            // truthfully at zero for the opening stretch and read as a hang. An indeterminate bar
            // says "working, amount not yet known", which is what is actually true.
            if (state.discovery.pathsCompleted == 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round
                )
            }
            Text(
                if (state.discovery.pathsCompleted == 0) {
                    stringResource(R.string.discovery_progress_starting, state.discovery.totalPaths)
                } else {
                    stringResource(
                        R.string.discovery_progress_value,
                        state.discovery.pathsCompleted,
                        state.discovery.totalPaths.coerceAtLeast(1)
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val active = state.discovery.active
            Text(
                if (active.isEmpty()) {
                    stringResource(R.string.connecting_google_play)
                } else {
                    active.take(MAX_ACTIVE_PROBE_LINES).joinToString("\n") { profile ->
                        "${profile.primaryAbi} · ${profile.densityDpi} dpi · API ${profile.sdkVersion}"
                    }
                },
                style = technicalTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            stringResource(R.string.discovery_partial_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VariantResults(
    state: PureUiState,
    viewModel: PureViewModel,
    variants: List<DeliveryVariant>,
    visibleVariants: List<DeliveryVariant>,
    variantsByVersion: Map<Long, List<DeliveryVariant>>,
    versionFilter: Long?,
    architectureFilter: VariantArchitectureFilter,
    densityFilter: Int?,
    activeFilterCount: Int,
    expandedVersions: Set<Long>,
    expandedVariantId: String,
    onVersionSelected: (Long?) -> Unit,
    onArchitectureSelected: (VariantArchitectureFilter) -> Unit,
    onDensitySelected: (Int?) -> Unit,
    onResetFilters: () -> Unit,
    onToggleVersion: (Long) -> Unit,
    onToggleVariant: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // A thin live strip keeps the still-running scan visible without pushing the results the
        // user can already act on off the screen.
        AnimatedVisibility(visible = state.discoveringVariants) {
            if (state.discovery.pathsCompleted == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            } else {
                LinearProgressIndicator(
                    progress = { state.discovery.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
        // A throttled scan returns a real but partial matrix. Saying so is the honest option:
        // silently showing a subset would look like the complete answer.
        AnimatedVisibility(visible = state.discoveryIncomplete && !state.discoveringVariants) {
            IncompleteScanBanner(onRescan = viewModel::discoverVariants)
        }
        VariantFilterBar(
            variants = variants,
            visibleCount = visibleVariants.size,
            scanning = state.discoveringVariants,
            activeFilterCount = activeFilterCount,
            versionCode = versionFilter,
            architecture = architectureFilter,
            densityDpi = densityFilter,
            onVersionSelected = onVersionSelected,
            onArchitectureSelected = onArchitectureSelected,
            onDensitySelected = onDensitySelected,
            onResetFilters = onResetFilters,
            onRefresh = viewModel::discoverVariants
        )
        // The running scan is already reported by the strip above, and a pull-to-refresh restarts
        // the scan from its full-screen progress panel. Leaving the indicator latched on would hover
        // it over the first result for the whole scan instead of retracting after the gesture.
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = viewModel::discoverVariants,
            state = rememberPullToRefreshState(),
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = PureSpacing.l,
                    vertical = PureSpacing.m
                ),
                verticalArrangement = Arrangement.spacedBy(PureSpacing.s)
            ) {
                if (visibleVariants.isEmpty()) {
                    item {
                        PureEmptyState(
                            icon = PureIcons.Tune,
                            title = stringResource(R.string.no_filtered_variants),
                            description = stringResource(R.string.no_filtered_variants_body),
                            actionLabel = stringResource(R.string.filter_reset),
                            onAction = onResetFilters
                        )
                    }
                }
                variantsByVersion.forEach { (versionCode, versionVariants) ->
                    val sectionExpanded = versionFilter != null || versionCode in expandedVersions
                    item(key = "version-$versionCode") {
                        VersionSectionHeader(
                            versionName = versionVariants.first().versionName,
                            versionCode = versionCode,
                            resultCount = versionVariants.size,
                            expanded = sectionExpanded,
                            collapsible = versionFilter == null,
                            onToggle = { onToggleVersion(versionCode) }
                        )
                    }
                    if (sectionExpanded) {
                        items(versionVariants, key = DeliveryVariant::id) { variant ->
                            VariantCard(
                                variant = variant,
                                selected = variant.id == state.selectedVariantId,
                                expanded = variant.id == expandedVariantId,
                                onSelect = { viewModel.selectVariant(variant.id) },
                                onToggleExpanded = { onToggleVariant(variant.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncompleteScanBanner(onRescan: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PureSpacing.l, vertical = PureSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                PureIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(PureSpacing.s))
            Text(
                stringResource(R.string.discovery_incomplete),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(PureSpacing.s))
            Text(
                stringResource(R.string.action_rescan),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(onClick = onRescan)
                    .padding(horizontal = PureSpacing.s, vertical = PureSpacing.xs)
            )
        }
    }
}

private data class VariantFilterOption<T>(
    val value: T,
    val label: String,
    val compactLabel: String = label
)

@Composable
private fun VariantFilterBar(
    variants: List<DeliveryVariant>,
    visibleCount: Int,
    scanning: Boolean,
    activeFilterCount: Int,
    versionCode: Long?,
    architecture: VariantArchitectureFilter,
    densityDpi: Int?,
    onVersionSelected: (Long?) -> Unit,
    onArchitectureSelected: (VariantArchitectureFilter) -> Unit,
    onDensitySelected: (Int?) -> Unit,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit
) {
    val resources = LocalResources.current
    val allLabel = stringResource(R.string.filter_all)
    val universalLabel = stringResource(R.string.filter_universal)
    val bit64Label = stringResource(R.string.filter_64_bit)
    val bit32Label = stringResource(R.string.filter_32_bit)

    // These lists are derived purely from the result set. Rebuilding them on every recomposition,
    // including each progress tick during a scan, was pure allocation churn.
    val versions = remember(variants) { groupDeliveryVariantsByNewestVersion(variants) }
    val versionOptions = remember(versions, allLabel) {
        buildList {
            add(VariantFilterOption<Long?>(null, allLabel))
            versions.forEach { (code, matching) ->
                add(
                    VariantFilterOption<Long?>(
                        code,
                        resources.getString(
                            R.string.filter_version_value,
                            matching.first().versionName,
                            code.toString(),
                            matching.size
                        ),
                        matching.first().versionName
                    )
                )
            }
        }
    }
    val architectureOptions = remember(variants, allLabel) {
        val actual = variants.flatMap(DeliveryVariant::architectures).toSet()
        val labels = mapOf(
            VariantArchitectureFilter.ALL to allLabel,
            VariantArchitectureFilter.UNIVERSAL to universalLabel,
            VariantArchitectureFilter.BIT_64 to bit64Label,
            VariantArchitectureFilter.BIT_32 to bit32Label,
            VariantArchitectureFilter.ARM_64 to ArchitectureVariant.ARM_64.archiveDirectory,
            VariantArchitectureFilter.ARM_32 to ArchitectureVariant.ARM_32.archiveDirectory,
            VariantArchitectureFilter.X86_64 to ArchitectureVariant.X86_64.archiveDirectory,
            VariantArchitectureFilter.X86 to ArchitectureVariant.X86.archiveDirectory
        )
        buildList {
            add(VariantArchitectureFilter.ALL)
            if (variants.any(DeliveryVariant::universal)) add(VariantArchitectureFilter.UNIVERSAL)
            if (actual.any { it.bitness == 64 }) add(VariantArchitectureFilter.BIT_64)
            if (actual.any { it.bitness == 32 }) add(VariantArchitectureFilter.BIT_32)
            if (ArchitectureVariant.ARM_64 in actual) add(VariantArchitectureFilter.ARM_64)
            if (ArchitectureVariant.ARM_32 in actual) add(VariantArchitectureFilter.ARM_32)
            if (ArchitectureVariant.X86_64 in actual) add(VariantArchitectureFilter.X86_64)
            if (ArchitectureVariant.X86 in actual) add(VariantArchitectureFilter.X86)
        }.map { VariantFilterOption(it, labels.getValue(it)) }
    }
    val densityOptions = remember(variants, allLabel) {
        buildList {
            add(VariantFilterOption<Int?>(null, allLabel))
            variants.flatMap(DeliveryVariant::densityDpis).distinct().sorted().forEach { dpi ->
                add(
                    VariantFilterOption<Int?>(
                        dpi,
                        resources.getString(R.string.filter_dpi_value, dpi),
                        dpi.toString()
                    )
                )
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PureSpacing.l, vertical = PureSpacing.s),
            verticalArrangement = Arrangement.spacedBy(PureSpacing.s)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (scanning) {
                        stringResource(R.string.result_count_scanning, visibleCount)
                    } else {
                        stringResource(R.string.result_count, visibleCount)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (activeFilterCount > 0) {
                    Text(
                        stringResource(R.string.filter_reset),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(onClick = onResetFilters)
                            .padding(horizontal = PureSpacing.s, vertical = PureSpacing.xs)
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !scanning,
                    // A 40dp control sits under Android's 48dp minimum tap target.
                    modifier = Modifier.size(PureSpacing.touchTarget)
                ) {
                    Icon(PureIcons.Refresh, stringResource(R.string.refresh_variants))
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PureSpacing.s)
            ) {
                VariantFilterMenu(
                    category = stringResource(R.string.filter_version),
                    selected = versionCode,
                    options = versionOptions,
                    active = versionCode != versions.keys.firstOrNull(),
                    onSelected = onVersionSelected,
                    modifier = Modifier.weight(1f)
                )
                VariantFilterMenu(
                    category = stringResource(R.string.filter_architecture),
                    selected = architecture,
                    options = architectureOptions,
                    active = architecture != VariantArchitectureFilter.ALL,
                    onSelected = onArchitectureSelected,
                    modifier = Modifier.weight(1f)
                )
                VariantFilterMenu(
                    category = stringResource(R.string.filter_dpi),
                    selected = densityDpi,
                    options = densityOptions,
                    active = densityDpi != null,
                    onSelected = onDensitySelected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun <T> VariantFilterMenu(
    category: String,
    selected: T,
    options: List<VariantFilterOption<T>>,
    active: Boolean,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.compactLabel.orEmpty()
    Box(modifier) {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            label = {
                Column(Modifier.padding(vertical = PureSpacing.xxs)) {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        selectedLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = PureSpacing.touchTarget),
            trailingIcon = {
                Icon(PureIcons.ExpandMore, null, Modifier.size(18.dp))
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = {
                        if (option.value == selected) {
                            Icon(
                                PureIcons.Check,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    }
                )
            }
        }
    }
}

@Composable
private fun VersionSectionHeader(
    versionName: String,
    versionCode: Long,
    resultCount: Int,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit
) {
    val base = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall)
    Row(
        (if (collapsible) base.clickable(onClick = onToggle) else base)
            .padding(horizontal = PureSpacing.s, vertical = PureSpacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(
                R.string.version_group_summary,
                versionName,
                versionCode.toString(),
                resultCount
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (collapsible) {
            Icon(
                PureIcons.ExpandMore,
                stringResource(if (expanded) R.string.collapse_details else R.string.expand_details),
                Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
    }
}

@Composable
private fun VariantCard(
    variant: DeliveryVariant,
    selected: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val architectureSummary = remember(variant.id) {
        variant.architectures.joinToString(" + ") { it.archiveDirectory }
    }
    val densitySummary = if (variant.densityDpis.size <= 3) {
        variant.densityDpis.joinToString(", ", postfix = " dpi")
    } else {
        stringResource(
            R.string.compact_dpi_range,
            variant.densityDpis.first(),
            variant.densityDpis.last(),
            variant.densityDpis.size
        )
    }
    val observedTargets = remember(variant.id, expanded) {
        if (!expanded) {
            emptyMap()
        } else {
            variant.profiles
                .groupBy { it.variant to it.densityDpi }
                .toSortedMap(compareBy<Pair<ArchitectureVariant, Int>>({ it.first.ordinal }, { it.second }))
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggleExpanded()
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            Modifier.padding(
                start = PureSpacing.s,
                end = PureSpacing.m,
                top = PureSpacing.m,
                bottom = PureSpacing.m
            ),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(PureSpacing.xs))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PureSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.compact_variant_version,
                            variant.versionName,
                            variant.versionCode.toString()
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (variant.universal) {
                        PureStatusBadge(
                            label = stringResource(R.string.filter_universal),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(PureSpacing.xs))
                    }
                    Icon(
                        PureIcons.ExpandMore,
                        stringResource(
                            if (expanded) R.string.collapse_details else R.string.expand_details
                        ),
                        Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        architectureSummary,
                        style = technicalTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(PureSpacing.s))
                    Text(
                        densitySummary,
                        style = technicalTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                // The size drives the download decision, so it is promoted out of the details
                // panel and onto the collapsed row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.apk_count_size,
                            variant.artifactCount,
                            formatBytes(variant.totalBytes)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (expanded) {
                    HorizontalDivider(Modifier.padding(vertical = PureSpacing.s))
                    PureDetailRow(
                        stringResource(R.string.architecture),
                        architectureSummary,
                        monospace = true
                    )
                    PureDetailRow(
                        stringResource(R.string.screen_density),
                        variant.densityDpis.joinToString(", ", postfix = " dpi"),
                        monospace = true
                    )
                    PureDetailRow(
                        stringResource(R.string.minimum_android),
                        stringResource(
                            R.string.android_api_value,
                            DeviceProfile.androidRelease(variant.minSdk),
                            variant.minSdk
                        )
                    )
                    PureDetailRow(
                        stringResource(R.string.probed_android),
                        variant.testedSdkVersions.joinToString(", ") { "API $it" },
                        monospace = true
                    )
                    Spacer(Modifier.height(PureSpacing.xs))
                    PureSectionTitle(stringResource(R.string.actual_combinations))
                    for ((target, profiles) in observedTargets) {
                        val apiText = profiles
                            .map { it.sdkVersion }
                            .distinct()
                            .sortedDescending()
                            .joinToString(", ") { sdk ->
                                resources.getString(
                                    R.string.actual_combination_api,
                                    DeviceProfile.androidRelease(sdk),
                                    sdk
                                )
                            }
                        Text(
                            stringResource(
                                R.string.actual_combination_value,
                                target.first.archiveDirectory,
                                target.second,
                                apiText
                            ),
                            style = technicalTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadActionBar(
    state: PureUiState,
    app: AppSummary,
    onDownload: () -> Unit
) {
    val selectedVariant = state.selectedVariant
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PureSpacing.l, vertical = PureSpacing.m),
            verticalArrangement = Arrangement.spacedBy(PureSpacing.s)
        ) {
            Text(
                selectedVariant?.let { variant ->
                    stringResource(
                        R.string.selected_variant_summary,
                        variant.versionName,
                        variant.architectures.joinToString("+") { it.archiveDirectory },
                        variant.densityDpis.joinToString(",", postfix = " dpi")
                    )
                } ?: stringResource(
                    if (state.discoveringVariants) {
                        R.string.select_variant_scanning
                    } else {
                        R.string.select_variant
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = onDownload,
                enabled = app.isFree && !state.busy && selectedVariant != null,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(PureSpacing.s))
                    Text(stringResource(R.string.preparing_download))
                } else {
                    Icon(PureIcons.Download, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(PureSpacing.s))
                    // Knowing the size before committing avoids a surprise on a metered network.
                    Text(
                        selectedVariant?.let {
                            stringResource(R.string.download_with_size, formatBytes(it.totalBytes))
                        } ?: stringResource(R.string.download_action)
                    )
                }
            }
        }
    }
}

private const val MAX_ACTIVE_PROBE_LINES = 3
