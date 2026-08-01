package com.vibestick.android.data

import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

interface BridgeOperations {
    suspend fun startRecording(sessionId: String): RecordingResult

    suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult

    suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult

    suspend fun pressEnter(): RecordingResult
}

interface BridgeGateway : BridgeOperations {
    suspend fun getState(): BridgeCallResult<BridgeState>
}

class HttpBridgeGateway(
    private val client: BridgeHttpClient,
) : BridgeGateway {
    override suspend fun getState(): BridgeCallResult<BridgeState> = client.getState()

    override suspend fun startRecording(sessionId: String): RecordingResult =
        client.startRecording(sessionId)

    override suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult = client.uploadPcm(sessionId, pcm)

    override suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult = client.stopRecording(text, paste)

    override suspend fun pressEnter(): RecordingResult =
        when (val response = client.postEvent(BridgeProtocol.enterEventBody())) {
            is BridgeCallResult.Failure -> RecordingResult.failure(response.failure)
            is BridgeCallResult.Success -> {
                val alert = response.value.alert
                if (alert.type == AlertType.ERROR) {
                    RecordingResult.failure(
                        BridgeFailure.Protocol(
                            alert.message.ifBlank { "The text was pasted, but Enter failed" },
                        ),
                        status = "enter_failed",
                    )
                } else {
                    RecordingResult.success(status = "enter_sent")
                }
            }
        }
}

class SendCoordinator(
    private val operations: BridgeOperations,
    private val sessionIdFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
    private val pendingEnterState: PendingEnterState = PendingEnterState(),
) {
    private val sendMutex = Mutex()

    suspend fun sendText(text: String): RecordingResult {
        if (text.isBlank()) {
            return RecordingResult.failure(
                BridgeFailure.Validation("Enter text before sending"),
            )
        }
        return executeExclusive {
            if (pendingEnterState.hasPending()) {
                if (!pendingEnterState.matches(text)) {
                    return@executeExclusive RecordingResult.failure(
                        BridgeFailure.Validation(
                            "The previous text is already pasted; retry Enter before changing it",
                        ),
                        status = "enter_retry_required",
                    )
                }
                return@executeExclusive pressEnterForPastedText(text)
            }

            val started = operations.startRecording(sessionIdFactory())
            if (!started.isSuccess) return@executeExclusive started

            val pasted = operations.stopRecording(text = text, paste = true)
            if (!pasted.isSuccess) return@executeExclusive pasted

            pressEnterForPastedText(text)
        }
    }

    suspend fun sendVoice(pcm: ByteArray): RecordingResult {
        if (pcm.isEmpty()) {
            return RecordingResult.failure(
                BridgeFailure.Validation("Record audio before sending"),
            )
        }
        return executeExclusive {
            if (pendingEnterState.hasPending()) {
                return@executeExclusive RecordingResult.failure(
                    BridgeFailure.Validation(
                        "The previous text is already pasted; retry Enter before voice input",
                    ),
                    status = "enter_retry_required",
                )
            }
            val sessionId = sessionIdFactory()
            val started = operations.startRecording(sessionId)
            if (!started.isSuccess) return@executeExclusive started

            val uploaded = operations.uploadPcm(sessionId, pcm)
            if (!uploaded.isSuccess) return@executeExclusive uploaded

            operations.stopRecording(text = null, paste = true)
        }
    }

    private suspend fun pressEnterForPastedText(text: String): RecordingResult {
        val result = operations.pressEnter()
        return if (result.isSuccess) {
            pendingEnterState.clear(text)
            result
        } else {
            pendingEnterState.mark(text)
            RecordingResult.failure(
                failure = result.failure
                    ?: BridgeFailure.Protocol("The text was pasted, but Enter failed"),
                status = "enter_failed",
                transcript = result.transcript,
            )
        }
    }

    private suspend fun executeExclusive(
        block: suspend () -> RecordingResult,
    ): RecordingResult {
        if (!sendMutex.tryLock()) {
            return RecordingResult.failure(BridgeFailure.Busy())
        }
        return try {
            block()
        } finally {
            sendMutex.unlock()
        }
    }
}

