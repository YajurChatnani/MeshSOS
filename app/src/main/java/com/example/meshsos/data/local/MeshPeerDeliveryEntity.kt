package com.example.meshsos.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "mesh_peer_deliveries",
    primaryKeys = ["messageId", "peerId"],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["peerId"])
    ]
)
data class MeshPeerDeliveryEntity(
    val messageId: String,
    val peerId: String,
    val deliveredAt: Long = System.currentTimeMillis()
)

