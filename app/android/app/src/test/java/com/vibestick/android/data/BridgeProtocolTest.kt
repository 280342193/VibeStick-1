package com.vibestick.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun discoveryRequestMatchesStickS3Contract() {
        val payload = JSONObject(BridgeProtocol.discoveryRequest("shared-secret").decodeToString())

        assertEquals(8766, BridgeProtocol.discoveryPort)
        assertEquals(8765, BridgeProtocol.defaultHttpPort)
        assertEquals("vibestick_discover", payload.getString("type"))
        assertEquals("VibeStick", payload.getString("device"))
        assertEquals("shared-secret", payload.getString("token"))
    }

    @Test
    fun recordingPayloadsKeepVoiceAndManualSemanticsSeparate() {
        val start = JSONObject(BridgeProtocol.startRecordingBody("session-1").decodeToString())
        val voiceStop = JSONObject(BridgeProtocol.stopVoiceBody().decodeToString())
        val textStop = JSONObject(
            BridgeProtocol.stopTextBody("run \"all\" tests\nnow").decodeToString(),
        )
        val enter = JSONObject(BridgeProtocol.enterEventBody().decodeToString())

        assertEquals("sticks3_pcm", start.getString("audio_source"))
        assertEquals("session-1", start.getString("session_id"))
        assertTrue(voiceStop.getBoolean("paste"))
        assertFalse(voiceStop.has("text"))
        assertEquals("run \"all\" tests\nnow", textStop.getString("text"))
        assertTrue(textStop.getBoolean("paste"))
        assertEquals("button_short", enter.getString("event"))
        assertEquals("android_text_send", enter.getString("source"))
    }

    @Test
    fun audioPathEscapesSessionId() {
        assertEquals(
            "/recording/audio?session_id=session%20one",
            BridgeProtocol.recordingAudioPath("session one"),
        )
    }
}
