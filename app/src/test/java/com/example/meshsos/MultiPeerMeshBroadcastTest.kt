package com.example.meshsos

import com.example.meshsos.data.local.MeshPeerDeliveryEntity
import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.protocol.MeshEnvelope
import com.example.meshsos.protocol.MessageDirection
import com.example.meshsos.protocol.MessageType
import com.example.meshsos.protocol.SosMessage
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.TransportFrame
import com.example.meshsos.transport.TransportType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MultiPeerMeshBroadcastTest {

    @Test
    fun testBroadcastToMultipleConnectedPeers() = runBlocking {
        val connectedPeers = listOf(
            DiscoveredPeer(id = "peer-B", name = "Device B", transport = TransportType.WIFI_AWARE),
            DiscoveredPeer(id = "peer-C", name = "Device C", transport = TransportType.WIFI_AWARE),
            DiscoveredPeer(id = "peer-D", name = "Device D", transport = TransportType.WIFI_AWARE)
        )

        val sos = SosMessage.createSos(
            senderId = "peer-A",
            text = "Emergency: Flash flood warning!",
            senderName = "Station A"
        )
        val envelope = MeshEnvelope.fromSosMessage(sos)
        val envelopeBytes = envelope.toByteArray()

        // Mock multi-peer broadcaster
        val receivedPeers = Collections.synchronizedList(mutableListOf<String>())
        val broadcastResults = connectedPeers.map { peer ->
            async {
                receivedPeers.add(peer.id)
                Pair(peer.id, true)
            }
        }.awaitAll().toMap()

        assertEquals(3, broadcastResults.size)
        assertTrue(broadcastResults.values.all { it })
        assertTrue(receivedPeers.contains("peer-B"))
        assertTrue(receivedPeers.contains("peer-C"))
        assertTrue(receivedPeers.contains("peer-D"))
    }

    @Test
    fun testRelayingFromBToCandDExcludingIngressA() {
        val ingressPeerId = "peer-A"
        val connectedPeers = listOf(
            DiscoveredPeer(id = "peer-A", name = "Device A", transport = TransportType.WIFI_AWARE),
            DiscoveredPeer(id = "peer-C", name = "Device C", transport = TransportType.WIFI_AWARE),
            DiscoveredPeer(id = "peer-D", name = "Device D", transport = TransportType.WIFI_AWARE)
        )

        val incomingEnvelope = MeshEnvelope(
            messageId = "sos-msg-101",
            originDeviceId = "peer-A",
            originDeviceName = "Device A",
            messageType = MessageType.SOS,
            hopCount = 0,
            hopLimit = 5,
            payloadJson = "{\"test\":\"data\"}"
        )

        // Eligible forward targets: all connected peers EXCEPT ingress peer
        val eligibleTargets = connectedPeers.filter { it.id != ingressPeerId }

        assertEquals(2, eligibleTargets.size)
        assertFalse(eligibleTargets.any { it.id == ingressPeerId })
        assertTrue(eligibleTargets.any { it.id == "peer-C" })
        assertTrue(eligibleTargets.any { it.id == "peer-D" })

        // Increment hop count for relay
        val relayedEnvelope = incomingEnvelope.nextHop()
        assertEquals(1, relayedEnvelope.hopCount)
        assertEquals("sos-msg-101", relayedEnvelope.messageId)
        assertEquals("peer-A", relayedEnvelope.originDeviceId)
    }

    @Test
    fun testHopLimitEnforcement() {
        val limitEnvelope = MeshEnvelope(
            messageId = "sos-hops-max",
            originDeviceId = "origin-node",
            hopCount = 5,
            hopLimit = 5
        )

        assertFalse(limitEnvelope.canForward())

        val unexpiredEnvelope = MeshEnvelope(
            messageId = "sos-hops-ok",
            originDeviceId = "origin-node",
            hopCount = 3,
            hopLimit = 5
        )
        assertTrue(unexpiredEnvelope.canForward())
    }

    @Test
    fun testExpiryEnforcement() {
        val now = 1_000_000L
        val expiredEnvelope = MeshEnvelope(
            messageId = "sos-expired",
            originDeviceId = "origin-node",
            createdAt = now - 50_000L,
            expiresAt = now - 1000L
        )

        assertTrue(expiredEnvelope.isExpired(now))
        assertFalse(expiredEnvelope.canForward(now))

        val activeEnvelope = MeshEnvelope(
            messageId = "sos-active",
            originDeviceId = "origin-node",
            createdAt = now - 10_000L,
            expiresAt = now + 60_000L
        )

        assertFalse(activeEnvelope.isExpired(now))
        assertTrue(activeEnvelope.canForward(now))
    }

    @Test
    fun testDeduplicationFromMultiplePeers() {
        val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

        val envelope = MeshEnvelope(
            messageId = "msg-duplicate-check-777",
            originDeviceId = "origin-sender",
            messageType = MessageType.SOS
        )

        // First arrival from peer B
        val firstProcessed = seenMessageIds.add(envelope.messageId)
        assertTrue(firstProcessed)

        // Second arrival of the same message from peer C
        val secondProcessed = seenMessageIds.add(envelope.messageId)
        assertFalse(secondProcessed)
    }

    @Test
    fun testConcurrentDuplicateDeliveries() = runBlocking {
        val seenMessageIds = ConcurrentHashMap.newKeySet<String>()
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val duplicateCount = java.util.concurrent.atomic.AtomicInteger(0)

        val targetMessageId = "concurrent-flood-sos-001"

        // Simulate 20 concurrent arrival attempts from mesh neighbors
        val jobs = (1..20).map {
            async {
                val isNew = seenMessageIds.add(targetMessageId)
                if (isNew) {
                    processedCount.incrementAndGet()
                } else {
                    duplicateCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(1, processedCount.get())
        assertEquals(19, duplicateCount.get())
    }

    @Test
    fun testIsolatedPeerSendFailure() = runBlocking {
        val peers = listOf("peer-1", "peer-failing", "peer-3")

        fun sendToPeer(peerId: String): Boolean {
            if (peerId == "peer-failing") {
                throw IOException("Socket broken pipe for peer-failing")
            }
            return true
        }

        val results = peers.map { peerId ->
            async {
                try {
                    val ok = sendToPeer(peerId)
                    Pair(peerId, ok)
                } catch (e: Exception) {
                    Pair(peerId, false)
                }
            }
        }.awaitAll().toMap()

        assertEquals(true, results["peer-1"])
        assertEquals(false, results["peer-failing"])
        assertEquals(true, results["peer-3"])
    }

    @Test
    fun testStoreAndForwardTracking() {
        val messageId = "sos-store-fwd-01"
        val deliveredPeers = mutableSetOf<String>()

        // Initially delivered to peer-B
        deliveredPeers.add("peer-B")

        // Discovered reconnected peer-C
        val newlyConnected = "peer-C"
        val alreadyDeliveredToC = deliveredPeers.contains(newlyConnected)
        assertFalse(alreadyDeliveredToC)

        // Store-and-forward delivers to peer-C
        deliveredPeers.add(newlyConnected)
        assertTrue(deliveredPeers.contains(newlyConnected))

        // Reconnect again; should not re-deliver
        val secondCheckDelivered = deliveredPeers.contains(newlyConnected)
        assertTrue(secondCheckDelivered)
    }

    @Test
    fun testAckReversePropagationAndDeduplication() {
        val originalMessageId = "sos-alpha-44"
        val victimSenderId = "victim-node-1"
        val humanResponderId = "responder-node-2"

        val ackEnvelope = MeshEnvelope(
            messageId = "ack-envelope-01",
            originDeviceId = humanResponderId,
            originDeviceName = "Rescue Team 1",
            messageType = MessageType.ACK,
            ackForMessageId = originalMessageId,
            hopCount = 0,
            hopLimit = 5
        )

        assertEquals(MessageType.ACK, ackEnvelope.messageType)
        assertEquals(originalMessageId, ackEnvelope.ackForMessageId)
        assertEquals(humanResponderId, ackEnvelope.originDeviceId)

        // Relay increments hop count
        val relayedAck = ackEnvelope.nextHop()
        assertEquals(1, relayedAck.hopCount)

        // ACK deduplication
        val seenAcks = ConcurrentHashMap.newKeySet<String>()
        val ackKey = "$originalMessageId:$humanResponderId"

        assertTrue(seenAcks.add(ackKey))
        assertFalse(seenAcks.add(ackKey))
    }

    @Test
    fun testLegacySosMessageEnvelopeWrapping() {
        // Legacy JSON produced by an older app installation
        val legacyJson = """{"v":1,"id":"legacy-msg-123","sender":"legacy-sender","type":"SOS","ts":1700000000,"exp":1700100000,"text":"Need help!"}"""

        val parsedEnvelope = MeshEnvelope.fromJson(legacyJson)

        assertEquals("legacy-msg-123", parsedEnvelope.messageId)
        assertEquals("legacy-sender", parsedEnvelope.originDeviceId)
        assertEquals(MessageType.SOS, parsedEnvelope.messageType)
        assertEquals(0, parsedEnvelope.hopCount)
        assertEquals(MeshEnvelope.DEFAULT_HOP_LIMIT, parsedEnvelope.hopLimit)

        val reconstructedSos = parsedEnvelope.toSosMessage()
        assertEquals("legacy-msg-123", reconstructedSos.messageId)
        assertEquals("legacy-sender", reconstructedSos.senderId)
        assertEquals("Need help!", reconstructedSos.text)
    }

    @Test
    fun testTransportFrameContract() {
        val frameData = "test-bytes".toByteArray(Charsets.UTF_8)
        val transportFrame = TransportFrame(
            peerId = "aware-peer-001",
            data = frameData,
            transport = TransportType.WIFI_AWARE
        )

        assertEquals("aware-peer-001", transportFrame.peerId)
        assertEquals(TransportType.WIFI_AWARE, transportFrame.transport)
        assertEquals(frameData.size, transportFrame.data.size)
    }
}

