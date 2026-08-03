package com.vibestick.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.vibestick.android.R
import com.vibestick.android.data.AgentStatus
import com.vibestick.android.data.AlertType
import com.vibestick.android.data.ConnectionState
import kotlin.math.sqrt

@Composable
fun MainScreen(
    state: UiState,
    modifier: Modifier = Modifier,
    onTextChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onVoiceStart: () -> Unit = {},
    onVoiceFinish: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onOpenConnections: () -> Unit = {},
    onRescan: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp),
        ) {
            Header(
                state = state,
                onOpenConnections = onOpenConnections,
                onRescan = onRescan,
            )
            Spacer(modifier = Modifier.weight(1f))
            ActionMessage(state)
            ActionArea(
                state = state,
                onSend = onSend,
                onVoiceStart = onVoiceStart,
                onVoiceFinish = onVoiceFinish,
                onVoiceCancel = onVoiceCancel,
            )
            OutlinedTextField(
                value = state.manualText,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("manual_input"),
                enabled = !state.isBusy,
                label = { Text(stringResource(R.string.manual_input_label)) },
                placeholder = { Text(stringResource(R.string.manual_input_placeholder)) },
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(6.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (state.canSendText) onSend() },
                ),
            )
        }
    }
}

@Composable
private fun Header(
    state: UiState,
    onOpenConnections: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VibeStick",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenConnections) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.connection_settings),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(connectionColor(state.connection)),
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = connectionLabel(state.connection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRescan,
                enabled = state.busyAction == com.vibestick.android.data.BusyAction.NONE,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.rescan),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.computerName.ifBlank {
                        stringResource(R.string.no_computer)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.providerName.ifBlank {
                        stringResource(R.string.provider_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = taskStatusLabel(state.taskStatus),
                    style = MaterialTheme.typography.labelLarge,
                    color = taskStatusColor(state.taskStatus),
                )
                if (state.alert.isTerminal) {
                    Text(
                        text = alertLabel(state.alert.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = alertColor(state.alert.type),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionMessage(state: UiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = state.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.actionState == ActionState.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionArea(
    state: UiState,
    onSend: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceFinish: () -> Unit,
    onVoiceCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(208.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceButton(
            state = state,
            onVoiceStart = onVoiceStart,
            onVoiceFinish = onVoiceFinish,
            onVoiceCancel = onVoiceCancel,
        )
        Spacer(modifier = Modifier.width(18.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = state.canSendText,
            modifier = Modifier
                .size(58.dp)
                .testTag("send_button"),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = VibeGreen,
                contentColor = Color.White,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.send),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun VoiceButton(
    state: UiState,
    onVoiceStart: () -> Unit,
    onVoiceFinish: () -> Unit,
    onVoiceCancel: () -> Unit,
) {
    val isRecording = state.actionState == ActionState.RECORDING
    val isUploading = state.actionState == ActionState.UPLOADING
    val intensity = if (isRecording) sqrt(state.amplitude.coerceIn(0f, 1f)) else 0f
    val targetScale = if (isRecording) 1f + 0.18f * intensity else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(70),
        label = "voice-scale",
    )
    val transition = rememberInfiniteTransition(label = "voice-motion")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(170, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-phase",
    )
    val density = LocalDensity.current
    val circleColor by animateColorAsState(
        targetValue = if (state.canStartVoice || isRecording) {
            VibeBlue
        } else {
            VibeBlue.copy(alpha = 0.58f)
        },
        animationSpec = tween(160),
        label = "voice-color",
    )
    val latestCanStart by rememberUpdatedState(state.canStartVoice)
    val latestStart by rememberUpdatedState(onVoiceStart)
    val latestFinish by rememberUpdatedState(onVoiceFinish)
    val latestCancel by rememberUpdatedState(onVoiceCancel)
    val voiceDescription = stringResource(R.string.voice_input)
    val voiceStateDescription = if (isRecording) {
        stringResource(R.string.recording)
    } else {
        stringResource(R.string.voice_idle)
    }

    Box(
        modifier = Modifier.size(196.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isUploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(188.dp),
                color = VibeBlue,
                strokeWidth = 3.dp,
            )
        }
        Box(
            modifier = Modifier
                .size(172.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = with(density) { (phase * intensity * 4.dp.toPx()) }
                    translationY = with(density) { (-intensity * 2.dp.toPx()) }
                    rotationZ = phase * intensity * 1.2f
                }
                .clip(CircleShape)
                .background(circleColor)
                .testTag("voice_button")
                .semantics {
                    contentDescription = voiceDescription
                    stateDescription = voiceStateDescription
                    role = Role.Button
                    if (!latestCanStart && !isRecording) disabled()
                }
                .pointerInput(Unit) {
                    var started = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            if (latestCanStart) {
                                started = true
                                latestStart()
                            }
                        },
                        onDragEnd = {
                            if (started) latestFinish()
                            started = false
                        },
                        onDragCancel = {
                            if (started) latestCancel()
                            started = false
                        },
                        onDrag = { change, _ -> change.consume() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = if (state.canStartVoice || isRecording) {
                    Color.White
                } else {
                    Color.White.copy(alpha = 0.72f)
                },
            )
        }
    }
}

@Composable
private fun connectionColor(connection: ConnectionState): Color = when (connection) {
    is ConnectionState.Connected -> VibeGreen
    ConnectionState.Discovering, is ConnectionState.Connecting -> Color(0xFFE6A23C)
    is ConnectionState.SelectionRequired -> VibeBlue
    ConnectionState.Disconnected, is ConnectionState.InvalidToken, is ConnectionState.Offline ->
        MaterialTheme.colorScheme.error
}

@Composable
private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> stringResource(R.string.disconnected)
    ConnectionState.Discovering -> stringResource(R.string.discovering)
    is ConnectionState.Connecting -> stringResource(R.string.connecting, connection.candidate.name)
    is ConnectionState.Connected -> stringResource(R.string.connected, connection.candidate.name)
    is ConnectionState.SelectionRequired -> stringResource(
        R.string.devices_found,
        connection.candidateCount,
    )
    is ConnectionState.InvalidToken -> stringResource(R.string.invalid_token)
    is ConnectionState.Offline -> stringResource(R.string.offline)
}

@Composable
private fun taskStatusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.RUNNING -> VibeBlue
    AgentStatus.DONE -> VibeGreen
    AgentStatus.ERROR -> MaterialTheme.colorScheme.error
    AgentStatus.APPROVAL -> Color(0xFFE6A23C)
    AgentStatus.IDLE, AgentStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun taskStatusLabel(status: AgentStatus): String = when (status) {
    AgentStatus.UNKNOWN -> stringResource(R.string.status_unknown)
    AgentStatus.IDLE -> stringResource(R.string.status_idle)
    AgentStatus.RUNNING -> stringResource(R.string.status_running)
    AgentStatus.DONE -> stringResource(R.string.status_done)
    AgentStatus.ERROR -> stringResource(R.string.status_error)
    AgentStatus.APPROVAL -> stringResource(R.string.status_approval)
}

@Composable
private fun alertLabel(type: AlertType): String = when (type) {
    AlertType.NONE -> ""
    AlertType.DONE -> stringResource(R.string.alert_done)
    AlertType.ERROR -> stringResource(R.string.alert_error)
    AlertType.APPROVAL -> stringResource(R.string.alert_approval)
}

@Composable
private fun alertColor(type: AlertType): Color = when (type) {
    AlertType.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    AlertType.DONE -> VibeGreen
    AlertType.ERROR -> MaterialTheme.colorScheme.error
    AlertType.APPROVAL -> Color(0xFFE6A23C)
}
