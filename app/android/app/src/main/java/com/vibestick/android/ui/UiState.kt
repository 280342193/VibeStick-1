package com.vibestick.android.ui

import com.vibestick.android.data.AgentStatus
import com.vibestick.android.data.Alert
import com.vibestick.android.data.BridgeCandidate
import com.vibestick.android.data.BusyAction
import com.vibestick.android.data.ConnectionState

enum class ActionState {
    IDLE,
    RECORDING,
    UPLOADING,
    SENDING,
    PASTED,
    SENT,
    ERROR,
}

data class UiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val computerName: String = "",
    val providerName: String = "",
    val taskStatus: AgentStatus = AgentStatus.UNKNOWN,
    val alert: Alert = Alert(),
    val manualText: String = "",
    val amplitude: Float = 0f,
    val actionState: ActionState = ActionState.IDLE,
    val busyAction: BusyAction = BusyAction.NONE,
    val candidates: List<BridgeCandidate> = emptyList(),
    val statusMessage: String = "",
) {
    val isConnected: Boolean
        get() = connection is ConnectionState.Connected

    val isBusy: Boolean
        get() = busyAction != BusyAction.NONE || actionState in setOf(
            ActionState.RECORDING,
            ActionState.UPLOADING,
            ActionState.SENDING,
        )

    val canSendText: Boolean
        get() = isConnected && !isBusy && manualText.isNotBlank()

    val canStartVoice: Boolean
        get() = isConnected && !isBusy
}
