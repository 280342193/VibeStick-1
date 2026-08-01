package com.vibestick.android.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BridgeRepositoryTest {
    private val desk = BridgeCandidate("192.168.1.30", 8765, "Desk", "0.1.5")
    private val laptop = BridgeCandidate("192.168.1.31", 8765, "Laptop", "0.1.5")

    @Test
    fun oneDiscoveryResultIsRememberedAndConnectedAutomatically() = runTest {
        val store = FakeConnectionStore()
        val gateway = FakeBridgeGateway(
            stateResult = BridgeCallResult.Success(bridgeState("Desk")),
        )
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(listOf(desk)),
            store = store,
            gatewayFactory = { _, _ -> gateway },
        )

        repository.discover()

        assertEquals(listOf(desk), repository.candidates.value)
        assertEquals(ConnectionState.Connected(desk), repository.connection.value)
        assertEquals("Desk", repository.bridgeState.value?.computerName)
        assertEquals(desk, store.selectedBridge())
    }

    @Test
    fun multipleDiscoveryResultsWaitForExplicitSelection() = runTest {
        var gatewayCreated = false
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(listOf(desk, laptop)),
            store = FakeConnectionStore(),
            gatewayFactory = { _, _ ->
                gatewayCreated = true
                FakeBridgeGateway(BridgeCallResult.Success(bridgeState("unused")))
            },
        )

        repository.discover()

        assertEquals(ConnectionState.SelectionRequired(2), repository.connection.value)
        assertNull(repository.bridgeState.value)
        assertEquals(false, gatewayCreated)
    }

    @Test
    fun multipleResultsReconnectUniqueRememberedComputerAfterIpChange() = runTest {
        val movedDesk = desk.copy(host = "192.168.1.99", version = "0.1.12")
        var selected: BridgeCandidate? = null
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(listOf(laptop, movedDesk)),
            store = FakeConnectionStore(initialCandidate = desk),
            gatewayFactory = { candidate, _ ->
                selected = candidate
                FakeBridgeGateway(BridgeCallResult.Success(bridgeState(candidate.name)))
            },
        )

        repository.discover()

        assertEquals(movedDesk, selected)
        assertEquals(ConnectionState.Connected(movedDesk), repository.connection.value)
    }

    @Test
    fun duplicateRememberedComputerNamesStillRequireSelection() = runTest {
        val first = desk.copy(host = "192.168.1.40")
        val second = desk.copy(host = "192.168.1.41")
        var gatewayCreated = false
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(listOf(first, second)),
            store = FakeConnectionStore(initialCandidate = desk),
            gatewayFactory = { _, _ ->
                gatewayCreated = true
                FakeBridgeGateway(BridgeCallResult.Success(bridgeState("unused")))
            },
        )

        repository.discover()

        assertEquals(ConnectionState.SelectionRequired(2), repository.connection.value)
        assertFalse(gatewayCreated)
    }

    @Test
    fun unauthorizedConnectionMapsToInvalidToken() = runTest {
        val store = FakeConnectionStore(initialCandidate = desk, initialToken = "wrong")
        val failure = BridgeFailure.Http(401, "Unauthorized")
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = store,
            gatewayFactory = { _, _ -> FakeBridgeGateway(BridgeCallResult.Failure(failure)) },
        )

        repository.selectBridge(desk)

        assertEquals(ConnectionState.InvalidToken(desk), repository.connection.value)
        assertSame(desk, store.selectedBridge())
    }

    @Test
    fun networkFailureMarksOfflineWithoutForgettingCandidate() = runTest {
        val store = FakeConnectionStore(initialCandidate = desk)
        val failure = BridgeFailure.Network("unreachable")
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = store,
            gatewayFactory = { _, _ -> FakeBridgeGateway(BridgeCallResult.Failure(failure)) },
        )

        repository.selectBridge(desk)

        assertEquals(ConnectionState.Offline(desk, failure), repository.connection.value)
        assertSame(desk, store.selectedBridge())
    }

    @Test
    fun publicStateRefreshDoesNotClearProtectedRequestTokenFailure() = runTest {
        val unauthorized = BridgeFailure.Http(401, "Unauthorized")
        val gateway = FakeBridgeGateway(
            stateResult = BridgeCallResult.Success(bridgeState("Desk")),
            stopResult = RecordingResult.failure(unauthorized),
        )
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = FakeConnectionStore(initialCandidate = desk, initialToken = "wrong"),
            gatewayFactory = { _, _ -> gateway },
        )
        repository.selectBridge(desk)

        repository.sendText("run tests")
        assertEquals(ConnectionState.InvalidToken(desk), repository.connection.value)

        repository.refreshState()

        assertEquals(ConnectionState.InvalidToken(desk), repository.connection.value)
    }

    @Test
    fun tokenUpdateInvalidatesAnInFlightOldGatewayRefresh() = runTest {
        val oldGateway = BlockingBridgeGateway(bridgeState("Old"))
        val newGateway = FakeBridgeGateway(
            stateResult = BridgeCallResult.Success(bridgeState("New")),
        )
        val tokens = mutableListOf<String>()
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = FakeConnectionStore(initialCandidate = desk, initialToken = "old"),
            gatewayFactory = { _, token ->
                tokens += token
                if (token == "old") oldGateway else newGateway
            },
        )
        val refreshing = async { repository.refreshState() }
        oldGateway.entered.await()

        repository.updateToken("new")
        oldGateway.release.complete(Unit)
        refreshing.await()

        assertFalse(repository.connection.value is ConnectionState.Connected)
        repository.refreshState()
        assertEquals(listOf("old", "new"), tokens)
        assertEquals("New", repository.bridgeState.value?.computerName)
    }

    @Test
    fun enterRetrySurvivesTokenUpdateAndGatewayRebuild() = runTest {
        val firstGateway = TrackingBridgeGateway(
            enterResult = RecordingResult.failure(BridgeFailure.Network("enter disconnected")),
        )
        val secondGateway = TrackingBridgeGateway(
            enterResult = RecordingResult.success(status = "enter_sent"),
        )
        var gatewayCount = 0
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = FakeConnectionStore(initialCandidate = desk, initialToken = "old"),
            gatewayFactory = { _, _ ->
                if (gatewayCount++ == 0) firstGateway else secondGateway
            },
        )
        repository.selectBridge(desk)
        val first = repository.sendText("keep this")
        repository.updateToken("new")
        repository.selectBridge(desk)

        val retry = repository.sendText("keep this")

        assertFalse(first.isSuccess)
        assertEquals("enter_failed", first.status)
        assertEquals(listOf("start", "stop", "enter"), firstGateway.calls)
        assertEquals(listOf("enter"), secondGateway.calls)
        assertEquals(true, retry.isSuccess)
    }

    @Test
    fun inFlightRefreshCannotOverrideAmbiguousDiscoverySelection() = runTest {
        val oldGateway = BlockingBridgeGateway(bridgeState("Old"))
        val first = desk.copy(host = "192.168.1.40")
        val second = desk.copy(host = "192.168.1.41")
        val repository = BridgeRepository(
            scanner = FakeBridgeScanner(listOf(first, second)),
            store = FakeConnectionStore(initialCandidate = desk),
            gatewayFactory = { _, _ -> oldGateway },
        )
        val refreshing = async { repository.refreshState() }
        oldGateway.entered.await()

        repository.discover()
        oldGateway.release.complete(Unit)
        refreshing.await()

        assertEquals(ConnectionState.SelectionRequired(2), repository.connection.value)
    }

    @Test
    fun refreshIsRejectedWhileDiscoveryIsRunning() = runTest {
        val scanner = BlockingBridgeScanner()
        var gatewayCreated = false
        val repository = BridgeRepository(
            scanner = scanner,
            store = FakeConnectionStore(initialCandidate = desk),
            gatewayFactory = { _, _ ->
                gatewayCreated = true
                FakeBridgeGateway(BridgeCallResult.Success(bridgeState("Desk")))
            },
        )
        val discovery = async { repository.discover() }
        scanner.entered.await()

        val result = repository.refreshState()

        assertEquals(BridgeFailure.Busy(), (result as BridgeCallResult.Failure).failure)
        assertFalse(gatewayCreated)
        scanner.release.complete(Unit)
        discovery.await()
    }

    @Test
    fun enterRetrySurvivesRepositoryRecreation() = runTest {
        val store = FakeConnectionStore(initialCandidate = desk)
        val firstGateway = TrackingBridgeGateway(
            enterResult = RecordingResult.failure(BridgeFailure.Network("enter disconnected")),
        )
        val firstRepository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = store,
            gatewayFactory = { _, _ -> firstGateway },
        )
        firstRepository.selectBridge(desk)
        firstRepository.sendText("keep this")

        val secondGateway = TrackingBridgeGateway(
            enterResult = RecordingResult.success(status = "enter_sent"),
        )
        val recreatedRepository = BridgeRepository(
            scanner = FakeBridgeScanner(emptyList()),
            store = store,
            gatewayFactory = { _, _ -> secondGateway },
        )
        recreatedRepository.selectBridge(desk)

        val retry = recreatedRepository.sendText("keep this")

        assertEquals(listOf("enter"), secondGateway.calls)
        assertEquals(true, retry.isSuccess)
        assertEquals("", store.pendingEnterFingerprint())
    }
}

