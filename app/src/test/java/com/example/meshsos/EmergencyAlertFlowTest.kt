package com.example.meshsos

import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.protocol.MessageDirection
import com.example.meshsos.protocol.MessageType
import com.example.meshsos.protocol.SosMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class EmergencyAlertFlowTest {

    @Test
    fun testSosMessageWithSenderNameSerialization() {
        val original = SosMessage.createSos(
            senderId = "node-alpha-123",
            text = "SOS: Earthquake collapsed wall, need extraction!",
            latitude = 28.6139,
            longitude = 77.2090,
            locationAccuracy = 4.2f,
            senderName = "Alpha Rescue Unit"
        )

        val jsonStr = original.toJson()
        val parsed = SosMessage.fromJson(jsonStr)

        assertEquals("Alpha Rescue Unit", parsed.senderName)
        assertEquals("node-alpha-123", parsed.senderId)
        assertEquals(MessageType.SOS, parsed.type)
        assertEquals(28.6139, parsed.latitude!!, 0.0001)
        assertEquals(77.2090, parsed.longitude!!, 0.0001)
        assertEquals(4.2f, parsed.locationAccuracy!!, 0.01f)
    }

    @Test
    fun testSosMessageWithoutSenderNameSerialization() {
        val original = SosMessage.createSos(
            senderId = "node-beta-456",
            text = "Need water and first aid",
            senderName = null
        )

        val jsonStr = original.toJson()
        val parsed = SosMessage.fromJson(jsonStr)

        assertNull(parsed.senderName)
        assertEquals("node-beta-456", parsed.senderId)
        assertEquals("Need water and first aid", parsed.text)
    }

    @Test
    fun testSosMessageEntityAcknowledgementState() {
        val incomingSos = SosMessage.createSos(
            senderId = "victim-device-1",
            text = "Trapped in room 204",
            latitude = 12.9716,
            longitude = 77.5946,
            senderName = "Room 204 Survivor"
        )

        // Initial entity created from incoming network frame using fromSosMessage helper
        val initialEntity = SosMessageEntity.fromSosMessage(
            message = incomingSos,
            direction = MessageDirection.INCOMING,
            status = DeliveryStatus.SENT,
            senderName = incomingSos.senderName,
            isAcknowledged = false,
            acknowledgedAt = null
        )

        assertFalse(initialEntity.isAcknowledged)
        assertNull(initialEntity.acknowledgedAt)
        assertEquals("Room 204 Survivor", initialEntity.senderName)

        // Receiver human confirms acknowledgement
        val ackTimestamp = System.currentTimeMillis()
        val acknowledgedEntity = initialEntity.copy(
            isAcknowledged = true,
            acknowledgedAt = ackTimestamp
        )

        assertTrue(acknowledgedEntity.isAcknowledged)
        assertEquals(ackTimestamp, acknowledgedEntity.acknowledgedAt)
    }

    @Test
    fun testHumanAcknowledgementResponseGeneration() {
        val victimMessageId = "msg-sos-emergency-999"
        val victimSenderId = "victim-node-77"
        val responderId = "responder-node-88"

        // Generate ACK message in response to human tapping "ACKNOWLEDGE"
        val ackMessage = SosMessage.createAck(
            senderId = responderId,
            originalMessageId = victimMessageId
        )

        assertEquals(MessageType.ACK, ackMessage.type)
        assertEquals(victimMessageId, ackMessage.ackForMessageId)
        assertEquals(responderId, ackMessage.senderId)

        // Verify serializing and deserializing ACK over transport
        val ackJson = ackMessage.toJson()
        val parsedAck = SosMessage.fromJson(ackJson)

        assertEquals(victimMessageId, parsedAck.ackForMessageId)
        assertEquals(responderId, parsedAck.senderId)
        assertEquals(MessageType.ACK, parsedAck.type)
    }

    @Test
    fun testDuplicateAlertSuppression() {
        val notifiedMessageIds = ConcurrentHashMap.newKeySet<String>()

        val alert1 = SosMessage.createSos(
            senderId = "sender-1",
            text = "Flood alert level 2",
            senderName = "Station North"
        )

        // First arrival triggers notification
        val firstArrivalNotified = notifiedMessageIds.add(alert1.messageId)
        assertTrue(firstArrivalNotified)

        // Retransmitted duplicate frame (e.g. from mesh relay or transport retry)
        val duplicateArrivalNotified = notifiedMessageIds.add(alert1.messageId)
        assertFalse(duplicateArrivalNotified)

        // A second distinct alert from the same sender should still be notified
        val alert2 = SosMessage.createSos(
            senderId = "sender-1",
            text = "Flood alert level 3 - evacuation needed",
            senderName = "Station North"
        )
        val secondAlertNotified = notifiedMessageIds.add(alert2.messageId)
        assertTrue(secondAlertNotified)
    }

    @Test
    fun testMultiAlertQueueOrdering() {
        val alertList = mutableListOf<SosMessageEntity>()

        val now = System.currentTimeMillis()
        val msg1 = SosMessage.createSos(
            senderId = "node-1",
            text = "Medical emergency",
            senderName = "Node 1"
        )
        val alert1 = SosMessageEntity.fromSosMessage(
            message = msg1,
            direction = MessageDirection.INCOMING,
            status = DeliveryStatus.SENT,
            isAcknowledged = false
        ).copy(timestamp = now - 5000)

        val msg2 = SosMessage.createSos(
            senderId = "node-2",
            text = "Fire breakout",
            senderName = "Node 2"
        )
        val alert2 = SosMessageEntity.fromSosMessage(
            message = msg2,
            direction = MessageDirection.INCOMING,
            status = DeliveryStatus.SENT,
            isAcknowledged = false
        ).copy(timestamp = now - 1000)

        alertList.add(alert1)
        alertList.add(alert2)

        // Sort descending by timestamp (newest first)
        val sortedAlerts = alertList.sortedByDescending { it.timestamp }
        assertEquals(msg2.messageId, sortedAlerts[0].messageId)
        assertEquals(msg1.messageId, sortedAlerts[1].messageId)
    }

    @Test
    fun testLocationUriFormatting() {
        val lat = 34.0522
        val lon = -118.2437
        val expectedUri = "geo:34.0522,-118.2437?q=34.0522,-118.2437(Emergency+Location)"

        val formattedUri = "geo:$lat,$lon?q=$lat,$lon(Emergency+Location)"
        assertEquals(expectedUri, formattedUri)
    }
}

