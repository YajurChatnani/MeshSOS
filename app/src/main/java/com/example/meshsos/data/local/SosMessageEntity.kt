package com.example.meshsos.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.protocol.MessageDirection
import com.example.meshsos.protocol.MessageType
import com.example.meshsos.protocol.SosMessage

@Entity(
    tableName = "sos_messages",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["deliveryStatus"]),
        Index(value = ["direction"]),
        Index(value = ["isAcknowledged"])
    ]
)
data class SosMessageEntity(
    @PrimaryKey
    val messageId: String,
    val senderId: String,
    val messageType: String,
    val text: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val timestamp: Long,
    val expiresAt: Long,
    val direction: String, // "INCOMING" or "OUTGOING"
    val deliveryStatus: String, // "PENDING", "SENT", "ACKNOWLEDGED", "FAILED", "EXPIRED"
    val retryCount: Int = 0,
    val lastRetryTimestamp: Long = 0L,
    val hopCount: Int = 0,
    val ackForMessageId: String? = null,
    val senderName: String? = null,
    val isAcknowledged: Boolean = false,
    val acknowledgedAt: Long? = null,
    val originDeviceId: String? = null,
    val hopLimit: Int = 5
) {
    fun toSosMessage(): SosMessage {
        return SosMessage(
            protocolVersion = SosMessage.CURRENT_PROTOCOL_VERSION,
            messageId = messageId,
            senderId = originDeviceId ?: senderId,
            type = MessageType.fromString(messageType),
            timestamp = timestamp,
            expiresAt = expiresAt,
            text = text,
            latitude = latitude,
            longitude = longitude,
            locationAccuracy = locationAccuracy,
            hopCount = hopCount,
            ackForMessageId = ackForMessageId,
            senderName = senderName
        )
    }

    companion object {
        fun fromSosMessage(
            message: SosMessage,
            direction: MessageDirection,
            status: DeliveryStatus,
            retryCount: Int = 0,
            lastRetryTimestamp: Long = 0L,
            senderName: String? = message.senderName,
            isAcknowledged: Boolean = false,
            acknowledgedAt: Long? = null,
            originDeviceId: String? = message.senderId,
            hopLimit: Int = 5
        ): SosMessageEntity {
            return SosMessageEntity(
                messageId = message.messageId,
                senderId = message.senderId,
                messageType = message.type.name,
                text = message.text,
                latitude = message.latitude,
                longitude = message.longitude,
                locationAccuracy = message.locationAccuracy,
                timestamp = message.timestamp,
                expiresAt = message.expiresAt,
                direction = direction.name,
                deliveryStatus = status.name,
                retryCount = retryCount,
                lastRetryTimestamp = lastRetryTimestamp,
                hopCount = message.hopCount,
                ackForMessageId = message.ackForMessageId,
                senderName = senderName ?: message.senderName,
                isAcknowledged = isAcknowledged,
                acknowledgedAt = acknowledgedAt,
                originDeviceId = originDeviceId ?: message.senderId,
                hopLimit = hopLimit
            )
        }
    }
}
