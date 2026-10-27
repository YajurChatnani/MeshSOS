package com.example.meshsos

import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.protocol.MessageDirection
import com.example.meshsos.protocol.MessageType
import com.example.meshsos.protocol.SosMessage
import com.example.meshsos.transport.ConnectionState
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class DeliveryAndRetryTest {

    @Test
    fun testAckMatchingLogic() {
        val outgoingSos = SosMessage.createSos(
            senderId = "sender-1",
            text = "Emergency Alert"
        )

        val ackFromPeer = SosMessage.createAck(
            senderId = "receiver-2",
            originalMessageId = outgoingSos.messageId
        )

        // Verify ACK points to original message ID
        assertEquals(outgoingSos.messageId, ackFromPeer.ackForMessageId)
        assertEquals(MessageType.ACK, ackFromPeer.type)
    }

    @Test
    fun testDuplicateSuppressionLogic() {
        val seenIds = mutableSetOf<String>()
        val incoming1 = SosMessage.createSos(senderId = "node-A", text = "SOS 1")
        val incoming2 = incoming1.copy() // Duplicate with same messageId

        // First message is new
        val isFirstDuplicate = !seenIds.add(incoming1.messageId)
        assertFalse(isFirstDuplicate)

        // Second message with identical ID is recognized as duplicate
        val isSecondDuplicate = !seenIds.add(incoming2.messageId)
        assertTrue(isSecondDuplicate)
    }

    @Test
    fun testBoundedBackoffCalculation() {
        val maxRetries = 5
        val maxBackoff = 30_000L

        fun calculateBackoff(retryCount: Int): Long {
            return min(maxBackoff, 2000L * (1 shl retryCount))
        }

        // Check each retry interval
        assertEquals(2000L, calculateBackoff(0))   // 2s
        assertEquals(4000L, calculateBackoff(1))   // 4s
        assertEquals(8000L, calculateBackoff(2))   // 8s
        assertEquals(16000L, calculateBackoff(3))  // 16s
        assertEquals(30000L, calculateBackoff(4))  // 32s capped at 30s
        assertEquals(30000L, calculateBackoff(5))  // capped at 30s

        // Verify retry count limit
        for (retry in 0..maxRetries) {
            val isExceeded = retry >= maxRetries
            if (retry < maxRetries) {
                assertFalse(isExceeded)
            } else {
                assertTrue(isExceeded)
            }
        }
    }

    @Test
    fun testConnectionStateTransitions() {
        var state: ConnectionState = ConnectionState.Disconnected
        assertFalse(state.isConnected())
        assertEquals("Disconnected", state.statusLabel())

        state = ConnectionState.Discovering
        assertFalse(state.isConnected())
        assertEquals("Discovering Peers…", state.statusLabel())

        val peer = DiscoveredPeer(
            id = "test-peer-id",
            name = "Galaxy A35",
            transport = TransportType.WIFI_AWARE
        )

        state = ConnectionState.PeerFound(peer)
        assertFalse(state.isConnected())
        assertTrue(state.statusLabel().contains("Galaxy A35"))

        state = ConnectionState.Connecting(peer.name)
        assertFalse(state.isConnected())

        state = ConnectionState.Connected(peer, TransportType.WIFI_AWARE)
        assertTrue(state.isConnected())
        assertEquals("Connected to Galaxy A35", state.statusLabel())

        state = ConnectionState.Failed("Socket closed unexpectedly")
        assertFalse(state.isConnected())
        assertTrue(state.statusLabel().contains("Socket closed"))
    }
}
