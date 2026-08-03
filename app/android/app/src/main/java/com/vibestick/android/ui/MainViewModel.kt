package com.vibestick.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibestick.android.audio.AudioRecordingException
import com.vibestick.android.audio.PhoneAudioRecorder
import com.vibestick.android.data.AgentStatus
import com.vibestick.android.data.BridgeCallResult
import com.vibestick.android.data.BridgeCandidate
import com.vibestick.android.data.BridgeFailure
import com.vibestick.android.data.BridgeState
import com.vibestick.android.data.ConnectionState
import com.vibestick.android.data.RecordingResult
import com.vibestick.android.data.VibeStickRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: VibeStickRepository,
    private val recorder: PhoneAudioRecorder,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var voiceStartJob: Job? = null
    private var voiceFinishJob: Job? = null
    private var sendJob: Job? = null
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null

    init {
        collectRepositoryState()
        rescan()
    }

    fun updateText(text: String) {
        if (_uiState.value.actionState == ActionState.SENDING) return
        _uiState.update { it.copy(manualText = text) }
    }

    fun startVoice() {
        val state = _uiState.value
        if (!state.isConnected || state.isBusy || voiceStartJob?.isActive == true) return
        _uiState.update {
            it.copy(
                actionState = ActionState.RECORDING,
                amplitude = 0f,
                statusMessage = "正在录音",
            )
        }
        voiceStartJob = viewModelScope.launch {
            val result = recorder.start { amplitude ->
                _uiState.update { current ->
                    if (current.actionState == ActionState.RECORDING) {
                        current.copy(amplitude = amplitude.coerceIn(0f, 1f))
                    } else {
                        current
                    }
                }
            }
            result.exceptionOrNull()?.let { error ->
                _uiState.update {
                    it.copy(
                        actionState = ActionState.ERROR,
                        amplitude = 0f,
                        statusMessage = audioErrorMessage(error),
                    )
                }
            }
        }
    }

    fun finishVoice() {
        if (_uiState.value.actionState != ActionState.RECORDING) return
        if (voiceFinishJob?.isActive == true) return
        voiceFinishJob = viewModelScope.launch {
            voiceStartJob?.join()
            if (_uiState.value.actionState != ActionState.RECORDING) return@launch
            _uiState.update {
                it.copy(
                    actionState = ActionState.UPLOADING,
                    amplitude = 0f,
                    statusMessage = "正在上传并识别",
                )
            }
            val audio = recorder.stop()
            val pcm = audio.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        actionState = ActionState.ERROR,
                        statusMessage = audioErrorMessage(error),
                    )
                }
                return@launch
            }
            applyVoiceResult(repository.sendVoice(pcm))
        }
    }

    fun cancelVoice() {
        if (_uiState.value.actionState != ActionState.RECORDING) return
        voiceStartJob?.cancel()
        recorder.cancel()
        _uiState.update {
            it.copy(
                actionState = ActionState.IDLE,
                amplitude = 0f,
                statusMessage = "",
            )
        }
    }

    fun onMicrophonePermissionDenied() {
        _uiState.update {
            it.copy(
                actionState = ActionState.ERROR,
                amplitude = 0f,
                statusMessage = "未获得麦克风权限",
            )
        }
    }

    fun onMicrophonePermissionGranted() {
        _uiState.update {
            it.copy(
                actionState = ActionState.IDLE,
                amplitude = 0f,
                statusMessage = "麦克风权限已开启，请再次长按录音",
            )
        }
    }

    fun sendText() {
        val snapshot = _uiState.value
        if (!snapshot.canSendText || sendJob?.isActive == true) return
        val text = snapshot.manualText
        sendJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    actionState = ActionState.SENDING,
                    statusMessage = "正在发送",
                )
            }
            val result = repository.sendText(text)
            if (result.isSuccess) {
                _uiState.update { current ->
                    current.copy(
                        manualText = if (current.manualText == text) "" else current.manualText,
                        actionState = ActionState.SENT,
                        statusMessage = "已发送",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        actionState = ActionState.ERROR,
                        statusMessage = sendFailureMessage(result),
                    )
                }
            }
        }
    }

    fun rescan() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "正在搜索局域网电脑") }
            when (val result = repository.discover()) {
                is BridgeCallResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            actionState = ActionState.ERROR,
                            statusMessage = failureMessage(result.failure),
                        )
                    }
                }
                is BridgeCallResult.Success -> {
                    if (result.value.isEmpty()) {
                        _uiState.update { it.copy(statusMessage = "未发现电脑") }
                    }
                }
            }
        }
    }

    fun selectBridge(candidate: BridgeCandidate) {
        if (connectionJob?.isActive == true) return
        connectionJob = viewModelScope.launch {
            when (val result = repository.selectBridge(candidate)) {
                is BridgeCallResult.Success -> Unit
                is BridgeCallResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            actionState = ActionState.ERROR,
                            statusMessage = failureMessage(result.failure),
                        )
                    }
                }
            }
        }
    }

    fun updateToken(token: String) {
        repository.updateToken(token)
        repository.rememberedBridge()?.let(::selectBridge) ?: rescan()
    }

    override fun onCleared() {
        recorder.cancel()
        super.onCleared()
    }

    private fun collectRepositoryState() {
        viewModelScope.launch {
            repository.connection.collect { connection ->
                _uiState.update {
                    it.copy(
                        connection = connection,
                        statusMessage = connectionMessage(connection, it.statusMessage),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.candidates.collect { candidates ->
                _uiState.update { it.copy(candidates = candidates) }
            }
        }
        viewModelScope.launch {
            repository.busyAction.collect { busy ->
                _uiState.update { it.copy(busyAction = busy) }
            }
        }
        viewModelScope.launch {
            repository.bridgeState.collect { state ->
                if (state != null) applyBridgeState(state)
            }
        }
    }

    private fun applyBridgeState(state: BridgeState) {
        val providerStatus = state.provider.status.takeUnless { it == AgentStatus.UNKNOWN }
            ?: state.codexStatus
        _uiState.update {
            it.copy(
                computerName = state.computerName,
                providerName = state.provider.displayName.ifBlank { state.activeProvider },
                taskStatus = providerStatus,
                alert = state.alert,
            )
        }
    }

    private fun applyVoiceResult(result: RecordingResult) {
        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    actionState = ActionState.PASTED,
                    statusMessage = "已粘贴到电脑",
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    actionState = ActionState.ERROR,
                    statusMessage = failureMessage(result.failure),
                )
            }
        }
    }

    private fun connectionMessage(
        connection: ConnectionState,
        currentMessage: String,
    ): String = when (connection) {
        ConnectionState.Disconnected -> "未连接"
        ConnectionState.Discovering -> "正在搜索局域网电脑"
        is ConnectionState.Connecting -> "正在连接 ${connection.candidate.name}"
        is ConnectionState.Connected -> {
            if (_uiState.value.actionState == ActionState.IDLE) "已连接" else currentMessage
        }
        is ConnectionState.SelectionRequired -> "发现 ${connection.candidateCount} 台电脑"
        is ConnectionState.InvalidToken -> "Bridge Token 无效"
        is ConnectionState.Offline -> "电脑离线"
    }

    private fun audioErrorMessage(error: Throwable): String = when (error) {
        is AudioRecordingException.PermissionDenied -> "未获得麦克风权限"
        is AudioRecordingException.Empty -> "没有录到声音"
        is AudioRecordingException.TooShort -> "录音时间太短"
        is AudioRecordingException.Initialization -> "麦克风不可用"
        else -> error.message ?: "录音失败"
    }

    private fun failureMessage(failure: BridgeFailure?): String = when (failure) {
        null -> "操作失败"
        is BridgeFailure.Http -> if (failure.statusCode == 401) {
            "Bridge Token 无效"
        } else {
            failure.message
        }
        is BridgeFailure.Network -> "电脑连接失败"
        is BridgeFailure.Busy -> "正在处理上一项操作"
        else -> failure.message
    }

    private fun sendFailureMessage(result: RecordingResult): String = when (result.status) {
        "enter_failed" -> "文字已粘贴，Enter 失败；再次发送只重试 Enter"
        "enter_retry_required" -> "上一条文字已粘贴，请恢复原文字后重试 Enter"
        else -> failureMessage(result.failure)
    }
}

class MainViewModelFactory(
    private val repository: VibeStickRepository,
    private val recorder: PhoneAudioRecorder,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MainViewModel::class.java))
        return MainViewModel(repository, recorder) as T
    }
}
