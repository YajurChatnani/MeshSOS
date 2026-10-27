package com.example.meshsos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SosMessageDao {

    @Query("SELECT * FROM sos_messages ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): Flow<List<SosMessageEntity>>

    @Query("SELECT * FROM sos_messages WHERE messageId = :id LIMIT 1")
    suspend fun getMessageById(id: String): SosMessageEntity?

    @Query("SELECT * FROM sos_messages WHERE direction = 'OUTGOING' AND (deliveryStatus = 'PENDING' OR deliveryStatus = 'SENT') AND expiresAt > :now ORDER BY timestamp ASC")
    suspend fun getPendingOutgoingMessages(now: Long = System.currentTimeMillis()): List<SosMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: SosMessageEntity): Long

    @Query("UPDATE sos_messages SET deliveryStatus = :status WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: String): Int

    @Query("UPDATE sos_messages SET deliveryStatus = :status, retryCount = :retryCount, lastRetryTimestamp = :lastRetryTimestamp WHERE messageId = :messageId")
    suspend fun updateRetryInfo(messageId: String, status: String, retryCount: Int, lastRetryTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM sos_messages WHERE messageId = :messageId")
    suspend fun exists(messageId: String): Int

    @Query("UPDATE sos_messages SET deliveryStatus = 'EXPIRED' WHERE expiresAt <= :now AND deliveryStatus != 'ACKNOWLEDGED'")
    suspend fun expireStaleMessages(now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE sos_messages SET isAcknowledged = 1, acknowledgedAt = :acknowledgedAt, deliveryStatus = :status WHERE messageId = :messageId")
    suspend fun markAcknowledged(messageId: String, acknowledgedAt: Long = System.currentTimeMillis(), status: String = "ACKNOWLEDGED"): Int

    @Query("SELECT * FROM sos_messages WHERE direction = 'INCOMING' AND isAcknowledged = 0 ORDER BY timestamp DESC")
    fun getUnacknowledgedIncomingMessagesFlow(): Flow<List<SosMessageEntity>>

    @Query("SELECT * FROM sos_messages WHERE direction = 'INCOMING' ORDER BY timestamp DESC")
    fun getIncomingMessagesFlow(): Flow<List<SosMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordPeerDelivery(delivery: MeshPeerDeliveryEntity): Long

    @Query("SELECT peerId FROM mesh_peer_deliveries WHERE messageId = :messageId")
    suspend fun getDeliveredPeerIds(messageId: String): List<String>

    @Query("SELECT COUNT(*) FROM mesh_peer_deliveries WHERE messageId = :messageId AND peerId = :peerId")
    suspend fun hasDeliveredToPeer(messageId: String, peerId: String): Int

    @Query("SELECT * FROM sos_messages WHERE expiresAt > :now AND deliveryStatus != 'EXPIRED' ORDER BY timestamp DESC")
    suspend fun getActiveMeshMessages(now: Long = System.currentTimeMillis()): List<SosMessageEntity>

    @Query("DELETE FROM sos_messages WHERE messageId = :id")
    suspend fun deleteMessage(id: String): Int

    @Query("DELETE FROM sos_messages")
    suspend fun clearAll(): Int

    @Query("DELETE FROM mesh_peer_deliveries")
    suspend fun clearAllDeliveries(): Int
}
