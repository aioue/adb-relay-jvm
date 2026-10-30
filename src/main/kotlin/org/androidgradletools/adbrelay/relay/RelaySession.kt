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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.websocket.readBytes

/**
 * WebSocket route compatible with [android-ide-extension/tools/remote-adb-relay/relay.cjs]:
 * first frame UTF-8 JSON handshake, then binary mux bytes forwarded to the paired peer.
 */
internal fun Routing.relayWebSocketRoute(
    coordinator: RelayCoordinator,
    handshakeTimeoutMillis: Long = HANDSHAKE_TIMEOUT_MILLIS,
) {
    webSocket("/") {
        handleRelayWebSocket(coordinator, handshakeTimeoutMillis)
    }
}

/** How long a new socket may take to send its JSON handshake before the relay closes it. */
internal const val HANDSHAKE_TIMEOUT_MILLIS: Long = 30_000L

private suspend fun DefaultWebSocketServerSession.handleRelayWebSocket(
    coordinator: RelayCoordinator,
    handshakeTimeoutMillis: Long,
) {
    // Without a deadline, a socket that never sends its handshake stays open indefinitely.
    val first = try {
        withTimeoutOrNull(handshakeTimeoutMillis) { incoming.receive() }
    } catch (_: ClosedReceiveChannelException) {
        return
    }
    if (first == null) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "handshake timeout"))
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
        runPairedBridge(this, peer)
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

private suspend fun runPairedBridge(a: DefaultWebSocketServerSession, b: DefaultWebSocketServerSession) {
    coroutineScope {
        launch {
            try {
                forwardBinaryFrames(a, b)
            } finally {
                runCatching { b.close() }
            }
        }
        launch {
            try {
                forwardBinaryFrames(b, a)
            } finally {
                runCatching { a.close() }
            }
        }
    }
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
