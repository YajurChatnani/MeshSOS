package com.example.meshsos.protocol

import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Message types supported by the Offgrid SOS protocol.
 */
enum class MessageType {
    SOS,
    ACK,
    HEARTBEAT;

    companion object {
        fun fromString(value: String): MessageType {
            return try {
                valueOf(value.uppercase())
            } catch (e: Exception) {
                SOS
            }
        }
    }
}

/**
 * Direction of the message relative to the local device.
 */
enum class MessageDirection {
    INCOMING,
    OUTGOING
}

/**
 * Delivery status lifecycle of an SOS message.
 */
enum class DeliveryStatus {
    PENDING,       // Created locally, waiting to be sent
    SENT,          // Transmitted over active transport, waiting for ACK
    ACKNOWLEDGED,  // Validated and persisted by receiver, matching ACK received
    FAILED,        // Max retries exceeded or transport disconnected
    EXPIRED        // Expiry timestamp passed before successful ACK
}

/**
 * Immutable data model representing an Offgrid SOS protocol message.
 * This model is completely independent of the underlying transport (Wi-Fi Aware or Bluetooth Classic).
 */
data class SosMessage(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val type: MessageType = MessageType.SOS,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    val text: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val hopCount: Int = 0,
    val ackForMessageId: String? = null,
    val senderName: String? = null
) {
    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        const val MAX_TEXT_LENGTH = 1000

        /**
         * Create an outgoing SOS message.
         */
        fun createSos(
            senderId: String,
            text: String,
            latitude: Double? = null,
            longitude: Double? = null,
            locationAccuracy: Float? = null,
            ttlMs: Long = DEFAULT_TTL_MS,
            senderName: String? = null
        ): SosMessage {
            val now = System.currentTimeMillis()
            return SosMessage(
                protocolVersion = CURRENT_PROTOCOL_VERSION,
                messageId = UUID.randomUUID().toString(),
                senderId = senderId,
                type = MessageType.SOS,
                timestamp = now,
                expiresAt = now + ttlMs,
                text = text.trim().take(MAX_TEXT_LENGTH),
                latitude = latitude,
                longitude = longitude,
                locationAccuracy = locationAccuracy,
                hopCount = 0,
                ackForMessageId = null,
                senderName = senderName?.trim()?.take(32)
            )
        }

        /**
         * Create an ACK message in response to a received SOS.
         */
        fun createAck(
            senderId: String,
            originalMessageId: String
        ): SosMessage {
            val now = System.currentTimeMillis()
            return SosMessage(
                protocolVersion = CURRENT_PROTOCOL_VERSION,
                messageId = UUID.randomUUID().toString(),
                senderId = senderId,
                type = MessageType.ACK,
                timestamp = now,
                expiresAt = now + DEFAULT_TTL_MS,
                text = "ACK",
                latitude = null,
                longitude = null,
                locationAccuracy = null,
                hopCount = 0,
                ackForMessageId = originalMessageId
            )
        }

        /**
         * Parse and validate a JSON string into a SosMessage.
         * Throws IllegalArgumentException or JSONException if payload is invalid.
         */
        @Throws(IllegalArgumentException::class, JSONException::class)
        fun fromJson(jsonStr: String): SosMessage {
            val json = JSONObject(jsonStr)

            val version = json.optInt("v", 1)
            val id = json.getString("id")
            val sender = json.getString("sender")
            val typeStr = json.getString("type")
            val type = MessageType.fromString(typeStr)
            val ts = json.getLong("ts")
            val exp = json.getLong("exp")
            val txt = json.optString("text", "")

            val lat = if (json.has("lat") && !json.isNull("lat")) json.getDouble("lat") else null
            val lon = if (json.has("lon") && !json.isNull("lon")) json.getDouble("lon") else null
            val acc = if (json.has("acc") && !json.isNull("acc")) json.getDouble("acc").toFloat() else null
            val hops = json.optInt("hops", 0)
            val ackFor = if (json.has("ackFor") && !json.isNull("ackFor")) json.getString("ackFor") else null
            val sName = if (json.has("senderName") && !json.isNull("senderName")) json.getString("senderName") else null

            // Validation checks
            require(id.isNotBlank()) { "Message ID cannot be blank" }
            require(sender.isNotBlank()) { "Sender ID cannot be blank" }
            if (lat != null) {
                require(lat in -90.0..90.0) { "Latitude out of bounds: $lat" }
            }
            if (lon != null) {
                require(lon in -180.0..180.0) { "Longitude out of bounds: $lon" }
            }
            if (acc != null) {
                require(acc >= 0f) { "Accuracy cannot be negative: $acc" }
            }
            if (type == MessageType.ACK) {
                require(!ackFor.isNullOrBlank()) { "ACK message must specify ackFor message ID" }
            }

            return SosMessage(
                protocolVersion = version,
                messageId = id,
                senderId = sender,
                type = type,
                timestamp = ts,
                expiresAt = exp,
                text = txt,
                latitude = lat,
                longitude = lon,
                locationAccuracy = acc,
                hopCount = hops,
                ackForMessageId = ackFor,
                senderName = sName
            )
        }

        fun fromByteArray(bytes: ByteArray): SosMessage {
            val jsonStr = String(bytes, Charsets.UTF_8)
            return fromJson(jsonStr)
        }
    }

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now > expiresAt
    }

    fun hasCoordinates(): Boolean {
        return latitude != null && longitude != null
    }

    /**
     * Serializes to a compact JSON string.
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("v", protocolVersion)
        json.put("id", messageId)
        json.put("sender", senderId)
        json.put("type", type.name)
        json.put("ts", timestamp)
        json.put("exp", expiresAt)
        json.put("text", text)

        if (latitude != null) json.put("lat", latitude)
        if (longitude != null) json.put("lon", longitude)
        if (locationAccuracy != null) json.put("acc", locationAccuracy.toDouble())
        json.put("hops", hopCount)
        if (ackForMessageId != null) json.put("ackFor", ackForMessageId)
        if (senderName != null) json.put("senderName", senderName)

        return json.toString()
    }

    fun toByteArray(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }
}
