package org.androidgradletools.adbrelay.relay

import io.ktor.client.plugins.websocket.cio.webSocketRawSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ServerPingTest {

    private val fastPings: io.ktor.server.application.Application.() -> Unit = {
        relayModule(RelayCoordinator(), pingPeriodMillis = 200, pingTimeoutMillis = 500)
    }

    @Test
    fun closesPeerThatStopsAnsweringAndReleasesItsPartner() = withRelay(fastPings) { port, client ->
        val dev = client.relayClient(port, "dev", "t")
        // A raw session never answers pings, like a phone whose connection died silently.
        val device = client.webSocketRawSession(host = "127.0.0.1", port = port, path = "/")
        device.send(handshake("device", "t"))

        var pings = 0
        val closed = withTimeoutOrNull(3_000) {
            try {
                while (true) {
                    when (device.incoming.receive()) {
                        is Frame.Ping -> pings++
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
            }
            true
        } ?: false
        assertTrue(pings > 0, "server sent no pings")
        assertTrue(closed, "server did not close a peer that stopped answering pings")
        assertTrue(closedWithin(dev, 3_000), "dev was left paired to the dead device")
    }

    @Test
    fun keepsPeersThatAnswerPings() = withRelay(fastPings) { port, client ->
        val dev = client.relayClient(port, "dev", "t")
        val device = client.relayClient(port, "device", "t")
        delay(1_500) // several ping periods and timeouts
        device.send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
        val got = withTimeout(3_000) { (dev.incoming.receive() as Frame.Binary).readBytes() }
        assertContentEquals(byteArrayOf(1, 2, 3), got)
        assertFalse(closedWithin(device, 500), "a peer that answers pings was closed")
    }
}
