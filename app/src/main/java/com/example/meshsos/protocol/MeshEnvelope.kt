package com.example.meshsos.protocol

import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Versioned mesh network envelope wrapping an Offgrid SOS payload.
 *
 * Provides multi-hop routing, hop-count bounding, deduplication identity,
 * and ingress-aware relaying while remaining backward compatible with legacy
 * un-enveloped SosMessage frames.
 */
data class MeshEnvelope(
    val version: Int = CURRENT_VERSION,
    val messageId: String = UUID.randomUUID().toString(),
    val originDeviceId: String,
    val originDeviceName: String? = null,
    val destinationDeviceId: String? = null, // null indicates broadcast to all reachable peers
    val messageType: MessageType = MessageType.SOS,
    val hopCount: Int = 0,
    val hopLimit: Int = DEFAULT_HOP_LIMIT,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    val ackForMessageId: String? = null,
    val payloadJson: String = ""
) {

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_HOP_LIMIT = 5
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

        /**
         * Wraps an existing SosMessage into a MeshEnvelope.
         */
        fun fromSosMessage(
            sosMessage: SosMessage,
            originDeviceId: String = sosMessage.senderId,
            originDeviceName: String? = sosMessage.senderName,
            destinationDeviceId: String? = null,
            hopLimit: Int = DEFAULT_HOP_LIMIT
        ): MeshEnvelope {
            return MeshEnvelope(
                version = CURRENT_VERSION,
                messageId = sosMessage.messageId,
                originDeviceId = originDeviceId,
                originDeviceName = originDeviceName,
                destinationDeviceId = destinationDeviceId,
                messageType = sosMessage.type,
                hopCount = sosMessage.hopCount,
                hopLimit = hopLimit,
                createdAt = sosMessage.timestamp,
                expiresAt = sosMessage.expiresAt,
                ackForMessageId = sosMessage.ackForMessageId,
                payloadJson = sosMessage.toJson()
            )
        }

        /**
         * Parses a JSON string into a MeshEnvelope.
         * Transparently handles:
         * 1. Modern envelopes containing "meshEnvelope": true
         * 2. Legacy raw SosMessage JSON (e.g. from older app builds) by auto-wrapping
         */
        @Throws(IllegalArgumentException::class, JSONException::class)
        fun fromJson(jsonStr: String): MeshEnvelope {
            val json = JSONObject(jsonStr)

            return if (json.has("meshEnvelope") || (json.has("origin") && json.has("hopLimit"))) {
                val version = json.optInt("v", CURRENT_VERSION)
                val id = json.getString("id")
                val origin = json.getString("origin")
                val originName = if (json.has("originName") && !json.isNull("originName")) json.getString("originName") else null
                val dest = if (json.has("dest") && !json.isNull("dest")) json.getString("dest") else null
                val type = MessageType.fromString(json.getString("type"))
                val hops = json.optInt("hops", 0)
                val hopLim = json.optInt("hopLimit", DEFAULT_HOP_LIMIT)
                val created = json.getLong("created")
                val exp = json.getLong("exp")
                val ackFor = if (json.has("ackFor") && !json.isNull("ackFor")) json.getString("ackFor") else null
                val payload = json.optString("payload", "")

                MeshEnvelope(
                    version = version,
                    messageId = id,
                    originDeviceId = origin,
                    originDeviceName = originName,
                    destinationDeviceId = dest,
                    messageType = type,
                    hopCount = hops,
                    hopLimit = hopLim,
                    createdAt = created,
                    expiresAt = exp,
                    ackForMessageId = ackFor,
                    payloadJson = payload
                )
            } else {
                // Backward compatibility: parse as legacy SosMessage and wrap in envelope
                val legacySos = SosMessage.fromJson(jsonStr)
                fromSosMessage(legacySos)
            }
        }

        fun fromByteArray(bytes: ByteArray): MeshEnvelope {
            val jsonStr = String(bytes, Charsets.UTF_8)
            return fromJson(jsonStr)
        }
    }

    /**
     * Checks if this envelope has exceeded its lifetime.
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt

    /**
     * Determines if this envelope is eligible to be forwarded across another hop.
     */
    fun canForward(now: Long = System.currentTimeMillis()): Boolean {
        return hopCount < hopLimit && !isExpired(now)
    }

    /**
     * Creates a new envelope with the hop count incremented for forwarding.
     */
    fun nextHop(): MeshEnvelope {
        return copy(hopCount = hopCount + 1)
    }

    /**
     * Extracts the embedded SosMessage from the envelope payload,
     * or reconstructs one from envelope metadata if payload is absent.
     */
    fun toSosMessage(): SosMessage {
        if (payloadJson.isNotBlank()) {
            try {
                val parsed = SosMessage.fromJson(payloadJson)
                // Ensure hop count and sender name reflect envelope state
                return parsed.copy(
                    hopCount = hopCount,
                    senderName = originDeviceName ?: parsed.senderName
                )
            } catch (e: Exception) {
                // Fall through to fallback
            }
        }
        return SosMessage(
            protocolVersion = version,
            messageId = messageId,
            senderId = originDeviceId,
            type = messageType,
            timestamp = createdAt,
            expiresAt = expiresAt,
            text = if (messageType == MessageType.ACK) "ACK" else "Emergency SOS Alert",
            hopCount = hopCount,
            ackForMessageId = ackForMessageId,
            senderName = originDeviceName
        )
    }

    /**
     * Serializes to JSON string with mesh envelope metadata.
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("meshEnvelope", true)
        json.put("v", version)
        json.put("id", messageId)
        json.put("origin", originDeviceId)
        if (originDeviceName != null) json.put("originName", originDeviceName)
        if (destinationDeviceId != null) json.put("dest", destinationDeviceId)
        json.put("type", messageType.name)
        json.put("hops", hopCount)
        json.put("hopLimit", hopLimit)
        json.put("created", createdAt)
        json.put("exp", expiresAt)
        if (ackForMessageId != null) json.put("ackFor", ackForMessageId)
        json.put("payload", payloadJson)
        return json.toString()
    }

    fun toByteArray(): ByteArray = toJson().toByteArray(Charsets.UTF_8)
}

