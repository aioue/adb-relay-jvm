package org.androidgradletools.adbrelay.relay

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.withTimeout

class MessageOrderTest {

    private suspend fun assertInOrder(from: WebSocketSession, to: WebSocketSession, count: Int) {
        repeat(count) { from.send(Frame.Binary(true, ByteBuffer.allocate(4).putInt(it).array())) }
        val got = withTimeout(10_000) {
            List(count) { ByteBuffer.wrap((to.incoming.receive() as Frame.Binary).readBytes()).int }
        }
        assertEquals((0 until count).toList(), got)
    }

    @Test
    fun forwardsDevMessagesInOrder() = withRelay({ adbRelayModule() }) { port, client ->
        val dev = client.relayClient(port, "dev", "order-1")
        val device = client.relayClient(port, "device", "order-1")
        assertInOrder(dev, device, 500)
    }

    @Test
    fun forwardsDeviceMessagesInOrder() = withRelay({ adbRelayModule() }) { port, client ->
        val device = client.relayClient(port, "device", "order-2")
        val dev = client.relayClient(port, "dev", "order-2")
        assertInOrder(device, dev, 500)
    }
}