private class FakeBridgeScanner(
    private val results: List<BridgeCandidate>,
) : BridgeScanner {
    override suspend fun discover(
        token: String,
        lastKnownHost: String?,
    ): List<BridgeCandidate> = results
}

private class BlockingBridgeScanner : BridgeScanner {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun discover(
        token: String,
        lastKnownHost: String?,
    ): List<BridgeCandidate> {
        entered.complete(Unit)
        release.await()
        return emptyList()
    }
}

private class FakeConnectionStore(
    initialCandidate: BridgeCandidate? = null,
    initialToken: String = "",
) : BridgeConnectionStore {
    private var candidate = initialCandidate
    private var savedToken = initialToken
    private var eventId = ""
    private var pendingEnterFingerprint = ""

    override fun selectedBridge(): BridgeCandidate? = candidate

    override fun saveSelectedBridge(candidate: BridgeCandidate) {
        this.candidate = candidate
    }

    override fun clearSelectedBridge() {
        candidate = null
    }

    override fun token(): String = savedToken

    override fun saveToken(token: String) {
        savedToken = token
    }

    override fun lastAlertEventId(): String = eventId

    override fun saveLastAlertEventId(eventId: String) {
        this.eventId = eventId
    }

    override fun pendingEnterFingerprint(): String = pendingEnterFingerprint

    override fun savePendingEnterFingerprint(fingerprint: String) {
        pendingEnterFingerprint = fingerprint
    }
}

