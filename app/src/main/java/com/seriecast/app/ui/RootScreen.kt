package com.seriecast.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seriecast.app.R

@Composable
fun SerieCastRoot(viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var form by remember { mutableStateOf(AppViewModel.DownloadForm()) }
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    var captureUrl by rememberSaveable { mutableStateOf("") }

    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val current = message
        if (current != null) {
            // Long, not the 4 s default: these messages say what is left on disk after a cancel or a
            // failed remux, and the user has to be able to read it.
            snackbarHostState.showSnackbar(current, duration = SnackbarDuration.Long)
            viewModel.clearMessage()
        }
    }

    RequestNotificationPermission()

    if (captureOpen) {
        CaptureScreen(
            sniffer = viewModel.sniffer,
            guard = viewModel.navigationGuard,
            initialUrl = captureUrl,
            onClose = { captureOpen = false },
            onUse = { stream ->
                form = viewModel.consumeSnifferCandidate(stream)
                viewModel.clearProbe()
                captureOpen = false
                tab = 1
            },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_library)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(painterResource(R.drawable.ic_tab_download), contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_download)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_guide)) },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> LibraryScreen(viewModel = viewModel, onGoToDownload = { tab = 1 })
                1 -> DownloadScreen(
                    viewModel = viewModel,
                    form = form,
                    onFormChange = { form = it },
                    captureUrl = captureUrl,
                    onCaptureUrlChange = { captureUrl = it },
                    onOpenCapture = { captureOpen = true },
                )
                else -> GuideScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
