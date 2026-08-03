package com.vibestick.android.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object BridgeProtocol {
    const val discoveryPort = 8766
    const val defaultHttpPort = 8765
    const val statePath = "/state"
    const val textInputPath = "/input/text"
    const val recordingStartPath = "/recording/start"
    const val recordingStopPath = "/recording/stop"
    const val eventPath = "/event"
    const val firmwareName = "vibestick-android"
    const val transport = "HTTP"

    fun discoveryRequest(token: String): ByteArray =
        JSONObject()
            .put("type", "vibestick_discover")
            .put("token", token)
            .put("device", "VibeStick")
            .toString()
            .encodeToByteArray()

    fun startRecordingBody(sessionId: String): ByteArray =
        JSONObject()
            .put("event", "button_long_start")
            .put("source", "android")
            .put("audio_source", "sticks3_pcm")
            .put("session_id", sessionId)
            .toString()
            .encodeToByteArray()

    fun stopVoiceBody(paste: Boolean = true): ByteArray =
        JSONObject()
            .put("event", "button_long_stop")
            .put("source", "android")
            .put("paste", paste)
            .toString()
            .encodeToByteArray()

    fun stopTextBody(text: String, paste: Boolean = true): ByteArray =
        JSONObject()
            .put("event", "button_long_stop")
            .put("source", "android_text")
            .put("text", text)
            .put("paste", paste)
            .toString()
            .encodeToByteArray()

    fun enterEventBody(): ByteArray =
        JSONObject()
            .put("event", "button_short")
            .put("source", "android_text_send")
            .toString()
            .encodeToByteArray()

    fun textInputBody(text: String): ByteArray =
        JSONObject()
            .put("text", text)
            .put("submit", true)
            .put("source", "android")
            .toString()
            .encodeToByteArray()

    fun authCheckEventBody(): ByteArray =
        JSONObject()
            .put("event", "android_auth_check")
            .put("source", "android")
            .toString()
            .encodeToByteArray()

    fun recordingAudioPath(sessionId: String): String {
        val encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "/recording/audio?session_id=$encoded"
    }

    fun recordingCompletePath(sessionId: String): String {
        val encoded = URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "/recording/complete?session_id=$encoded"
    }
}
