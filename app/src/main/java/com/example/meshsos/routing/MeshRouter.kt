package com.example.meshsos.routing

import android.content.Context
import android.util.Log
import com.example.meshsos.data.local.MeshPeerDeliveryEntity
import com.example.meshsos.data.local.SosMessageDao
import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.data.preferences.DevicePreferences
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.protocol.MeshEnvelope
import com.example.meshsos.protocol.MessageDirection
import com.example.meshsos.protocol.MessageType
import com.example.meshsos.protocol.SosMessage
import com.example.meshsos.service.SosNotificationHelper
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.TransportFrame
import com.example.meshsos.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated Mesh Broadcast Router for MeshSOS.
 *
 * Implements:
 * - Ingress-peer excluded relay broadcast.
 * - Outbound multi-peer SOS broadcast.
 * - Persistent deduplication against Room DB + fast in-memory caches.
 * - Multi-hop ACK reverse-path propagation.
 * - Persistent store-and-forward for disconnected and reconnecting peers.
 * - Decoupled from transport socket internals and UI lifecycle.
 */
class MeshRouter(
    private val context: Context,
    private val dao: SosMessageDao,
    private val transportManager: TransportManager,
    private val preferences: DevicePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val _incomingAlertEvents = MutableSharedFlow<SosMessageEntity>(extraBufferCapacity = 16)
    val incomingAlertEvents: SharedFlow<SosMessageEntity> = _incomingAlertEvents.asSharedFlow()

    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val seenAckKeys = ConcurrentHashMap.newKeySet<String>()
    private val dedupMutex = Mutex()

    private var frameListenerJob: Job? = null
    private var peerConnectionWatcherJob: Job? = null
    private var previousConnectedPeerIds = setOf<String>()

    init {
        startFrameListener()
        startPeerConnectionWatcher()
    }

    private fun startFrameListener() {
        frameListenerJob?.cancel()
        frameListenerJob = scope.launch {
            transportManager.incomingTransportFrames.collect { transportFrame ->
                try {
                    handleIncomingTransportFrame(transportFrame)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing incoming transport frame: ${e.message}")
                }
            }
        }
    }

    private fun startPeerConnectionWatcher() {
        peerConnectionWatcherJob?.cancel()
        peerConnectionWatcherJob = scope.launch {
            transportManager.connectedPeers.collect { connectedList ->
                val currentIds = connectedList.map { it.id }.toSet()
                val newlyConnected = currentIds - previousConnectedPeerIds
                previousConnectedPeerIds = currentIds

                if (newlyConnected.isNotEmpty()) {
                    Log.d(TAG, "Newly connected peers detected: $newlyConnected. Running store-and-forward queue.")
                    runStoreAndForwardForPeers(newlyConnected)
                }
            }
        }
    }

    /**
     * Processes an incoming raw transport frame from an ingress peer.
     */
    suspend fun handleIncomingTransportFrame(transportFrame: TransportFrame) = withContext(Dispatchers.IO) {
        val envelope = try {
            MeshEnvelope.fromByteArray(transportFrame.data)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed or unsupported frame from ${transportFrame.peerId}: ${e.message}")
            return@withContext
        }

        // Drop messages originated by this device (loop protection)
        if (envelope.originDeviceId == preferences.deviceId) {
            Log.d(TAG, "Dropped loopback message originated by local device: ${envelope.messageId}")
            return@withContext
        }

        // Drop expired messages
        if (envelope.isExpired()) {
            Log.d(TAG, "Dropped expired message: ${envelope.messageId}")
            return@withContext
        }

        when (envelope.messageType) {
            MessageType.SOS -> handleIncomingSosEnvelope(envelope, ingressPeerId = transportFrame.peerId)
            MessageType.ACK -> handleIncomingAckEnvelope(envelope, ingressPeerId = transportFrame.peerId)
            MessageType.HEARTBEAT -> {
                // Heartbeat / ping packet
            }
        }
    }

    private suspend fun handleIncomingSosEnvelope(envelope: MeshEnvelope, ingressPeerId: String) {
        val messageId = envelope.messageId

        // Critical Section: atomic deduplication check
        val isNew = dedupMutex.withLock {
            if (seenMessageIds.contains(messageId)) {
                return@withLock false
            }
            if (dao.exists(messageId) > 0) {
                seenMessageIds.add(messageId)
                return@withLock false
            }
            seenMessageIds.add(messageId)
            true
        }

        // Record that ingress peer already has this message
        dao.recordPeerDelivery(MeshPeerDeliveryEntity(messageId = messageId, peerId = ingressPeerId))

        if (!isNew) {
            Log.d(TAG, "Duplicate SOS frame $messageId from ingress peer $ingressPeerId suppressed.")
            // If already acknowledged by local user, re-send ACK so ingress peer knows
            val existing = dao.getMessageById(messageId)
            if (existing?.isAcknowledged == true) {
                val ack = MeshEnvelope(
                    originDeviceId = preferences.deviceId,
                    originDeviceName = preferences.deviceName,
                    messageType = MessageType.ACK,
                    ackForMessageId = messageId,
                    hopCount = 0,
                    hopLimit = MeshEnvelope.DEFAULT_HOP_LIMIT
                )
                transportManager.sendFrameToPeer(ingressPeerId, ack.toByteArray())
            }
            return
        }

        // 1. Resolve friendly sender name
        val resolvedName = envelope.originDeviceName
            ?: transportManager.connectedPeers.value.firstOrNull { it.id == envelope.originDeviceId }?.name
            ?: transportManager.discoveredPeers.value.firstOrNull { it.id == envelope.originDeviceId }?.name
            ?: "Nearby Device (${envelope.originDeviceId.takeLast(4)})"

        // 2. Persist to Room Database first
        val entity = SosMessageEntity.fromSosMessage(
            message = envelope.toSosMessage(),
            direction = MessageDirection.INCOMING,
            status = DeliveryStatus.SENT,
            senderName = resolvedName,
            isAcknowledged = false,
            originDeviceId = envelope.originDeviceId,
            hopLimit = envelope.hopLimit
        )
        dao.insertOrUpdate(entity)

        // 3. Post emergency notification & alert UI once
        SosNotificationHelper.postEmergencyNotification(context, entity)
        _incomingAlertEvents.emit(entity)

        // 4. Relay Broadcast: forward to all connected peers except ingress peer
        if (envelope.canForward()) {
            val relayEnvelope = envelope.nextHop()
            val relayBytes = relayEnvelope.toByteArray()
            val results = transportManager.broadcastFrame(relayBytes, excludedPeerId = ingressPeerId)

            for ((peerId, success) in results) {
                if (success) {
                    dao.recordPeerDelivery(MeshPeerDeliveryEntity(messageId = messageId, peerId = peerId))
                }
            }
            Log.d(TAG, "Relayed SOS $messageId (hop ${relayEnvelope.hopCount}/${relayEnvelope.hopLimit}) to ${results.count { it.value }} peers.")
        } else {
            Log.d(TAG, "SOS $messageId reached hop limit (${envelope.hopCount}/${envelope.hopLimit}). Relaying stopped.")
        }
    }

    private suspend fun handleIncomingAckEnvelope(envelope: MeshEnvelope, ingressPeerId: String) {
        val targetMessageId = envelope.ackForMessageId ?: envelope.messageId
        val ackKey = "$targetMessageId:${envelope.originDeviceId}"

        val isNewAck = seenAckKeys.add(ackKey)
        if (!isNewAck) {
            return
        }

        Log.d(TAG, "Inbound ACK for SOS $targetMessageId from ${envelope.originDeviceId} via ingress peer $ingressPeerId")

        // 1. Check if the acknowledged message was created locally by this device
        val localMessage = dao.getMessageById(targetMessageId)
        if (localMessage != null && localMessage.direction == MessageDirection.OUTGOING.name) {
            dao.markAcknowledged(targetMessageId, acknowledgedAt = envelope.createdAt, status = DeliveryStatus.ACKNOWLEDGED.name)
            Log.d(TAG, "Local outgoing SOS $targetMessageId successfully confirmed ACKNOWLEDGED by peer!")
        }

        // 2. Reverse propagation: relay ACK through the mesh so origin gets it
        if (envelope.canForward()) {
            val relayAck = envelope.nextHop()
            val results = transportManager.broadcastFrame(relayAck.toByteArray(), excludedPeerId = ingressPeerId)
            Log.d(TAG, "Propagated ACK for $targetMessageId to ${results.count { it.value }} peers.")
        }
    }

    /**
     * Creates and broadcasts an SOS message across the multi-peer mesh.
     */
    suspend fun broadcastSos(
        text: String,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null
    ): SosMessage = withContext(Dispatchers.IO) {
        val sos = SosMessage.createSos(
            senderId = preferences.deviceId,
            text = text,
            latitude = latitude,
            longitude = longitude,
            locationAccuracy = accuracy,
            senderName = preferences.deviceName
        )

        val envelope = MeshEnvelope.fromSosMessage(
            sosMessage = sos,
            originDeviceId = preferences.deviceId,
            originDeviceName = preferences.deviceName,
            hopLimit = MeshEnvelope.DEFAULT_HOP_LIMIT
        )

        // 1. Persist locally first
        val entity = SosMessageEntity.fromSosMessage(
            message = sos,
            direction = MessageDirection.OUTGOING,
            status = DeliveryStatus.PENDING,
            senderName = preferences.deviceName,
            originDeviceId = preferences.deviceId,
            hopLimit = MeshEnvelope.DEFAULT_HOP_LIMIT
        )
        dao.insertOrUpdate(entity)
        seenMessageIds.add(sos.messageId)

        // 2. Broadcast to all connected peers
        val envelopeBytes = envelope.toByteArray()
        val results = transportManager.broadcastFrame(envelopeBytes, excludedPeerId = null)

        var deliveredCount = 0
        for ((peerId, success) in results) {
            if (success) {
                deliveredCount++
                dao.recordPeerDelivery(MeshPeerDeliveryEntity(messageId = sos.messageId, peerId = peerId))
            }
        }

        if (deliveredCount > 0) {
            dao.updateDeliveryStatus(sos.messageId, DeliveryStatus.SENT.name)
            Log.d(TAG, "Broadcast SOS ${sos.messageId} successfully transmitted to $deliveredCount connected peers.")
        } else {
            Log.d(TAG, "No peers currently connected. Outgoing SOS ${sos.messageId} queued in store-and-forward.")
        }

        return@withContext sos
    }

    /**
     * Called when a human responder taps "ACKNOWLEDGE" on the alert screen or notification.
     */
    suspend fun acknowledgeSos(messageId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.getMessageById(messageId) ?: return@withContext false

        val now = System.currentTimeMillis()
        dao.markAcknowledged(messageId, acknowledgedAt = now, status = DeliveryStatus.ACKNOWLEDGED.name)

        SosNotificationHelper.dismissNotification(context, messageId)

        // Create ACK envelope
        val ackEnvelope = MeshEnvelope(
            originDeviceId = preferences.deviceId,
            originDeviceName = preferences.deviceName,
            messageType = MessageType.ACK,
            ackForMessageId = messageId,
            hopCount = 0,
            hopLimit = MeshEnvelope.DEFAULT_HOP_LIMIT,
            createdAt = now
        )

        val ackKey = "$messageId:${preferences.deviceId}"
        seenAckKeys.add(ackKey)

        // Broadcast ACK to all connected peers
        val results = transportManager.broadcastFrame(ackEnvelope.toByteArray(), excludedPeerId = null)
        Log.d(TAG, "Human ACK for $messageId broadcast to ${results.count { it.value }} peers.")
        return@withContext true
    }

    /**
     * Delivers unexpired pending or relayed messages to newly connected peers.
     */
    suspend fun runStoreAndForwardForPeers(peerIds: Set<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val activeMessages = dao.getActiveMeshMessages(now)

        for (item in activeMessages) {
            val sos = item.toSosMessage()
            val envelope = MeshEnvelope.fromSosMessage(
                sosMessage = sos,
                originDeviceId = item.originDeviceId ?: item.senderId,
                originDeviceName = item.senderName,
                hopLimit = item.hopLimit
            )
            val envelopeBytes = envelope.toByteArray()

            for (peerId in peerIds) {
                // Check if already delivered to this peer
                if (dao.hasDeliveredToPeer(item.messageId, peerId) == 0) {
                    val sent = transportManager.sendFrameToPeer(peerId, envelopeBytes)
                    if (sent) {
                        dao.recordPeerDelivery(MeshPeerDeliveryEntity(messageId = item.messageId, peerId = peerId))
                        Log.d(TAG, "Store-and-forward delivered message ${item.messageId} to reconnected peer $peerId")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MeshRouter"
    }
}

