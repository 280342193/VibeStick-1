package com.vibestick.android.data

import org.json.JSONObject

enum class AgentStatus {
    UNKNOWN,
    IDLE,
    RUNNING,
    DONE,
    ERROR,
    APPROVAL;

    companion object {
        fun from(raw: String?): AgentStatus =
            entries.firstOrNull { it.name == raw.orEmpty().uppercase() } ?: UNKNOWN
    }
}

enum class AlertType {
    NONE,
    DONE,
    ERROR,
    APPROVAL;

    companion object {
        fun from(raw: String?): AlertType =
            entries.firstOrNull { it.name == raw.orEmpty().uppercase() } ?: NONE
    }
}

data class Alert(
    val eventId: String = "",
    val type: AlertType = AlertType.NONE,
    val message: String = "",
) {
    val isTerminal: Boolean
        get() = eventId.isNotBlank() && type in setOf(AlertType.DONE, AlertType.ERROR, AlertType.APPROVAL)
}

data class ProviderState(
    val id: String = "",
    val displayName: String = "",
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val quota5hRemaining: Int? = null,
    val quota7dRemaining: Int? = null,
)

data class BridgeState(
    val computerName: String = "",
    val activeProvider: String = "",
    val provider: ProviderState = ProviderState(),
    val codexStatus: AgentStatus = AgentStatus.UNKNOWN,
    val alert: Alert = Alert(),
) {
    companion object {
        fun fromJson(json: JSONObject): BridgeState {
            val providerJson = json.optJSONObject("provider") ?: JSONObject()
            val codexJson = json.optJSONObject("codex") ?: JSONObject()
            val alertJson = json.optJSONObject("alert") ?: JSONObject()

            return BridgeState(
                computerName = json.optString("computer_name"),
                activeProvider = json.optString("active_provider"),
                provider = ProviderState(
                    id = providerJson.optString("id"),
                    displayName = providerJson.optString("display_name"),
                    status = AgentStatus.from(providerJson.optString("status")),
                    quota5hRemaining = providerJson.optNullableInt("quota_5h_remaining"),
                    quota7dRemaining = providerJson.optNullableInt("quota_7d_remaining"),
                ),
                codexStatus = AgentStatus.from(codexJson.optString("status")),
                alert = Alert(
                    eventId = alertJson.optString("event_id"),
                    type = AlertType.from(alertJson.optString("type")),
                    message = alertJson.optString("message"),
                ),
            )
        }
    }
}

data class BridgeCandidate(
    val host: String,
    val port: Int,
    val name: String,
    val version: String,
)

sealed interface BridgeFailure {
    val message: String

    data class Http(
        val statusCode: Int,
        override val message: String,
    ) : BridgeFailure

    data class Network(override val message: String) : BridgeFailure

    data class Protocol(override val message: String) : BridgeFailure

    data class Audio(override val message: String) : BridgeFailure

    data class Validation(override val message: String) : BridgeFailure

    data class Busy(
        override val message: String = "Another action is already in progress",
    ) : BridgeFailure
}

class RecordingResult private constructor(
    val isSuccess: Boolean,
    val status: String,
    val message: String,
    val transcript: String?,
    val failure: BridgeFailure?,
) {
    companion object {
        fun success(
            status: String = "",
            message: String = "",
            transcript: String? = null,
        ): RecordingResult = RecordingResult(
            isSuccess = true,
            status = status,
            message = message,
            transcript = transcript,
            failure = null,
        )

        fun failure(
            failure: BridgeFailure,
            status: String = "",
            transcript: String? = null,
        ): RecordingResult = RecordingResult(
            isSuccess = false,
            status = status,
            message = failure.message,
            transcript = transcript,
            failure = failure,
        )
    }
}

sealed interface BridgeCallResult<out T> {
    data class Success<T>(val value: T) : BridgeCallResult<T>

    data class Failure(val failure: BridgeFailure) : BridgeCallResult<Nothing>
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Discovering : ConnectionState

    data class Connecting(val candidate: BridgeCandidate) : ConnectionState

    data class Connected(val candidate: BridgeCandidate) : ConnectionState

    data class SelectionRequired(val candidateCount: Int) : ConnectionState

    data class Offline(
        val candidate: BridgeCandidate?,
        val failure: BridgeFailure,
    ) : ConnectionState

    data class InvalidToken(val candidate: BridgeCandidate) : ConnectionState
}

private fun JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)
