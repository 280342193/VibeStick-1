package com.vibestick.android.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHttpClientTest {
    private val candidate = BridgeCandidate(
        host = "192.168.1.40",
        port = 8765,
        name = "Desk PC",
        version = "0.1.5",
    )

    @Test
    fun stateRequestParsesBodyAndUsesExpectedTimeouts() = runTest {
        val connection = FakeHttpURLConnection(
            responseCode = 200,
            responseBody =
                """{"computer_name":"Desk PC","codex":{"status":"RUNNING"},"alert":{"type":"NONE"}}""",
        )
        val client = clientWith(connection, token = "shared-secret")

        val result = client.getState()

        assertTrue(result is BridgeCallResult.Success)
        assertEquals(
            "Desk PC",
            (result as BridgeCallResult.Success).value.computerName,
        )
        assertEquals("GET", connection.requestMethod)
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertNull(connection.getRequestProperty("X-Vibe-Stick-Token"))
    }

    @Test
    fun audioUploadSetsPcmHeadersTokenAndRawBody() = runTest {
        val connection = FakeHttpURLConnection(
            responseCode = 200,
            responseBody =
                """{"recording":{"status":"recording","message":"uploaded"},"state":{}}""",
        )
        var requestedUrl: URL? = null
        val client = BridgeHttpClient(candidate, token = "shared-secret") { url ->
            requestedUrl = url
            connection
        }
        val pcm = byteArrayOf(0, 1, 2, 3)

        val result = client.uploadPcm("session one", pcm)

        assertTrue(result.isSuccess)
        assertEquals(
            "/recording/audio?session_id=session%20one",
            requestedUrl?.file,
        )
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/octet-stream", connection.getRequestProperty("Content-Type"))
        assertEquals("16000", connection.getRequestProperty("X-Vibe-Stick-Sample-Rate"))
        assertEquals("1", connection.getRequestProperty("X-Vibe-Stick-Channels"))
        assertEquals("16", connection.getRequestProperty("X-Vibe-Stick-Bits-Per-Sample"))
        assertEquals("shared-secret", connection.getRequestProperty("X-Vibe-Stick-Token"))
        assertArrayEquals(pcm, connection.requestBody.toByteArray())
    }

    @Test
    fun nonSuccessResponseUsesStructuredJsonError() = runTest {
        val connection = FakeHttpURLConnection(
            responseCode = 401,
            responseBody = """{"error":"Unauthorized"}""",
        )

        val result = clientWith(connection, token = "wrong-token")
            .startRecording("session-123")

        assertFalse(result.isSuccess)
        assertEquals(
            BridgeFailure.Http(401, "Unauthorized"),
            result.failure,
        )
    }

    @Test
    fun recordingFailureInsideHttp200IsNotReportedAsSuccess() = runTest {
        val connection = FakeHttpURLConnection(
            responseCode = 200,
            responseBody =
                """{"recording":{"status":"paste_failed","message":"focus lost","transcript":"hello"},"state":{}}""",
        )

        val result = clientWith(connection).stopRecording(text = null, paste = true)

        assertFalse(result.isSuccess)
        assertEquals("paste_failed", result.status)
        assertEquals("hello", result.transcript)
        assertEquals(BridgeFailure.Protocol("focus lost"), result.failure)
    }

    @Test
    fun recordingStopAllowsBridgeMaximumTranscriptionTime() = runTest {
        val connection = FakeHttpURLConnection(
            responseCode = 200,
            responseBody =
                """{"recording":{"status":"pasted","message":"done"},"state":{}}""",
        )

        val result = clientWith(connection).stopRecording(text = null, paste = true)

        assertTrue(result.isSuccess)
        assertEquals(630_000, connection.readTimeout)
    }

    private fun clientWith(
        connection: FakeHttpURLConnection,
        token: String = "",
    ): BridgeHttpClient = BridgeHttpClient(candidate, token) { connection }
}

private class FakeHttpURLConnection(
    responseCode: Int,
    responseBody: String,
) : HttpURLConnection(URL("http://127.0.0.1/")) {
    private val code = responseCode
    private val body = responseBody.encodeToByteArray()
    val requestBody = ByteArrayOutputStream()

    override fun connect() {
        connected = true
    }

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = code

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)

    override fun getErrorStream(): InputStream = ByteArrayInputStream(body)

    override fun getOutputStream(): ByteArrayOutputStream = requestBody
}
