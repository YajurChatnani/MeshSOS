package com.example.meshsos

import com.example.meshsos.protocol.MessageType
import com.example.meshsos.protocol.SosMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosProtocolTest {

    @Test
    fun testSosSerializationAndDeserialization() {
        val original = SosMessage.createSos(
            senderId = "device-abc-123",
            text = "Need medical help immediately!",
            latitude = 37.7749,
            longitude = -122.4194,
            locationAccuracy = 8.5f,
            ttlMs = 3600000L
        )

        val jsonStr = original.toJson()
        val parsed = SosMessage.fromJson(jsonStr)

        assertEquals(original.protocolVersion, parsed.protocolVersion)
        assertEquals(original.messageId, parsed.messageId)
        assertEquals(original.senderId, parsed.senderId)
        assertEquals(MessageType.SOS, parsed.type)
        assertEquals(original.text, parsed.text)
        assertEquals(37.7749, parsed.latitude!!, 0.0001)
        assertEquals(-122.4194, parsed.longitude!!, 0.0001)
        assertEquals(8.5f, parsed.locationAccuracy!!, 0.01f)
        assertEquals(0, parsed.hopCount)
        assertNull(parsed.ackForMessageId)
        assertTrue(parsed.hasCoordinates())
        assertFalse(parsed.isExpired())
    }

    @Test
    fun testAckSerializationAndDeserialization() {
        val originalMessageId = "msg-xyz-789"
        val ack = SosMessage.createAck(
            senderId = "receiver-device-456",
            originalMessageId = originalMessageId
        )

        val jsonStr = ack.toJson()
        val parsed = SosMessage.fromJson(jsonStr)

        assertEquals(MessageType.ACK, parsed.type)
        assertEquals("receiver-device-456", parsed.senderId)
        assertEquals(originalMessageId, parsed.ackForMessageId)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
        assertFalse(parsed.hasCoordinates())
    }

    @Test
    fun testSosWithoutCoordinates() {
        val sos = SosMessage.createSos(
            senderId = "device-node-1",
            text = "Coordinates unavailable, trapped in basement",
            latitude = null,
            longitude = null,
            locationAccuracy = null
        )

        assertFalse(sos.hasCoordinates())
        assertNull(sos.latitude)
        assertNull(sos.longitude)

        val jsonStr = sos.toJson()
        val parsed = SosMessage.fromJson(jsonStr)

        assertFalse(parsed.hasCoordinates())
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
        assertNull(parsed.locationAccuracy)
        assertEquals("Coordinates unavailable, trapped in basement", parsed.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidLatitudeThrowsException() {
        SosMessage.fromJson(
            """{"v":1,"id":"id-1","sender":"sender-1","type":"SOS","ts":1000,"exp":2000,"text":"test","lat":95.0,"lon":10.0}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidLongitudeThrowsException() {
        SosMessage.fromJson(
            """{"v":1,"id":"id-1","sender":"sender-1","type":"SOS","ts":1000,"exp":2000,"text":"test","lat":10.0,"lon":-195.0}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBlankMessageIdThrowsException() {
        SosMessage.fromJson(
            """{"v":1,"id":"","sender":"sender-1","type":"SOS","ts":1000,"exp":2000,"text":"test"}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testAckMissingAckForThrowsException() {
        SosMessage.fromJson(
            """{"v":1,"id":"ack-1","sender":"sender-1","type":"ACK","ts":1000,"exp":2000,"text":"ACK"}"""
        )
    }

    @Test
    fun testMessageExpiryCalculation() {
        val now = 1_000_000L
        val unexpired = SosMessage(
            senderId = "dev-1",
            text = "Active alert",
            timestamp = now - 5000,
            expiresAt = now + 5000
        )
        assertFalse(unexpired.isExpired(now))

        val expired = SosMessage(
            senderId = "dev-1",
            text = "Old alert",
            timestamp = now - 10000,
            expiresAt = now - 1000
        )
        assertTrue(expired.isExpired(now))
    }
}
