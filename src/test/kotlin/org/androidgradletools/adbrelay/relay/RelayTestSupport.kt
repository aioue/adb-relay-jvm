package org.androidgradletools.adbrelay.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.net.ServerSocket
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Runs [block] against a real relay on a free loopback port, built by [module]. */
internal fun withRelay(
    module: Application.() -> Unit,
    block: suspend (port: Int, client: HttpClient) -> Unit,
) {
    val port = ServerSocket(0).use { it.localPort }
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port, module = module).start(wait = false)
    val client = HttpClient(CIO) { install(WebSockets) }
    try {
        runBlocking { block(port, client) }
    } finally {
        client.close()
        server.stop(100, 1_000)
    }
}

internal fun handshake(role: String, token: String): Frame.Text =
    Frame.Text("""{"v":1,"role":"$role","token":"$token"}""")

/** A client that answers pings automatically and has sent its handshake. */
internal suspend fun HttpClient.relayClient(port: Int, role: String, token: String): WebSocketSession =
    webSocketSession(host = "127.0.0.1", port = port, path = "/").also { it.send(handshake(role, token)) }

/**
 * Reads [session] until the server closes it. Returns false if it is still open after
 * [millis]. Frames that arrive first are discarded.
 */
internal suspend fun closedWithin(session: WebSocketSession, millis: Long): Boolean =
    withTimeoutOrNull(millis) {
        try {
            while (true) {
                if (session.incoming.receive() is Frame.Close) break
            }
        } catch (_: ClosedReceiveChannelException) {
        }
        true
    } ?: false
