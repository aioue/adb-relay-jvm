package org.androidgradletools.adbrelay.relay

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Spec 4.2: invalid types (e.g. v as a string) MUST be rejected. */
class HandshakeTypesTest {

    private fun assertRejected(json: String) = withRelay({ adbRelayModule() }) { port, client ->
        val s = client.webSocketSession(host = "127.0.0.1", port = port, path = "/")
        s.send(Frame.Text(json))
        val reason = withTimeoutOrNull(3_000) { (s as DefaultClientWebSocketSession).closeReason.await() }
        assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code, "accepted $json")
    }

    @Test
    fun rejectsVersionAsString() = assertRejected("""{"v":"1","role":"dev","token":"t"}""")

    @Test
    fun rejectsVersionAsBoolean() = assertRejected("""{"v":true,"role":"dev","token":"t"}""")

    @Test
    fun rejectsTokenAsNumber() = assertRejected("""{"v":1,"role":"dev","token":123}""")

    @Test
    fun rejectsRoleAsUnquotedWord() = assertRejected("""{"v":1,"role":dev,"token":"t"}""")

    @Test
    fun acceptsValidHandshakeWithUnknownKeys() = withRelay({ adbRelayModule() }) { port, client ->
        val dev = client.webSocketSession(host = "127.0.0.1", port = port, path = "/")
        dev.send(Frame.Text("""{"v":1,"role":"dev","token":"ok","client":"test"}"""))
        val device = client.relayClient(port, "device", "ok")
        device.send(Frame.Binary(true, byteArrayOf(1)))
        val got = withTimeout(3_000) { (dev.incoming.receive() as Frame.Binary).readBytes() }
        assertContentEquals(byteArrayOf(1), got)
    }
}