class PendingEnterState(
    initialFingerprint: String = "",
    private val onFingerprintChanged: (String) -> Unit = {},
) {
    private val pendingFingerprint = AtomicReference(initialFingerprint)

    fun hasPending(): Boolean = pendingFingerprint.get().isNotBlank()

    fun matches(text: String): Boolean = pendingFingerprint.get() == fingerprint(text)

    fun mark(text: String) {
        val fingerprint = fingerprint(text)
        pendingFingerprint.set(fingerprint)
        onFingerprintChanged(fingerprint)
    }

    fun clear(expectedText: String) {
        val expectedFingerprint = fingerprint(expectedText)
        val currentFingerprint = pendingFingerprint.get()
        if (
            currentFingerprint == expectedFingerprint &&
            pendingFingerprint.compareAndSet(currentFingerprint, "")
        ) {
            onFingerprintChanged("")
        }
    }

    private fun fingerprint(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}

enum class BusyAction {
    NONE,
    DISCOVERING,
    CONNECTING,
    SENDING_VOICE,
    SENDING_TEXT,
}

interface VibeStickRepository {
    val connection: StateFlow<ConnectionState>
    val candidates: StateFlow<List<BridgeCandidate>>
    val bridgeState: StateFlow<BridgeState?>
    val busyAction: StateFlow<BusyAction>

    suspend fun discover(): BridgeCallResult<List<BridgeCandidate>>

    suspend fun selectBridge(candidate: BridgeCandidate): BridgeCallResult<BridgeState>

    suspend fun refreshState(): BridgeCallResult<BridgeState>

    suspend fun sendText(text: String): RecordingResult

    suspend fun sendVoice(pcm: ByteArray): RecordingResult

    fun updateToken(token: String)

    fun rememberedBridge(): BridgeCandidate?
}

class BridgeRepository(
    private val scanner: BridgeScanner,
    private val store: BridgeConnectionStore,
    private val gatewayFactory: (BridgeCandidate, String) -> BridgeGateway = { candidate, token ->
        HttpBridgeGateway(BridgeHttpClient(candidate, token))
    },
) : VibeStickRepository {
    private val connectionLock = Any()
    private val connectionRevision = AtomicLong(0)
    private val pendingEnterState = PendingEnterState(
        initialFingerprint = store.pendingEnterFingerprint(),
        onFingerprintChanged = store::savePendingEnterFingerprint,
    )

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _candidates = MutableStateFlow<List<BridgeCandidate>>(emptyList())
    override val candidates: StateFlow<List<BridgeCandidate>> = _candidates.asStateFlow()

    private val _bridgeState = MutableStateFlow<BridgeState?>(null)
    override val bridgeState: StateFlow<BridgeState?> = _bridgeState.asStateFlow()

    private val _busyAction = MutableStateFlow(BusyAction.NONE)
    override val busyAction: StateFlow<BusyAction> = _busyAction.asStateFlow()

    private var selectedCandidate: BridgeCandidate? = store.selectedBridge()
    private var gateway: BridgeGateway? = null
    private var sendCoordinator: SendCoordinator? = null

    override suspend fun discover(): BridgeCallResult<List<BridgeCandidate>> {
        if (!_busyAction.compareAndSet(BusyAction.NONE, BusyAction.DISCOVERING)) {
            return BridgeCallResult.Failure(BridgeFailure.Busy())
        }
        synchronized(connectionLock) {
            connectionRevision.incrementAndGet()
        }
        _connection.value = ConnectionState.Discovering
        val remembered = store.selectedBridge()
        val result = try {
            BridgeCallResult.Success(
                scanner.discover(
                    token = store.token(),
                    lastKnownHost = remembered?.host,
                ),
            )
        } catch (error: IOException) {
            BridgeCallResult.Failure(
                BridgeFailure.Network(error.message ?: "LAN discovery failed"),
            )
        } catch (error: SecurityException) {
            BridgeCallResult.Failure(
                BridgeFailure.Network(error.message ?: "LAN discovery was blocked"),
            )
        } finally {
            _busyAction.compareAndSet(BusyAction.DISCOVERING, BusyAction.NONE)
        }

        return when (result) {
            is BridgeCallResult.Failure -> {
                _connection.value = if (remembered == null) {
                    ConnectionState.Disconnected
                } else {
                    ConnectionState.Offline(remembered, result.failure)
                }
                result
            }
            is BridgeCallResult.Success -> {
                val found = result.value
                _candidates.value = found
                val rememberedMatch = rememberedBridgeMatch(remembered, found)
                when {
                    found.isEmpty() -> {
                        _connection.value = if (remembered == null) {
                            ConnectionState.Disconnected
                        } else {
                            ConnectionState.Offline(
                                remembered,
                                BridgeFailure.Network("No VibeStick bridge responded"),
                            )
                        }
                    }
                    found.size == 1 -> selectBridge(found.single())
                    rememberedMatch != null -> selectBridge(rememberedMatch)
                    else -> synchronized(connectionLock) {
                        gateway = null
                        sendCoordinator = null
                        _connection.value = ConnectionState.SelectionRequired(found.size)
                    }
                }
                result
            }
        }
    }

    override suspend fun selectBridge(candidate: BridgeCandidate): BridgeCallResult<BridgeState> {
        if (!_busyAction.compareAndSet(BusyAction.NONE, BusyAction.CONNECTING)) {
            return BridgeCallResult.Failure(BridgeFailure.Busy())
        }
        val revision = synchronized(connectionLock) {
            val currentRevision = connectionRevision.incrementAndGet()
            selectedCandidate = candidate
            gateway = null
            sendCoordinator = null
            currentRevision
        }
        store.saveSelectedBridge(candidate)
        _connection.value = ConnectionState.Connecting(candidate)
        val newGateway = gatewayFactory(candidate, store.token())

        return try {
            when (val result = newGateway.getState()) {
                is BridgeCallResult.Success -> {
                    val committed = synchronized(connectionLock) {
                        if (
                            connectionRevision.get() != revision ||
                            selectedCandidate != candidate
                        ) {
                            false
                        } else {
                            gateway = newGateway
                            sendCoordinator = SendCoordinator(
                                newGateway,
                                pendingEnterState = pendingEnterState,
                            )
                            _bridgeState.value = result.value
                            _connection.value = ConnectionState.Connected(candidate)
                            true
                        }
                    }
                    if (committed) result else staleConnectionResult()
                }
                is BridgeCallResult.Failure -> {
                    val committed = synchronized(connectionLock) {
                        if (
                            connectionRevision.get() != revision ||
                            selectedCandidate != candidate
                        ) {
                            false
                        } else {
                            gateway = null
                            sendCoordinator = null
                            _bridgeState.value = null
                            _connection.value = connectionFailure(candidate, result.failure)
                            true
                        }
                    }
                    if (committed) result else staleConnectionResult()
                }
            }
        } finally {
            _busyAction.compareAndSet(BusyAction.CONNECTING, BusyAction.NONE)
        }
    }

    override suspend fun refreshState(): BridgeCallResult<BridgeState> {
        val attempt = synchronized(connectionLock) {
            if (
                _busyAction.value == BusyAction.DISCOVERING ||
                _busyAction.value == BusyAction.CONNECTING
            ) {
                return BridgeCallResult.Failure(BridgeFailure.Busy())
            }
            val candidate = selectedCandidate ?: store.selectedBridge()
                ?: return BridgeCallResult.Failure(
                    BridgeFailure.Network("No bridge is selected"),
                )
            RefreshAttempt(
                revision = connectionRevision.get(),
                candidate = candidate,
                gateway = gateway ?: gatewayFactory(candidate, store.token()),
            )
        }
        val candidate = attempt.candidate
        val activeGateway = attempt.gateway
        return when (val result = activeGateway.getState()) {
            is BridgeCallResult.Success -> {
                val committed = synchronized(connectionLock) {
                    if (
                        connectionRevision.get() != attempt.revision ||
                        selectedCandidate?.let { it != candidate } == true
                    ) {
                        false
                    } else {
                        selectedCandidate = candidate
                        gateway = activeGateway
                        if (sendCoordinator == null) {
                            sendCoordinator = SendCoordinator(
                                activeGateway,
                                pendingEnterState = pendingEnterState,
                            )
                        }
                        _bridgeState.value = result.value
                        val currentConnection = _connection.value
                        if (
                            currentConnection !is ConnectionState.InvalidToken ||
                            currentConnection.candidate != candidate
                        ) {
                            _connection.value = ConnectionState.Connected(candidate)
                        }
                        true
                    }
                }
                if (committed) result else staleConnectionResult()
            }
            is BridgeCallResult.Failure -> {
                val committed = synchronized(connectionLock) {
                    if (
                        connectionRevision.get() != attempt.revision ||
                        selectedCandidate?.let { it != candidate } == true
                    ) {
                        false
                    } else {
                        applyOperationFailure(candidate, result.failure)
                        true
                    }
                }
                if (committed) result else staleConnectionResult()
            }
        }
    }

    override suspend fun sendText(text: String): RecordingResult =
        runSend(BusyAction.SENDING_TEXT) { coordinator -> coordinator.sendText(text) }

    override suspend fun sendVoice(pcm: ByteArray): RecordingResult =
        runSend(BusyAction.SENDING_VOICE) { coordinator -> coordinator.sendVoice(pcm) }

    override fun updateToken(token: String) {
        store.saveToken(token)
        synchronized(connectionLock) {
            connectionRevision.incrementAndGet()
            gateway = null
            sendCoordinator = null
            _connection.value = selectedCandidate?.let(ConnectionState::Connecting)
                ?: ConnectionState.Disconnected
        }
    }

    override fun rememberedBridge(): BridgeCandidate? = store.selectedBridge()

    private suspend fun runSend(
        action: BusyAction,
        block: suspend (SendCoordinator) -> RecordingResult,
    ): RecordingResult {
        if (!_busyAction.compareAndSet(BusyAction.NONE, action)) {
            return RecordingResult.failure(BridgeFailure.Busy())
        }
        return try {
            val attempt = synchronized(connectionLock) {
                SendAttempt(
                    revision = connectionRevision.get(),
                    candidate = selectedCandidate,
                    coordinator = sendCoordinator,
                )
            }
            val coordinator = attempt.coordinator
                ?: return RecordingResult.failure(
                    BridgeFailure.Network("The bridge is not connected"),
                )
            val result = block(coordinator)
            synchronized(connectionLock) {
                if (connectionRevision.get() == attempt.revision) {
                    result.failure?.let { failure ->
                        attempt.candidate?.let { applyOperationFailure(it, failure) }
                    }
                }
            }
            result
        } finally {
            _busyAction.compareAndSet(action, BusyAction.NONE)
        }
    }

    private fun applyOperationFailure(
        candidate: BridgeCandidate,
        failure: BridgeFailure,
    ) {
        when {
            failure is BridgeFailure.Http && failure.statusCode == 401 -> {
                _connection.value = ConnectionState.InvalidToken(candidate)
            }
            failure is BridgeFailure.Network -> {
                _connection.value = ConnectionState.Offline(candidate, failure)
            }
        }
    }

    private fun connectionFailure(
        candidate: BridgeCandidate,
        failure: BridgeFailure,
    ): ConnectionState =
        if (failure is BridgeFailure.Http && failure.statusCode == 401) {
            ConnectionState.InvalidToken(candidate)
        } else {
            ConnectionState.Offline(candidate, failure)
        }

    private fun staleConnectionResult(): BridgeCallResult.Failure =
        BridgeCallResult.Failure(
            BridgeFailure.Busy("Connection settings changed during the request"),
        )
}

private data class RefreshAttempt(
    val revision: Long,
    val candidate: BridgeCandidate,
    val gateway: BridgeGateway,
)

private data class SendAttempt(
    val revision: Long,
    val candidate: BridgeCandidate?,
    val coordinator: SendCoordinator?,
)

private fun rememberedBridgeMatch(
    remembered: BridgeCandidate?,
    found: List<BridgeCandidate>,
): BridgeCandidate? {
    if (remembered == null) return null
    found.firstOrNull {
        it.host == remembered.host && it.port == remembered.port
    }?.let { return it }

    val nameMatches = found.filter {
        it.name.equals(remembered.name, ignoreCase = true)
    }
    return nameMatches.singleOrNull()
}
