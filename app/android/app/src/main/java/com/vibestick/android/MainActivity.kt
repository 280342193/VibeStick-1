package com.vibestick.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibestick.android.audio.AndroidPhoneAudioRecorder
import com.vibestick.android.service.BridgeMonitorService
import com.vibestick.android.ui.ConnectionSheet
import com.vibestick.android.ui.MainScreen
import com.vibestick.android.ui.MainViewModel
import com.vibestick.android.ui.MainViewModelFactory
import com.vibestick.android.ui.VibeStickTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val app = application as VibeStickApplication
        MainViewModelFactory(
            repository = app.repository,
            recorder = AndroidPhoneAudioRecorder(applicationContext),
        )
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        BridgeMonitorService.start(this)
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onMicrophonePermissionGranted()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startMonitorWithPermission()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            var showConnections by remember { mutableStateOf(false) }
            var token by remember { mutableStateOf("") }

            VibeStickTheme {
                MainScreen(
                    state = state,
                    onTextChange = viewModel::updateText,
                    onSend = viewModel::sendText,
                    onVoiceStart = ::startVoiceWithPermission,
                    onVoiceFinish = viewModel::finishVoice,
                    onVoiceCancel = viewModel::cancelVoice,
                    onOpenConnections = { showConnections = true },
                    onRescan = viewModel::rescan,
                )
                if (showConnections) {
                    ConnectionSheet(
                        state = state,
                        token = token,
                        onTokenChange = { token = it },
                        onDismiss = { showConnections = false },
                        onRescan = viewModel::rescan,
                        onSelectBridge = { candidate ->
                            viewModel.selectBridge(candidate)
                            showConnections = false
                        },
                        onSaveToken = {
                            viewModel.updateToken(token)
                            token = ""
                            showConnections = false
                        },
                    )
                }
            }
        }
    }

    private fun startMonitorWithPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            BridgeMonitorService.start(this)
        }
    }

    private fun startVoiceWithPermission() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startVoice()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
