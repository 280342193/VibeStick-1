package com.vibestick.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDeduplicatorTest {
    @Test
    fun terminalEventIsDeliveredOnce() {
        val dedupe = AlertDeduplicator("")
        val alert = Alert("done-1", AlertType.DONE, "done")

        assertTrue(dedupe.shouldNotify(alert))
        dedupe.markDelivered(alert)
        assertFalse(dedupe.shouldNotify(alert))
        assertEquals("done-1", dedupe.deliveredEventId)
    }

    @Test
    fun emptyAndNoneAlertsAreIgnored() {
        val dedupe = AlertDeduplicator("")

        assertFalse(dedupe.shouldNotify(Alert("", AlertType.NONE, "")))
        assertFalse(dedupe.shouldNotify(Alert("none-1", AlertType.NONE, "")))
    }

    @Test
    fun firstConnectionCanSeedCurrentAlertWithoutReplayingIt() {
        val dedupe = AlertDeduplicator("done-1")
        val current = Alert("done-2", AlertType.DONE, "already finished")

        dedupe.seed(current)

        assertFalse(dedupe.shouldNotify(current))
        assertEquals("done-2", dedupe.deliveredEventId)
    }

    @Test
    fun failedDispatchCanRetrySameEvent() {
        val dedupe = AlertDeduplicator("")
        val alert = Alert("approval-1", AlertType.APPROVAL, "confirm")

        assertTrue(dedupe.shouldNotify(alert))
        dedupe.markDeliveryFailed(alert)

        assertTrue(dedupe.shouldNotify(alert))
    }
}
