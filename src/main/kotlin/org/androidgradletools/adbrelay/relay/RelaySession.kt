package org.androidgradletools.adbrelay.relay

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.server.routing.Routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import io.ktor.websocket.readBytes

/**
 * WebSocket route compatible with [android-ide-extension/tools/remote-adb-relay/relay.cjs]:
 * first frame UTF-8 JSON handshake, then binary mux bytes forwarded to the paired peer.
 */
internal fun Routing.relayWebSocketRoute(coordinator: RelayCoordinator) {
    webSocket("/") {
        handleRelayWebSocket(coordinator)
    }
}

private suspend fun DefaultWebSocketServerSession.handleRelayWebSocket(coordinator: RelayCoordinator) {
    val first = try {
        incoming.receive()
    } catch (_: ClosedReceiveChannelException) {
        return
    }
    if (first !is Frame.Text) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "first frame must be text JSON handshake"))
        return
    }
    val handshake = parseHandshake(first.readText())?.takeIf { it.isValid() } ?: run {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid handshake"))
        return
    }
    val pending = RelayCoordinator.Pending(this)
    val stillWaiting = coordinator.registerOrMatch(handshake.role, handshake.token, pending)
    try {
        if (stillWaiting) {
            val closedWhileWaiting = waitForPeerWithBuffering(pending)
            if (closedWhileWaiting || !pending.peerFuture.isCompleted) {
                return
            }
        }
        val peer = pending.peerFuture.await()
        pending.drainBufferTo(peer)
        // Each side's handler forwards only its own incoming frames. Both handlers
        // used to bridge both directions, so two coroutines read each incoming
        // channel and their sends to the peer interleaved out of order.
        try {
            forwardBinaryFrames(this, peer)
        } finally {
            runCatching { peer.close() }
        }
    } finally {
        coordinator.removePendingWebSocket(handshake.token, handshake.role, this)
    }
}

/** @return true if the client closed or the socket was ended before pairing completed. */
private suspend fun DefaultWebSocketServerSession.waitForPeerWithBuffering(
    pending: RelayCoordinator.Pending,
): Boolean {
    while (!pending.peerFuture.isCompleted) {
        var closed = false
        select<Unit> {
            incoming.onReceive { frame ->
                when (frame) {
                    is Frame.Binary -> {
                        if (!pending.addChunk(frame.readBytes())) {
                            close(CloseReason(CloseReason.Codes.TOO_BIG, "pre-pairing buffer limit exceeded"))
                            closed = true
                        }
                    }
                    is Frame.Close -> {
                        close(CloseReason(CloseReason.Codes.NORMAL, "closed while waiting"))
                        closed = true
                    }
                    is Frame.Ping -> Unit
                    is Frame.Pong -> Unit
                    else -> {
                        close(
                            CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "only binary frames after handshake"),
                        )
                        closed = true
                    }
                }
            }
            pending.peerFuture.onAwait { }
        }
        if (closed) {
            return true
        }
    }
    return false
}

private suspend fun forwardBinaryFrames(from: DefaultWebSocketServerSession, to: DefaultWebSocketServerSession) {
    try {
        for (frame in from.incoming) {
            when (frame) {
                is Frame.Binary -> {
                    if (to.isActive) {
                        to.send(Frame.Binary(fin = true, data = frame.readBytes()))
                    }
                }
                is Frame.Close -> {
                    to.close()
                    return
                }
                is Frame.Ping -> Unit
                is Frame.Pong -> Unit
                else -> Unit
            }
        }
    } catch (_: ClosedReceiveChannelException) {
        // normal shutdown
    }
}
