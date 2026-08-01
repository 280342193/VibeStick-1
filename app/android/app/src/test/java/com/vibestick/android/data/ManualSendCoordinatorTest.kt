package com.vibestick.android.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSendCoordinatorTest {
    @Test
    fun manualSendPastesThenPressesEnter() = runTest {
        val fake = FakeBridgeOperations()

        val result = SendCoordinator(fake).sendText("run tests")

        assertTrue(result.isSuccess)
        assertEquals(listOf("start", "stop:text=run tests", "enter"), fake.calls)
    }

    @Test
    fun voiceUploadNeverPressesEnter() = runTest {
        val fake = FakeBridgeOperations()

        val result = SendCoordinator(fake).sendVoice(byteArrayOf(1, 2))

        assertTrue(result.isSuccess)
        assertEquals(listOf("start", "upload", "stop:voice"), fake.calls)
        assertEquals(fake.startedSessionId, fake.uploadedSessionId)
        assertFalse(fake.enterCalled)
    }

    @Test
    fun blankManualTextIsRejectedWithoutNetworkCalls() = runTest {
        val fake = FakeBridgeOperations()

        val result = SendCoordinator(fake).sendText("  \n")

        assertFalse(result.isSuccess)
        assertTrue(result.failure is BridgeFailure.Validation)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun voiceUploadFailureStopsBeforeTranscription() = runTest {
        val fake = FakeBridgeOperations(
            uploadResult = RecordingResult.failure(BridgeFailure.Network("upload failed")),
        )

        val result = SendCoordinator(fake).sendVoice(byteArrayOf(1, 2))

        assertFalse(result.isSuccess)
        assertEquals(listOf("start", "upload"), fake.calls)
        assertFalse(fake.enterCalled)
    }

    @Test
    fun pasteFailureDoesNotPressEnter() = runTest {
        val fake = FakeBridgeOperations(
            stopResult = RecordingResult.failure(BridgeFailure.Protocol("paste failed")),
        )

        val result = SendCoordinator(fake).sendText("keep this")

        assertFalse(result.isSuccess)
        assertEquals(listOf("start", "stop:text=keep this"), fake.calls)
        assertFalse(fake.enterCalled)
    }

    @Test
    fun enterFailureIsReturnedAfterSuccessfulPaste() = runTest {
        val fake = FakeBridgeOperations(
            enterResult = RecordingResult.failure(BridgeFailure.Protocol("enter failed")),
        )

        val result = SendCoordinator(fake).sendText("keep this")

        assertFalse(result.isSuccess)
        assertEquals(listOf("start", "stop:text=keep this", "enter"), fake.calls)
    }

    @Test
    fun retryAfterEnterFailureOnlyPressesEnterAgain() = runTest {
        val fake = FakeBridgeOperations(
            enterResult = RecordingResult.failure(BridgeFailure.Protocol("enter failed")),
        )
        val coordinator = SendCoordinator(fake)

        val first = coordinator.sendText("keep this")
        fake.enterResult = RecordingResult.success(status = "enter_sent")
        val retry = coordinator.sendText("keep this")

        assertFalse(first.isSuccess)
        assertTrue(retry.isSuccess)
        assertEquals(
            listOf("start", "stop:text=keep this", "enter", "enter"),
            fake.calls,
        )
    }

    @Test
    fun differentTextIsRejectedWhilePreviousPasteAwaitsEnter() = runTest {
        val fake = FakeBridgeOperations(
            enterResult = RecordingResult.failure(BridgeFailure.Protocol("enter failed")),
        )
        val coordinator = SendCoordinator(fake)
        coordinator.sendText("first")

        val result = coordinator.sendText("second")

        assertFalse(result.isSuccess)
        assertTrue(result.failure is BridgeFailure.Validation)
        assertEquals(listOf("start", "stop:text=first", "enter"), fake.calls)
    }

    @Test
    fun voiceIsRejectedWhilePreviousPasteAwaitsEnter() = runTest {
        val fake = FakeBridgeOperations(
            enterResult = RecordingResult.failure(BridgeFailure.Protocol("enter failed")),
        )
        val coordinator = SendCoordinator(fake)
        coordinator.sendText("first")

        val result = coordinator.sendVoice(byteArrayOf(1, 2))

        assertFalse(result.isSuccess)
        assertTrue(result.failure is BridgeFailure.Validation)
        assertEquals(listOf("start", "stop:text=first", "enter"), fake.calls)
    }

    @Test
    fun duplicateConcurrentSendIsRejectedInsteadOfQueued() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeBridgeOperations(startGate = gate)
        val coordinator = SendCoordinator(fake)
        val first = async { coordinator.sendText("first") }
        fake.startEntered.await()

        val duplicate = coordinator.sendText("second")

        assertFalse(duplicate.isSuccess)
        assertTrue(duplicate.failure is BridgeFailure.Busy)
        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertEquals(1, fake.calls.count { it == "start" })
    }
}

private class FakeBridgeOperations(
    private val startResult: RecordingResult = RecordingResult.success(),
    private val uploadResult: RecordingResult = RecordingResult.success(),
    private val stopResult: RecordingResult = RecordingResult.success(),
    enterResult: RecordingResult = RecordingResult.success(),
    private val startGate: CompletableDeferred<Unit>? = null,
) : BridgeOperations {
    val calls = mutableListOf<String>()
    val startEntered = CompletableDeferred<Unit>()
    var enterCalled = false
    var startedSessionId = ""
    var uploadedSessionId = ""
    var enterResult = enterResult

    override suspend fun startRecording(sessionId: String): RecordingResult {
        calls += "start"
        startedSessionId = sessionId
        startEntered.complete(Unit)
        startGate?.await()
        return startResult
    }

    override suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult {
        calls += "upload"
        uploadedSessionId = sessionId
        return uploadResult
    }

    override suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult {
        calls += if (text == null) "stop:voice" else "stop:text=$text"
        return stopResult
    }

    override suspend fun pressEnter(): RecordingResult {
        calls += "enter"
        enterCalled = true
        return enterResult
    }
}
