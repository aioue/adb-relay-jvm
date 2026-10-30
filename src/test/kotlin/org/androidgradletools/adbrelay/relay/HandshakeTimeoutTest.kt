package org.androidgradletools.adbrelay.relay

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.withTimeout

class HandshakeTimeoutTest {

    @Test
    fun closesSocketThatNeverSendsHandshake() = withRelay({
        relayModule(RelayCoordinator(), handshakeTimeoutMillis = 500)
    }) { port, client ->
        val s = client.webSocketSession(host = "127.0.0.1", port = port, path = "/")
        val reason = withTimeout(3_000) { (s as DefaultClientWebSocketSession).closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        assertEquals("handshake timeout", reason?.message)
    }

    @Test
    fun keepsSocketThatHandshakesInTime() = withRelay({
        relayModule(RelayCoordinator(), handshakeTimeoutMillis = 500)
    }) { port, client ->
        val s = client.relayClient(port, "dev", "waiting-for-device")
        assertFalse(closedWithin(s, 1_500), "a handshaked socket waiting for its peer was closed")
    }
}
