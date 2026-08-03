package com.vibestick.android.ui

import com.vibestick.android.audio.PhoneAudioRecorder
import com.vibestick.android.data.AgentStatus
import com.vibestick.android.data.BridgeCallResult
import com.vibestick.android.data.BridgeCandidate
import com.vibestick.android.data.BridgeFailure
import com.vibestick.android.data.BridgeState
import com.vibestick.android.data.BusyAction
import com.vibestick.android.data.ConnectionState
import com.vibestick.android.data.RecordingResult
import com.vibestick.android.data.VibeStickRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun voiceSuccessReachesPastedWithoutUsingManualSend() = runTest(dispatcher) {
        val repository = FakeVibeStickRepository()
        val recorder = FakePhoneAudioRecorder()
        val viewModel = MainViewModel(repository, recorder)
        advanceUntilIdle()

        viewModel.startVoice()
        advanceUntilIdle()
        viewModel.finishVoice()
        advanceUntilIdle()

        assertEquals(ActionState.PASTED, viewModel.uiState.value.actionState)
        assertEquals(1, repository.voiceSendCount)
        assertEquals(0, repository.textSendCount)
    }

    @Test
    fun manualSuccessClearsInput() = runTest(dispatcher) {
        val repository = FakeVibeStickRepository()
        val viewModel = MainViewModel(repository, FakePhoneAudioRecorder())
        advanceUntilIdle()
        viewModel.updateText("run tests")

        viewModel.sendText()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.manualText)
        assertEquals(ActionState.SENT, viewModel.uiState.value.actionState)
    }

    @Test
    fun manualFailureRetainsInput() = runTest(dispatcher) {
        val repository = FakeVibeStickRepository().apply {
            textResult = RecordingResult.failure(BridgeFailure.Protocol("paste failed"))
        }
        val viewModel = MainViewModel(repository, FakePhoneAudioRecorder())
        advanceUntilIdle()
        viewModel.updateText("keep this")

        viewModel.sendText()
        advanceUntilIdle()

        assertEquals("keep this", viewModel.uiState.value.manualText)
        assertEquals(ActionState.ERROR, viewModel.uiState.value.actionState)
    }

    @Test
    fun enterFailureExplainsThatTextWasAlreadyPasted() = runTest(dispatcher) {
        val repository = FakeVibeStickRepository().apply {
            textResult = RecordingResult.failure(
                failure = BridgeFailure.Network("connection closed"),
                status = "enter_failed",
            )
        }
        val viewModel = MainViewModel(repository, FakePhoneAudioRecorder())
        advanceUntilIdle()
        viewModel.updateText("keep this")

        viewModel.sendText()
        advanceUntilIdle()

        assertEquals("keep this", viewModel.uiState.value.manualText)
        assertEquals("文字已粘贴，Enter 失败；再次发送只重试 Enter", viewModel.uiState.value.statusMessage)
    }

    @Test
    fun permissionDenialIsRetryable() = runTest(dispatcher) {
        val viewModel = MainViewModel(
            FakeVibeStickRepository(),
            FakePhoneAudioRecorder(),
        )
        advanceUntilIdle()

        viewModel.onMicrophonePermissionDenied()
        assertEquals(ActionState.ERROR, viewModel.uiState.value.actionState)

        viewModel.startVoice()
        advanceUntilIdle()
        assertEquals(ActionState.RECORDING, viewModel.uiState.value.actionState)
    }

    @Test
    fun permissionGrantPromptsForANewLongPressWithoutStartingRecording() = runTest(dispatcher) {
        val recorder = FakePhoneAudioRecorder()
        val viewModel = MainViewModel(FakeVibeStickRepository(), recorder)
        advanceUntilIdle()

        viewModel.onMicrophonePermissionGranted()

        assertEquals(ActionState.IDLE, viewModel.uiState.value.actionState)
        assertEquals("麦克风权限已开启，请再次长按录音", viewModel.uiState.value.statusMessage)
        assertEquals(0, recorder.startCount)
    }

    @Test
    fun secondManualSendIsIgnoredWhileFirstIsBusy() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeVibeStickRepository(textGate = gate)
        val viewModel = MainViewModel(repository, FakePhoneAudioRecorder())
        advanceUntilIdle()
        viewModel.updateText("once")

        viewModel.sendText()
        runCurrent()
        viewModel.sendText()
        runCurrent()

        assertEquals(1, repository.textSendCount)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.manualText.isEmpty())
    }
}

private class FakePhoneAudioRecorder : PhoneAudioRecorder {
    var startCount = 0

    override suspend fun start(onAmplitude: (Float) -> Unit): Result<Unit> {
        startCount += 1
        onAmplitude(0.6f)
        return Result.success(Unit)
    }

    override suspend fun stop(): Result<ByteArray> = Result.success(ByteArray(22_400))

    override fun cancel() = Unit
}

private class FakeVibeStickRepository(
    private val textGate: CompletableDeferred<Unit>? = null,
) : VibeStickRepository {
    private val candidate = BridgeCandidate("192.168.1.20", 8765, "Desk", "0.1.5")

    override val connection: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Connected(candidate))
    override val candidates: StateFlow<List<BridgeCandidate>> = MutableStateFlow(listOf(candidate))
    override val bridgeState: StateFlow<BridgeState?> = MutableStateFlow(
        BridgeState(
            computerName = "Desk",
            activeProvider = "codex",
            codexStatus = AgentStatus.IDLE,
        ),
    )
    override val busyAction: StateFlow<BusyAction> = MutableStateFlow(BusyAction.NONE)

    var textResult: RecordingResult = RecordingResult.success(status = "enter_sent")
    var voiceResult: RecordingResult = RecordingResult.success(status = "pasted")
    var textSendCount = 0
    var voiceSendCount = 0

    override suspend fun discover(): BridgeCallResult<List<BridgeCandidate>> =
        BridgeCallResult.Success(candidates.value)

    override suspend fun selectBridge(candidate: BridgeCandidate): BridgeCallResult<BridgeState> =
        BridgeCallResult.Success(requireNotNull(bridgeState.value))

    override suspend fun refreshState(): BridgeCallResult<BridgeState> =
        BridgeCallResult.Success(requireNotNull(bridgeState.value))

    override suspend fun sendText(text: String): RecordingResult {
        textSendCount += 1
        textGate?.await()
        return textResult
    }

    override suspend fun sendVoice(pcm: ByteArray): RecordingResult {
        voiceSendCount += 1
        return voiceResult
    }

    override fun updateToken(token: String) = Unit

    override fun rememberedBridge(): BridgeCandidate = candidate
}
