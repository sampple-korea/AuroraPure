/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ProcessLifecycleOwner
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.ui.AuroraPureApp
import com.aurora.pure.ui.AuroraPureTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), DefaultLifecycleObserver {
    private val viewModel: PureViewModel by viewModels()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        viewModel.setCustomFolder(uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super<ComponentActivity>.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        handleIntent(intent)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            // The in-app light/dark choice can disagree with the system setting. Without this the
            // window kept the system's bar-icon colour, leaving the status bar unreadable whenever
            // the two differed.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            val downloading = state.records.any { it.status == TaskStatus.DOWNLOADING }
            DisposableEffect(state.keepScreenOn, downloading) {
                if (state.keepScreenOn && downloading) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            AuroraPureTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
                AuroraPureApp(
                    state = state,
                    viewModel = viewModel,
                    onChooseFolder = { folderPicker.launch(null) },
                    onShare = ::share,
                    onOpen = ::openSavedFile,
                    onOpenUrl = ::openUrl
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart(owner: LifecycleOwner) {
        viewModel.onForegroundChanged(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        viewModel.onForegroundChanged(false)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        super<ComponentActivity>.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        text?.takeIf(String::isNotBlank)?.let(viewModel::acceptSharedText)
    }

    private fun mimeTypeFor(record: DownloadRecord): String =
        if (record.outputName.endsWith(".apk", ignoreCase = true)) {
            "application/vnd.android.package-archive"
        } else {
            "application/zip"
        }

    private fun share(record: DownloadRecord) {
        // Confirming the file still exists touches the content resolver, so it runs off the main
        // thread and the intent is only built once the check comes back.
        lifecycleScope.launch {
            if (!viewModel.verifyOutput(record)) return@launch
            val uri = record.outputUri.toUri()
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeFor(record)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(record.outputName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(share, getString(R.string.share))) }
        }
    }

    private fun openSavedFile(record: DownloadRecord) {
        lifecycleScope.launch {
            if (!viewModel.verifyOutput(record)) return@launch
            val uri = record.outputUri.toUri()
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(record))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Not every device has a handler for an .apks archive, so fall back to the chooser
            // rather than failing silently.
            val opened = runCatching { startActivity(view); true }.getOrDefault(false)
            if (!opened) {
                runCatching {
                    startActivity(Intent.createChooser(view, getString(R.string.action_open)))
                }
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
}
