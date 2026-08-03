package com.vibestick.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeModelsTest {
    @Test
    fun stateParserPreservesAlertAndProvider() {
        val state = BridgeState.fromJson(
            JSONObject(
                """
                {
                  "computer_name": "Desk PC",
                  "active_provider": "codex",
                  "provider": {
                    "id": "codex",
                    "display_name": "Codex",
                    "status": "DONE",
                    "quota_5h_remaining": 64,
                    "quota_7d_remaining": 91
                  },
                  "codex": {"status": "DONE"},
                  "alert": {
                    "event_id": "done-42",
                    "type": "DONE",
                    "message": "finished"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Desk PC", state.computerName)
        assertEquals("codex", state.activeProvider)
        assertEquals("Codex", state.provider.displayName)
        assertEquals(AgentStatus.DONE, state.provider.status)
        assertEquals(AgentStatus.DONE, state.codexStatus)
        assertEquals(64, state.provider.quota5hRemaining)
        assertEquals(91, state.provider.quota7dRemaining)
        assertEquals(Alert("done-42", AlertType.DONE, "finished"), state.alert)
        assertTrue(state.alert.isTerminal)
    }

    @Test
    fun missingAndNullFieldsUseSafeDefaults() {
        val state = BridgeState.fromJson(
            JSONObject(
                """
                {
                  "provider": {
                    "quota_5h_remaining": null,
                    "quota_7d_remaining": null
                  },
                  "alert": {"type": "NONE"}
                }
                """.trimIndent(),
            ),
        )

        assertEquals("", state.computerName)
        assertEquals(AgentStatus.UNKNOWN, state.provider.status)
        assertNull(state.provider.quota5hRemaining)
        assertNull(state.provider.quota7dRemaining)
        assertEquals(AlertType.NONE, state.alert.type)
        assertFalse(state.alert.isTerminal)
    }

    @Test
    fun unknownStatusAndAlertDoNotThrow() {
        val state = BridgeState.fromJson(
            JSONObject(
                """
                {
                  "provider": {"status": "SOMETHING_NEW"},
                  "codex": {"status": "FUTURE_STATE"},
                  "alert": {"event_id": "future-1", "type": "FUTURE_ALERT"}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(AgentStatus.UNKNOWN, state.provider.status)
        assertEquals(AgentStatus.UNKNOWN, state.codexStatus)
        assertEquals(AlertType.NONE, state.alert.type)
        assertFalse(state.alert.isTerminal)
    }

    @Test
    fun operationAndConnectionModelsPreserveTypedFailures() {
        val candidate = BridgeCandidate(
            host = "192.168.1.40",
            port = 8765,
            name = "Desk PC",
            version = "1.0",
        )
        val failure = BridgeFailure.Http(statusCode = 401, message = "invalid token")
        val result = RecordingResult.failure(failure)

        assertFalse(result.isSuccess)
        assertEquals(failure, result.failure)
        assertEquals(ConnectionState.Connected(candidate), ConnectionState.Connected(candidate))
        assertTrue(RecordingResult.success().isSuccess)
    }
}
