package com.vibestick.android.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val discoveryWindowMillis = 1_400L
private const val discoveryPacketBytes = 2_048

internal fun parseDiscoveryResponse(
    senderHost: String,
    payload: String,
): BridgeCandidate? {
    val host = senderHost.trim()
    if (host.isEmpty()) return null

    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    if (json.opt("type") !is String || json.optString("type") != "vibestick_bridge") {
        return null
    }

    val advertisedPort = (json.opt("port") as? Number)?.toInt()
    val port = advertisedPort
        ?.takeIf { it in 1..65_535 }
        ?: BridgeProtocol.defaultHttpPort
    val name = (json.opt("name") as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: host
    val version = (json.opt("version") as? String)?.trim().orEmpty()

    return BridgeCandidate(
        host = host,
        port = port,
        name = name,
        version = version,
    )
}

interface BridgeScanner {
    suspend fun discover(
        token: String,
        lastKnownHost: String? = null,
    ): List<BridgeCandidate>
}

class BridgeDiscovery(
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : BridgeScanner {
    override suspend fun discover(
        token: String,
        lastKnownHost: String?,
    ): List<BridgeCandidate> = withContext(Dispatchers.IO) {
        socketFactory().use { socket ->
            socket.broadcast = true
            val request = BridgeProtocol.discoveryRequest(token)
            val targets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                lastKnownHost
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                    ?.takeIf { it.hostAddress != "255.255.255.255" }
                    ?.let(::add)
            }

            targets.forEach { address ->
                val packet = DatagramPacket(
                    request,
                    request.size,
                    address,
                    BridgeProtocol.discoveryPort,
                )
                runCatching { socket.send(packet) }
            }

            val candidates = linkedMapOf<String, BridgeCandidate>()
            val deadlineNanos = System.nanoTime() + discoveryWindowMillis * 1_000_000L
            while (true) {
                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) break
                socket.soTimeout = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                val buffer = ByteArray(discoveryPacketBytes)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    break
                }

                val host = response.address?.hostAddress.orEmpty()
                val payload = response.data.decodeToString(
                    startIndex = response.offset,
                    endIndex = response.offset + response.length,
                )
                val candidate = parseDiscoveryResponse(host, payload) ?: continue
                candidates.putIfAbsent("${candidate.host}:${candidate.port}", candidate)
            }

            candidates.values.sortedWith(
                compareBy<BridgeCandidate>(
                    { it.name.lowercase(Locale.ROOT) },
                    { it.host },
                    { it.port },
                ),
            )
        }
    }
}
