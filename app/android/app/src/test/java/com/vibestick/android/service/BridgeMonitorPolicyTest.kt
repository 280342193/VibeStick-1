package com.vibestick.android.service

import com.vibestick.android.data.Alert
import com.vibestick.android.data.AlertType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMonitorPolicyTest {
    @Test
    fun firstRefreshDeliversTerminalAlertNotPreviouslyShown() {
        val policy = BridgeMonitorAlertPolicy(lastDeliveredEventId = "done-1")
        val unseen = Alert("done-2", AlertType.DONE, "Task completed")

        assertTrue(policy.shouldNotify(unseen))
        policy.markDelivered(unseen)
        assertFalse(policy.shouldNotify(unseen))
    }

    @Test
    fun networkFailureBackoffUsesFiveTenThenThirtySeconds() {
        assertEquals(5_000L, retryDelayMillis(1))
        assertEquals(10_000L, retryDelayMillis(2))
        assertEquals(30_000L, retryDelayMillis(3))
        assertEquals(30_000L, retryDelayMillis(9))
    }

    @Test
    fun thirdAndLaterNetworkFailuresTriggerLanRediscovery() {
        assertFalse(shouldRediscover(1))
        assertFalse(shouldRediscover(2))
        assertTrue(shouldRediscover(3))
        assertTrue(shouldRediscover(9))
    }

    @Test
    fun terminalAlertTypesMapToNotificationKinds() {
        assertEquals(TaskAlertKind.COMPLETED, taskAlertKind(AlertType.DONE))
        assertEquals(TaskAlertKind.FAILED, taskAlertKind(AlertType.ERROR))
        assertEquals(TaskAlertKind.APPROVAL, taskAlertKind(AlertType.APPROVAL))
        assertNull(taskAlertKind(AlertType.NONE))
    }
}
