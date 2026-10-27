package com.example.meshsos.transport.wifi

import org.json.JSONObject
import java.nio.charset.StandardCharsets

enum class HandshakeType {
    CONNECT_REQ,
    CONNECT_RESP,
    CONNECT_CANCEL
}

enum class HandshakeStatus {
    ACCEPTED,
    REJECTED_BUSY,
    REJECTED_ERROR
}

/**
 * Lightweight, versioned discovery-channel handshake message.
 * Exchanged via [android.net.wifi.aware.DiscoverySession.sendMessage] prior to requesting
 * a Wi-Fi Aware network data path.
 *
 * Wire payload is kept under 180 bytes, well within the 255-byte limit of Wi-Fi Aware messages.
 */
data class WifiAwareHandshake(
    val version: Int = CURRENT_VERSION,
    val type: HandshakeType,
    val sessionId: String,
    val initiatorDeviceId: String? = null,
    val initiatorName: String? = null,
    val targetDeviceId: String? = null,
    val responderDeviceId: String? = null,
    val status: HandshakeStatus? = null,
    val serverPort: Int? = null,
    val reason: String? = null
) {
    fun toByteArray(): ByteArray {
        val json = JSONObject()
        json.put("v", version)
        json.put("type", type.name)
        json.put("sid", sessionId)

        initiatorDeviceId?.let { json.put("initId", it) }
        initiatorName?.let { json.put("initName", it) }
        targetDeviceId?.let { json.put("tgtId", it) }
        responderDeviceId?.let { json.put("respId", it) }
        status?.let { json.put("status", it.name) }
        serverPort?.let { json.put("port", it) }
        reason?.let { json.put("reason", it) }

        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun createConnectRequest(
            sessionId: String,
            initiatorDeviceId: String,
            initiatorName: String,
            targetDeviceId: String
        ): WifiAwareHandshake {
            return WifiAwareHandshake(
                version = CURRENT_VERSION,
                type = HandshakeType.CONNECT_REQ,
                sessionId = sessionId,
                initiatorDeviceId = initiatorDeviceId,
                initiatorName = initiatorName.take(32),
                targetDeviceId = targetDeviceId
            )
        }

        fun createConnectResponse(
            sessionId: String,
            responderDeviceId: String,
            status: HandshakeStatus,
            serverPort: Int? = null,
            reason: String? = null
        ): WifiAwareHandshake {
            return WifiAwareHandshake(
                version = CURRENT_VERSION,
                type = HandshakeType.CONNECT_RESP,
                sessionId = sessionId,
                responderDeviceId = responderDeviceId,
                status = status,
                serverPort = serverPort,
                reason = reason
            )
        }

        fun createCancel(sessionId: String, reason: String): WifiAwareHandshake {
            return WifiAwareHandshake(
                version = CURRENT_VERSION,
                type = HandshakeType.CONNECT_CANCEL,
                sessionId = sessionId,
                reason = reason
            )
        }

        fun fromByteArray(bytes: ByteArray?): WifiAwareHandshake? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                val jsonStr = String(bytes, StandardCharsets.UTF_8)
                val json = JSONObject(jsonStr)

                val version = json.optInt("v", CURRENT_VERSION)
                val typeStr = json.getString("type")
                val type = HandshakeType.valueOf(typeStr)
                val sid = json.getString("sid")

                val initId = if (json.has("initId") && !json.isNull("initId")) json.getString("initId") else null
                val initName = if (json.has("initName") && !json.isNull("initName")) json.getString("initName") else null
                val tgtId = if (json.has("tgtId") && !json.isNull("tgtId")) json.getString("tgtId") else null
                val respId = if (json.has("respId") && !json.isNull("respId")) json.getString("respId") else null

                val statusStr = if (json.has("status") && !json.isNull("status")) json.getString("status") else null
                val status = if (statusStr != null) HandshakeStatus.valueOf(statusStr) else null

                val port = if (json.has("port") && !json.isNull("port")) json.getInt("port") else null
                val reason = if (json.has("reason") && !json.isNull("reason")) json.getString("reason") else null

                WifiAwareHandshake(
                    version = version,
                    type = type,
                    sessionId = sid,
                    initiatorDeviceId = initId,
                    initiatorName = initName,
                    targetDeviceId = tgtId,
                    responderDeviceId = respId,
                    status = status,
                    serverPort = port,
                    reason = reason
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

