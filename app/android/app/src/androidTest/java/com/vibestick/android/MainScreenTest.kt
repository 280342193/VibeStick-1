package com.vibestick.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vibestick.android.data.AgentStatus
import com.vibestick.android.data.BridgeCandidate
import com.vibestick.android.data.BusyAction
import com.vibestick.android.data.ConnectionState
import com.vibestick.android.ui.ActionState
import com.vibestick.android.ui.MainScreen
import com.vibestick.android.ui.UiState
import com.vibestick.android.ui.VibeStickTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lowerActionLayoutKeepsInputBelowVoiceAndSendToTheRight() {
        composeRule.setContent {
            VibeStickTheme {
                MainScreen(state = connectedState())
            }
        }

        composeRule.onNodeWithTag("manual_input").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_button").assertIsDisplayed()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed()

        val input = composeRule.onNodeWithTag("manual_input").fetchSemanticsNode().boundsInRoot
        val voice = composeRule.onNodeWithTag("voice_button").fetchSemanticsNode().boundsInRoot
        val send = composeRule.onNodeWithTag("send_button").fetchSemanticsNode().boundsInRoot
        assertTrue(input.top > voice.bottom)
        assertTrue(send.left > voice.center.x)
    }

    @Test
    fun blankInputIsDisabledAndTypingEnablesSendWhenConnected() {
        composeRule.setContent {
            var state by remember { mutableStateOf(connectedState()) }
            VibeStickTheme {
                MainScreen(
                    state = state,
                    onTextChange = { state = state.copy(manualText = it) },
                )
            }
        }

        composeRule.onNodeWithTag("send_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("manual_input").performTextInput("run tests")
        composeRule.onNodeWithTag("send_button").assertIsEnabled()
    }

    @Test
    fun busyStateDisablesRepeatedSend() {
        var sends = 0
        composeRule.setContent {
            var state by remember { mutableStateOf(connectedState().copy(manualText = "once")) }
            VibeStickTheme {
                MainScreen(
                    state = state,
                    onSend = {
                        sends += 1
                        state = state.copy(busyAction = BusyAction.SENDING_TEXT)
                    },
                )
            }
        }

        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.onNodeWithTag("send_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("send_button").performClick()
        composeRule.runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun errorStateKeepsTypedTextVisible() {
        composeRule.setContent {
            VibeStickTheme {
                MainScreen(
                    state = connectedState().copy(
                        manualText = "keep this",
                        actionState = ActionState.ERROR,
                        statusMessage = "paste failed",
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("manual_input").assertTextContains("keep this")
    }
}

private fun connectedState(): UiState {
    val candidate = BridgeCandidate("192.168.1.20", 8765, "Desk", "0.1.5")
    return UiState(
        connection = ConnectionState.Connected(candidate),
        computerName = "Desk",
        providerName = "Codex",
        taskStatus = AgentStatus.IDLE,
    )
}
