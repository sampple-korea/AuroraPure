/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aurora.pure.PureViewModel
import com.aurora.pure.R
import com.aurora.pure.data.AppSummary
import com.aurora.pure.data.PureUiState

@Composable
internal fun SearchScreen(state: PureUiState, viewModel: PureViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(
                start = PureSpacing.gutter,
                end = PureSpacing.gutter,
                top = PureSpacing.s,
                bottom = PureSpacing.m
            ),
            verticalArrangement = Arrangement.spacedBy(PureSpacing.m)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                placeholder = {
                    Text(
                        stringResource(R.string.search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(PureIcons.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(PureIcons.Close, stringResource(R.string.action_clear_query))
                        }
                    }
                },
                // Submitting from the keyboard is the expected gesture for a search field; the
                // separate Search button used to be the only way to run a query.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        viewModel.submitSearch()
                    }
                )
            )
            ClipboardSuggestion(state, viewModel)
            RecentQueries(state, viewModel, keyboardHide = { keyboard?.hide() })
        }

        when {
            state.searching -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.results.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PureSpacing.gutter,
                    end = PureSpacing.gutter,
                    bottom = PureSpacing.xl
                ),
                verticalArrangement = Arrangement.spacedBy(PureSpacing.s)
            ) {
                items(state.results, key = AppSummary::packageName) { app ->
                    AppResultCard(
                        app = app,
                        onClick = { viewModel.openDetails(app.packageName) },
                        onCopied = viewModel::notifyCopied
                    )
                }
            }

            state.searched -> PureEmptyState(
                icon = PureIcons.Search,
                title = stringResource(R.string.empty_no_results_title),
                description = stringResource(R.string.empty_no_results_body),
                modifier = Modifier.fillMaxSize()
            )

            else -> PureEmptyState(
                icon = PureIcons.Apps,
                title = stringResource(R.string.empty_search_title),
                description = stringResource(R.string.empty_search_body),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A blind "Paste" button made the user guess whether the clipboard held anything useful. Only the
 * clip description is inspected while the screen is idle — reading the text itself would fire
 * Android's paste notification on every keystroke — and the content is read once, on the tap.
 */
@Composable
private fun ClipboardSuggestion(state: PureUiState, viewModel: PureViewModel) {
    val context = LocalContext.current
    var available by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.query.isEmpty()) {
        available = state.query.isEmpty() && context.clipboardHasText()
    }
    if (!available || dismissed || state.query.isNotEmpty()) return
    AssistChip(
        onClick = {
            dismissed = true
            val text = context.readClipboardText().take(MAX_CLIPBOARD_LENGTH)
            if (text.isBlank()) return@AssistChip
            val packageName = PureViewModel.parsePackageName(text)
            if (packageName != null) {
                viewModel.setQuery(packageName)
                viewModel.openDetails(packageName)
            } else {
                viewModel.setQuery(text)
            }
        },
        leadingIcon = { Icon(PureIcons.Copy, contentDescription = null, Modifier.size(18.dp)) },
        label = {
            Text(
                stringResource(R.string.clipboard_suggestion),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun RecentQueries(
    state: PureUiState,
    viewModel: PureViewModel,
    keyboardHide: () -> Unit
) {
    if (state.recentQueries.isEmpty() || state.results.isNotEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(PureSpacing.s)) {
        items(state.recentQueries, key = { it }) { query ->
            InputChip(
                selected = false,
                onClick = {
                    keyboardHide()
                    viewModel.submitSearch(query)
                },
                label = { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        PureIcons.Close,
                        contentDescription = stringResource(R.string.action_remove_recent),
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .clickableNoRipple { viewModel.removeRecentQuery(query) }
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppResultCard(app: AppSummary, onClick: () -> Unit, onCopied: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                // Copying the package name is the one thing a user reliably wants out of a result
                // row without opening it.
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.copyWithFeedback(app.packageName, onCopied)
                }
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.padding(PureSpacing.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = app.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(PureSpacing.m))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PureSpacing.xxs)
            ) {
                Text(
                    app.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.developerName.isNotBlank()) {
                    Text(
                        app.developerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    app.packageName,
                    style = technicalTextStyle(MaterialTheme.typography.labelSmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!app.isFree) {
                Spacer(Modifier.width(PureSpacing.s))
                PureStatusBadge(
                    label = stringResource(R.string.paid_short),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                Spacer(Modifier.width(PureSpacing.s))
                Icon(
                    PureIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(-90f)
                )
            }
        }
    }
}

private const val MAX_CLIPBOARD_LENGTH = 512
