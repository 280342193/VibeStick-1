package com.vibestick.android.data

import com.vibestick.android.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val connectTimeoutMillis = 3_000
private const val defaultReadTimeoutMillis = 30_000
private const val recordingStopReadTimeoutMillis = 630_000

class BridgeHttpClient(
    private val candidate: BridgeCandidate,
    private val token: String = "",
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    suspend fun getState(): BridgeCallResult<BridgeState> = withContext(Dispatchers.IO) {
        when (val response = execute(method = "GET", path = BridgeProtocol.statePath)) {
            is BridgeCallResult.Success -> parseState(response.value)
            is BridgeCallResult.Failure -> response
        }
    }

    suspend fun startRecording(sessionId: String): RecordingResult = recordingRequest(
        path = BridgeProtocol.recordingStartPath,
        body = BridgeProtocol.startRecordingBody(sessionId),
    )

    suspend fun uploadPcm(
        sessionId: String,
        pcm: ByteArray,
    ): RecordingResult = recordingRequest(
        path = BridgeProtocol.recordingAudioPath(sessionId),
        body = pcm,
        contentType = "application/octet-stream",
        audioHeaders = true,
    )

    suspend fun stopRecording(
        text: String?,
        paste: Boolean,
    ): RecordingResult = recordingRequest(
        path = BridgeProtocol.recordingStopPath,
        body = if (text == null) {
            BridgeProtocol.stopVoiceBody(paste)
        } else {
            BridgeProtocol.stopTextBody(text, paste)
        },
        readTimeoutMillis = recordingStopReadTimeoutMillis,
    )

    suspend fun postEvent(body: ByteArray): BridgeCallResult<BridgeState> =
        withContext(Dispatchers.IO) {
            when (
                val response = execute(
                    method = "POST",
                    path = BridgeProtocol.eventPath,
                    body = body,
                    protected = true,
                )
            ) {
                is BridgeCallResult.Success -> parseState(response.value)
                is BridgeCallResult.Failure -> response
            }
        }

    private suspend fun recordingRequest(
        path: String,
        body: ByteArray,
        contentType: String = "application/json; charset=utf-8",
        audioHeaders: Boolean = false,
        readTimeoutMillis: Int = defaultReadTimeoutMillis,
    ): RecordingResult = withContext(Dispatchers.IO) {
        when (
            val response = execute(
                method = "POST",
                path = path,
                body = body,
                contentType = contentType,
                protected = true,
                audioHeaders = audioHeaders,
                readTimeoutMillis = readTimeoutMillis,
            )
        ) {
            is BridgeCallResult.Success -> parseRecordingResult(response.value)
            is BridgeCallResult.Failure -> RecordingResult.failure(response.failure)
        }
    }

    private fun execute(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json; charset=utf-8",
        protected: Boolean = false,
        audioHeaders: Boolean = false,
        readTimeoutMillis: Int = defaultReadTimeoutMillis,
    ): BridgeCallResult<JSONObject> {
        var connection: HttpURLConnection? = null
        return try {
            connection = connectionFactory(URL("http", candidate.host, candidate.port, path))
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Vibe-Stick-Firmware-Name", BridgeProtocol.firmwareName)
            connection.setRequestProperty("X-Vibe-Stick-Firmware-Version", BuildConfig.VERSION_NAME)
            connection.setRequestProperty("X-Vibe-Stick-Firmware-Transport", BridgeProtocol.transport)
            if (protected && token.isNotBlank()) {
                connection.setRequestProperty("X-Vibe-Stick-Token", token)
            }
            if (audioHeaders) {
                connection.setRequestProperty("X-Vibe-Stick-Sample-Rate", "16000")
                connection.setRequestProperty("X-Vibe-Stick-Channels", "1")
                connection.setRequestProperty("X-Vibe-Stick-Bits-Per-Sample", "16")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val responseText = (
                if (statusCode in 200..299) connection.inputStream else connection.errorStream
            )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (statusCode !in 200..299) {
                val message = runCatching { JSONObject(responseText).optString("error") }
                    .getOrDefault("")
                    .ifBlank { "HTTP $statusCode" }
                BridgeCallResult.Failure(BridgeFailure.Http(statusCode, message))
            } else {
                val json = runCatching { JSONObject(responseText) }.getOrElse {
                    return BridgeCallResult.Failure(
                        BridgeFailure.Protocol("Bridge returned invalid JSON"),
                    )
                }
                BridgeCallResult.Success(json)
            }
        } catch (error: IOException) {
            BridgeCallResult.Failure(
                BridgeFailure.Network(error.message ?: "Bridge network request failed"),
            )
        } catch (error: SecurityException) {
            BridgeCallResult.Failure(
                BridgeFailure.Network(error.message ?: "Bridge network request was blocked"),
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseState(json: JSONObject): BridgeCallResult<BridgeState> =
        runCatching { BridgeState.fromJson(json) }
            .fold(
                onSuccess = { BridgeCallResult.Success(it) },
                onFailure = {
                    BridgeCallResult.Failure(
                        BridgeFailure.Protocol("Bridge state was invalid"),
                    )
                },
            )

    private fun parseRecordingResult(json: JSONObject): RecordingResult {
        val recording = json.optJSONObject("recording")
            ?: return RecordingResult.failure(
                BridgeFailure.Protocol("Bridge response omitted recording state"),
            )
        val status = recording.optString("status")
        val message = recording.optString("message")
        val transcript = recording.optString("transcript").takeIf(String::isNotEmpty)
        if (status.isBlank()) {
            return RecordingResult.failure(
                BridgeFailure.Protocol("Bridge response omitted recording status"),
                transcript = transcript,
            )
        }

        return if (status in recordingFailureStatuses) {
            RecordingResult.failure(
                failure = BridgeFailure.Protocol(
                    message.ifBlank { "Recording failed: $status" },
                ),
                status = status,
                transcript = transcript,
            )
        } else {
            RecordingResult.success(
                status = status,
                message = message,
                transcript = transcript,
            )
        }
    }

    private companion object {
        val recordingFailureStatuses = setOf(
            "start_failed",
            "stop_failed",
            "audio_failed",
            "audio_skipped",
            "transcript_rejected",
            "transcription_failed",
            "paste_failed",
        )
    }
}
