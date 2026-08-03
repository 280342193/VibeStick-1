package com.vibestick.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeDiscoveryTest {
    @Test
    fun responseUsesSenderIpAndAdvertisedPort() {
        val candidate = parseDiscoveryResponse(
            senderHost = "192.168.1.20",
            payload =
                """{"type":"vibestick_bridge","name":"Desk PC","port":8765,"version":"0.1.5"}""",
        )

        assertEquals(
            BridgeCandidate("192.168.1.20", 8765, "Desk PC", "0.1.5"),
            candidate,
        )
    }

    @Test
    fun missingPortAndNameUseFirmwareCompatibleDefaults() {
        val candidate = parseDiscoveryResponse(
            senderHost = "192.168.1.21",
            payload = """{"type":"vibestick_bridge"}""",
        )

        assertEquals(
            BridgeCandidate("192.168.1.21", 8765, "192.168.1.21", ""),
            candidate,
        )
    }

    @Test
    fun invalidPacketsAreRejected() {
        assertNull(parseDiscoveryResponse("192.168.1.22", "not-json"))
        assertNull(
            parseDiscoveryResponse(
                "192.168.1.22",
                """{"type":"another_service","port":8765}""",
            ),
        )
        assertNull(
            parseDiscoveryResponse(
                "",
                """{"type":"vibestick_bridge","port":8765}""",
            ),
        )
    }
}
