/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aurora.pure.data.DownloadRecord
import com.aurora.pure.data.TaskStatus
import com.aurora.pure.ui.AuroraPureApp
import com.aurora.pure.ui.AuroraPureTheme

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
            val downloading = state.records.any { it.status == TaskStatus.DOWNLOADING }
            DisposableEffect(state.keepScreenOn, downloading) {
                if (state.keepScreenOn && downloading) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            AuroraPureTheme(state.themeMode) {
                AuroraPureApp(
                    state = state,
                    viewModel = viewModel,
                    onChooseFolder = { folderPicker.launch(null) },
                    onShare = ::share
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

    private fun share(record: DownloadRecord) {
        if (!viewModel.verifyOutput(record)) return
        val uri = Uri.parse(record.outputUri)
        val mime = if (record.outputName.endsWith(".apk", true)) {
            "application/vnd.android.package-archive"
        } else {
            "application/zip"
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(record.outputName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share)))
    }
}
