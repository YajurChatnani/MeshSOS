package com.example.meshsos.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Encapsulates an incoming data frame with its ingress peer identity and transport source.
 */
data class TransportFrame(
    val peerId: String,
    val data: ByteArray,
    val transport: TransportType
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TransportFrame
        if (peerId != other.peerId) return false
        if (!data.contentEquals(other.data)) return false
        if (transport != other.transport) return false
        return true
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + transport.hashCode()
        return result
    }
}

/**
 * Common abstraction for offline direct transports (Wi-Fi Aware & Bluetooth Classic).
 */
interface Transport {
    val type: TransportType
    val connectionState: StateFlow<ConnectionState>
    val discoveredPeers: StateFlow<List<DiscoveredPeer>>
    val connectedPeers: StateFlow<List<DiscoveredPeer>>
    val incomingFrames: SharedFlow<ByteArray>
    val incomingTransportFrames: SharedFlow<TransportFrame>

    /**
     * Checks if the device hardware supports this transport.
     */
    fun isSupported(): Boolean

    /**
     * Checks if the transport is currently available (hardware enabled, radios on, permissions granted).
     * Returns a pair of (isAvailable, explanationIfNotAvailable).
     */
    fun isAvailable(): Pair<Boolean, String?>

    /**
     * Begins publishing service and scanning for nearby peers.
     */
    suspend fun startDiscovery()

    /**
     * Stops peer discovery/advertising.
     */
    suspend fun stopDiscovery()

    /**
     * Connects to a specific discovered peer.
     */
    suspend fun connect(peer: DiscoveredPeer)

    /**
     * Cleanly closes all active connections, releases sockets, and resets connection state.
     */
    suspend fun disconnect()

    /**
     * Cleanly closes a specific peer connection without affecting any other connected peers.
     */
    suspend fun disconnectPeer(peerId: String)

    /**
     * Sends a framed protocol payload over all established connections.
     * Returns true if successfully written to at least one connected peer.
     */
    suspend fun sendFrame(frame: ByteArray): Boolean

    /**
     * Sends a framed protocol payload to a specific connected peer.
     */
    suspend fun sendFrameToPeer(peerId: String, frame: ByteArray): Boolean

    /**
     * Broadcasts a framed protocol payload to all currently connected peers except [excludedPeerId].
     * Returns a map of peerId -> success boolean.
     */
    suspend fun broadcastFrame(frame: ByteArray, excludedPeerId: String? = null): Map<String, Boolean>
}
