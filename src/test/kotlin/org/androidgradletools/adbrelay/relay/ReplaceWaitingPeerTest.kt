package org.androidgradletools.adbrelay.relay

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class ReplaceWaitingPeerTest {

    private suspend fun assertReplaced(old: WebSocketSession) {
        val reason = withTimeout(3_000) { (old as DefaultClientWebSocketSession).closeReason.await() }
        assertEquals(CloseReason.Codes.NORMAL.code, reason?.code)
        assertEquals("replaced by a newer connection", reason?.message)
    }

    private suspend fun assertForwards(from: WebSocketSession, to: WebSocketSession, bytes: ByteArray) {
        from.send(Frame.Binary(true, bytes))
        val got = withTimeout(3_000) { (to.incoming.receive() as Frame.Binary).readBytes() }
        assertContentEquals(bytes, got)
    }

    @Test
    fun newerDevReplacesWaitingDev() = withRelay({ adbRelayModule() }) { port, client ->
        val dev1 = client.relayClient(port, "dev", "t")
        delay(300)
        val dev2 = client.relayClient(port, "dev", "t")
        assertReplaced(dev1)

        val device = client.relayClient(port, "device", "t")
        assertForwards(device, dev2, byteArrayOf(1, 2, 3))
        assertForwards(dev2, device, byteArrayOf(4, 5, 6))
    }

    @Test
    fun newerDeviceReplacesWaitingDevice() = withRelay({ adbRelayModule() }) { port, client ->
        val device1 = client.relayClient(port, "device", "t")
        delay(300)
        val device2 = client.relayClient(port, "device", "t")
        assertReplaced(device1)

        val dev = client.relayClient(port, "dev", "t")
        assertForwards(dev, device2, byteArrayOf(7, 8, 9))
    }
}
