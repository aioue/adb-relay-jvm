package org.androidgradletools.adbrelay.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class HandshakeMessage(
    val v: Int,
    val role: String,
    val token: String,
)

internal val handshakeJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Spec 4.2: invalid types MUST be rejected. Decoding straight into [HandshakeMessage]
 * accepted "v":"1" (kotlinx coerces quoted numbers to Int), and in lenient mode also
 * "token":123 and unquoted strings, so each field's JSON type is checked here.
 */
internal fun parseHandshake(text: String): HandshakeMessage? =
    try {
        val obj = handshakeJson.parseToJsonElement(text) as? JsonObject
        val v = obj?.get("v") as? JsonPrimitive
        val role = obj?.get("role") as? JsonPrimitive
        val token = obj?.get("token") as? JsonPrimitive
        if (v == null || v.isString || role == null || !role.isString || token == null || !token.isString) {
            null
        } else {
            v.intOrNull?.let { HandshakeMessage(v = it, role = role.content, token = token.content) }
        }
    } catch (_: Exception) {
        null
    }

internal fun HandshakeMessage.isValid(): Boolean =
    v == 1 &&
        token.isNotEmpty() &&
        (role == "dev" || role == "device")
