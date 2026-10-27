package com.example.meshsos.transport

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val transport: TransportType,
    val rssi: Int? = null,
    val address: String? = null,
    val rawHandle: Any? = null
)

sealed class ConnectionState {
    object CheckingSupport : ConnectionState()

    data class Unavailable(val reason: String) : ConnectionState()

    object Disconnected : ConnectionState()

    object Discovering : ConnectionState()

    data class PeerFound(val peer: DiscoveredPeer) : ConnectionState()

    data class Connecting(val peerName: String) : ConnectionState()

    data class Connected(
        val peer: DiscoveredPeer,
        val transport: TransportType,
        val connectedAt: Long = System.currentTimeMillis()
    ) : ConnectionState()

    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState()

    data class Failed(val error: String) : ConnectionState()

    fun isConnected(): Boolean = this is Connected

    fun statusLabel(): String {
        return when (this) {
            is CheckingSupport -> "Checking Support"
            is Unavailable -> "Unavailable: $reason"
            is Disconnected -> "Disconnected"
            is Discovering -> "Discovering Peers…"
            is PeerFound -> "Peer Found: ${peer.name}"
            is Connecting -> "Connecting to $peerName…"
            is Connected -> "Connected to ${peer.name}"
            is Reconnecting -> "Reconnecting ($attempt/$maxAttempts)…"
            is Failed -> "Failed: $error"
        }
    }
}
