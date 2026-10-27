package com.example.meshsos

import com.example.meshsos.transport.wifi.HandshakeStatus
import com.example.meshsos.transport.wifi.HandshakeType
import com.example.meshsos.transport.wifi.WifiAwareHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAwareHandshakeTest {

    @Test
    fun testConnectRequestSerializationAndDeserialization() {
        val req = WifiAwareHandshake.createConnectRequest(
            sessionId = "session-1234-abcd",
            initiatorDeviceId = "init-uuid-001",
            initiatorName = "Samsung Galaxy A35",
            targetDeviceId = "target-uuid-002"
        )

        val bytes = req.toByteArray()
        assertTrue("Handshake message must be <= 255 bytes, was ${bytes.size}", bytes.size <= 255)

        val parsed = WifiAwareHandshake.fromByteArray(bytes)
        assertNotNull(parsed)
        assertEquals(HandshakeType.CONNECT_REQ, parsed!!.type)
        assertEquals("session-1234-abcd", parsed.sessionId)
        assertEquals("init-uuid-001", parsed.initiatorDeviceId)
        assertEquals("Samsung Galaxy A35", parsed.initiatorName)
        assertEquals("target-uuid-002", parsed.targetDeviceId)
        assertNull(parsed.status)
        assertNull(parsed.serverPort)
    }

    @Test
    fun testConnectResponseSerializationAndDeserialization() {
        val resp = WifiAwareHandshake.createConnectResponse(
            sessionId = "session-1234-abcd",
            responderDeviceId = "resp-uuid-002",
            status = HandshakeStatus.ACCEPTED,
            serverPort = 49152
        )

        val bytes = resp.toByteArray()
        assertTrue("Handshake message must be <= 255 bytes, was ${bytes.size}", bytes.size <= 255)

        val parsed = WifiAwareHandshake.fromByteArray(bytes)
        assertNotNull(parsed)
        assertEquals(HandshakeType.CONNECT_RESP, parsed!!.type)
        assertEquals("session-1234-abcd", parsed.sessionId)
        assertEquals("resp-uuid-002", parsed.responderDeviceId)
        assertEquals(HandshakeStatus.ACCEPTED, parsed.status)
        assertEquals(49152, parsed.serverPort)
    }

    @Test
    fun testConnectResponseBusyRejection() {
        val resp = WifiAwareHandshake.createConnectResponse(
            sessionId = "session-9999",
            responderDeviceId = "busy-device-uuid",
            status = HandshakeStatus.REJECTED_BUSY,
            reason = "Device already connected"
        )

        val bytes = resp.toByteArray()
        val parsed = WifiAwareHandshake.fromByteArray(bytes)
        assertNotNull(parsed)
        assertEquals(HandshakeStatus.REJECTED_BUSY, parsed!!.status)
        assertEquals("Device already connected", parsed.reason)
        assertNull(parsed.serverPort)
    }

    @Test
    fun testConnectCancelSerialization() {
        val cancel = WifiAwareHandshake.createCancel(
            sessionId = "session-cancel-1",
            reason = "User aborted connection"
        )

        val bytes = cancel.toByteArray()
        val parsed = WifiAwareHandshake.fromByteArray(bytes)
        assertNotNull(parsed)
        assertEquals(HandshakeType.CONNECT_CANCEL, parsed!!.type)
        assertEquals("User aborted connection", parsed.reason)
    }

    @Test
    fun testMalformedPayloadHandling() {
        assertNull(WifiAwareHandshake.fromByteArray(null))
        assertNull(WifiAwareHandshake.fromByteArray(ByteArray(0)))
        assertNull(WifiAwareHandshake.fromByteArray("not valid json".toByteArray(Charsets.UTF_8)))
    }
}

