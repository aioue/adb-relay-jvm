package org.androidgradletools.adbrelay.relay

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets

/**
 * JVM relay compatible with the Node relay in **android-ide-extension** (`relay.cjs`).
 *
 * @param host Bind address (default `0.0.0.0` — all interfaces, same idea as Node `ws` `WebSocketServer({ port })`; use `127.0.0.1` for local-only).
 * @param port TCP port (default [DEFAULT_PORT]; CLI entrypoint also reads `RELAY_PORT`).
 */
class AdbRelayServer(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
) {
    private val coordinator = RelayCoordinator()
    private var engine: NettyApplicationEngine? = null

    /** Start Netty + WebSocket relay; blocks when [wait] is true. */
    fun start(wait: Boolean = true) {
        check(engine == null) { "server already started" }
        engine = embeddedServer(Netty, host = host, port = port) {
            relayModule(coordinator)
        }
        engine!!.start(wait = wait)
    }

    fun stop(gracePeriodMillis: Long = 1000, timeoutMillis: Long = 5000) {
        engine?.stop(gracePeriodMillis, timeoutMillis)
        engine = null
    }

    companion object {
        const val DEFAULT_HOST: String = "0.0.0.0"
        const val DEFAULT_PORT: Int = 18765
    }
}

/**
 * Install the relay into an existing Ktor [Application] (single shared coordinator per call).
 */
fun Application.adbRelayModule() {
    relayModule(RelayCoordinator())
}

/** Server ping interval; a peer that misses a pong for [PING_TIMEOUT_MILLIS] is closed. */
internal const val PING_PERIOD_MILLIS: Long = 15_000L
internal const val PING_TIMEOUT_MILLIS: Long = 15_000L

internal fun Application.relayModule(
    coordinator: RelayCoordinator,
    pingPeriodMillis: Long = PING_PERIOD_MILLIS,
    pingTimeoutMillis: Long = PING_TIMEOUT_MILLIS,
) {
    install(WebSockets) {
        maxFrameSize = 256L * 1024L * 1024L
        // Without pings, a peer that disappears without closing (a phone changing networks)
        // stays paired until TCP gives up, and its reconnect waits for a peer that is taken.
        this.pingPeriodMillis = pingPeriodMillis
        timeoutMillis = pingTimeoutMillis
    }
    routing {
        relayWebSocketRoute(coordinator)
    }
}