private class FakeBridgeGateway(
    private val stateResult: BridgeCallResult<BridgeState>,
    private val stopResult: RecordingResult = RecordingResult.success(),
) : BridgeGateway {
    override suspend fun getState(): BridgeCallResult<BridgeState> = stateResult

    override suspend fun startRecording(sessionId: String): RecordingResult =
        RecordingResult.success()

    override suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult = RecordingResult.success()

    override suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult = stopResult

    override suspend fun pressEnter(): RecordingResult = RecordingResult.success()
}

private class BlockingBridgeGateway(
    private val state: BridgeState,
) : BridgeGateway {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun getState(): BridgeCallResult<BridgeState> {
        entered.complete(Unit)
        release.await()
        return BridgeCallResult.Success(state)
    }

    override suspend fun startRecording(sessionId: String): RecordingResult =
        RecordingResult.success()

    override suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult = RecordingResult.success()

    override suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult = RecordingResult.success()

    override suspend fun pressEnter(): RecordingResult = RecordingResult.success()
}

private class TrackingBridgeGateway(
    private val enterResult: RecordingResult,
) : BridgeGateway {
    val calls = mutableListOf<String>()

    override suspend fun getState(): BridgeCallResult<BridgeState> =
        BridgeCallResult.Success(bridgeState("Desk"))

    override suspend fun startRecording(sessionId: String): RecordingResult {
        calls += "start"
        return RecordingResult.success()
    }

    override suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult {
        calls += "upload"
        return RecordingResult.success()
    }

    override suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult {
        calls += "stop"
        return RecordingResult.success(status = "pasted")
    }

    override suspend fun pressEnter(): RecordingResult {
        calls += "enter"
        return enterResult
    }
}

private fun bridgeState(computerName: String): BridgeState = BridgeState(
    computerName = computerName,
    codexStatus = AgentStatus.IDLE,
)
