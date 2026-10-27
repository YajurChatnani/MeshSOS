package com.example.meshsos.data.repository

import android.content.Context
import com.example.meshsos.data.local.SosMessageDao
import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.data.preferences.DevicePreferences
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.protocol.MeshEnvelope
import com.example.meshsos.protocol.SosMessage
import com.example.meshsos.routing.MeshRouter
import com.example.meshsos.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class SosRepository(
    private val context: Context,
    private val dao: SosMessageDao,
    private val transportManager: TransportManager,
    private val preferences: DevicePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    val meshRouter = MeshRouter(context, dao, transportManager, preferences, scope)

    val allMessages: Flow<List<SosMessageEntity>> = dao.getAllMessagesFlow()
    val unacknowledgedAlerts: Flow<List<SosMessageEntity>> = dao.getUnacknowledgedIncomingMessagesFlow()
    val incomingMessages: Flow<List<SosMessageEntity>> = dao.getIncomingMessagesFlow()
    val incomingAlertEvents: SharedFlow<SosMessageEntity> = meshRouter.incomingAlertEvents

    private var retryJob: Job? = null

    init {
        startRetryWorker()
    }

    /**
     * Creates, persists, and broadcasts an outgoing SOS across the multi-peer mesh.
     */
    suspend fun createAndSendSos(
        text: String,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null
    ): SosMessage = withContext(Dispatchers.IO) {
        meshRouter.broadcastSos(
            text = text,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy
        )
    }

    /**
     * Acknowledges an incoming SOS alert and broadcasts an ACK back through the mesh.
     */
    suspend fun acknowledgeMessage(messageId: String): Boolean = withContext(Dispatchers.IO) {
        meshRouter.acknowledgeSos(messageId)
    }

    suspend fun getMessageById(messageId: String): SosMessageEntity? = withContext(Dispatchers.IO) {
        dao.getMessageById(messageId)
    }

    /**
     * Retries unacknowledged outgoing messages using bounded exponential backoff.
     */
    private fun startRetryWorker() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_CHECK_INTERVAL_MS)
                try {
                    processPendingRetries()
                } catch (e: Exception) {
                    // Retry step error
                }
            }
        }
    }

    private suspend fun processPendingRetries() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.expireStaleMessages(now)

        if (transportManager.connectedPeers.value.isEmpty()) {
            return@withContext
        }

        val pending = dao.getPendingOutgoingMessages(now)
        for (item in pending) {
            if (item.retryCount >= MAX_RETRIES) {
                dao.updateDeliveryStatus(item.messageId, DeliveryStatus.FAILED.name)
                continue
            }

            // Exponential backoff with jitter: 2s, 4s, 8s, 16s, capped at 30s
            val backoffMs = min(30_000L, 2000L * (1 shl item.retryCount))
            if (now - item.lastRetryTimestamp >= backoffMs) {
                val newRetryCount = item.retryCount + 1
                dao.updateRetryInfo(
                    messageId = item.messageId,
                    status = DeliveryStatus.SENT.name,
                    retryCount = newRetryCount,
                    lastRetryTimestamp = now
                )

                val envelope = MeshEnvelope.fromSosMessage(
                    sosMessage = item.toSosMessage(),
                    originDeviceId = item.originDeviceId ?: item.senderId,
                    originDeviceName = item.senderName,
                    hopLimit = item.hopLimit
                )
                transportManager.broadcastFrame(envelope.toByteArray())
            }
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
        dao.clearAllDeliveries()
    }

    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        dao.deleteMessage(id)
    }

    companion object {
        const val MAX_RETRIES = 5
        const val RETRY_CHECK_INTERVAL_MS = 3000L
    }
}
