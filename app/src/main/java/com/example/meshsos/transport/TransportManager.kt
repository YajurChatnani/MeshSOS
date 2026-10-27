package com.example.meshsos.transport

import android.content.Context
import com.example.meshsos.data.preferences.DevicePreferences
import com.example.meshsos.transport.bluetooth.BluetoothClassicTransport
import com.example.meshsos.transport.wifi.WifiAwareTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransportManager(
    private val context: Context,
    private val preferences: DevicePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val wifiAwareTransport = WifiAwareTransport(context, preferences, scope)
    val bluetoothTransport = BluetoothClassicTransport(context, scope)

    private val _selectedTransport = MutableStateFlow(preferences.selectedTransport)
    val selectedTransport: StateFlow<TransportType> = _selectedTransport.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val _incomingTransportFrames = MutableSharedFlow<TransportFrame>(extraBufferCapacity = 64)
    val incomingTransportFrames: SharedFlow<TransportFrame> = _incomingTransportFrames.asSharedFlow()

    val currentTransport: Transport
        get() = when (_selectedTransport.value) {
            TransportType.WIFI_AWARE -> wifiAwareTransport
            TransportType.BLUETOOTH_CLASSIC -> bluetoothTransport
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<ConnectionState> = _selectedTransport
        .flatMapLatest { type ->
            when (type) {
                TransportType.WIFI_AWARE -> wifiAwareTransport.connectionState
                TransportType.BLUETOOTH_CLASSIC -> bluetoothTransport.connectionState
            }
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _selectedTransport
        .flatMapLatest { type ->
            when (type) {
                TransportType.WIFI_AWARE -> wifiAwareTransport.discoveredPeers
                TransportType.BLUETOOTH_CLASSIC -> bluetoothTransport.discoveredPeers
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val connectedPeers: StateFlow<List<DiscoveredPeer>> = _selectedTransport
        .flatMapLatest { type ->
            when (type) {
                TransportType.WIFI_AWARE -> wifiAwareTransport.connectedPeers
                TransportType.BLUETOOTH_CLASSIC -> bluetoothTransport.connectedPeers
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        listenToFrames()
    }

    private fun listenToFrames() {
        scope.launch {
            wifiAwareTransport.incomingFrames.collect { frame ->
                if (_selectedTransport.value == TransportType.WIFI_AWARE) {
                    _incomingFrames.emit(frame)
                }
            }
        }
        scope.launch {
            wifiAwareTransport.incomingTransportFrames.collect { tFrame ->
                if (_selectedTransport.value == TransportType.WIFI_AWARE) {
                    _incomingTransportFrames.emit(tFrame)
                }
            }
        }
        scope.launch {
            bluetoothTransport.incomingFrames.collect { frame ->
                if (_selectedTransport.value == TransportType.BLUETOOTH_CLASSIC) {
                    _incomingFrames.emit(frame)
                }
            }
        }
        scope.launch {
            bluetoothTransport.incomingTransportFrames.collect { tFrame ->
                if (_selectedTransport.value == TransportType.BLUETOOTH_CLASSIC) {
                    _incomingTransportFrames.emit(tFrame)
                }
            }
        }
    }

    /**
     * Safely switches transport after disconnecting the previous one.
     */
    suspend fun selectTransport(type: TransportType) = withContext(Dispatchers.IO) {
        if (_selectedTransport.value == type) return@withContext

        // 1. Cleanly disconnect previous transport
        currentTransport.stopDiscovery()
        currentTransport.disconnect()

        // 2. Persist user selection
        preferences.selectedTransport = type
        _selectedTransport.value = type
    }

    suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        currentTransport.startDiscovery()
    }

    suspend fun stopDiscovery() = withContext(Dispatchers.IO) {
        currentTransport.stopDiscovery()
    }

    suspend fun connect(peer: DiscoveredPeer) = withContext(Dispatchers.IO) {
        currentTransport.connect(peer)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        currentTransport.disconnect()
    }

    suspend fun disconnectPeer(peerId: String) = withContext(Dispatchers.IO) {
        currentTransport.disconnectPeer(peerId)
    }

    suspend fun sendFrame(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        currentTransport.sendFrame(frame)
    }

    suspend fun sendFrameToPeer(peerId: String, frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        currentTransport.sendFrameToPeer(peerId, frame)
    }

    suspend fun broadcastFrame(frame: ByteArray, excludedPeerId: String? = null): Map<String, Boolean> = withContext(Dispatchers.IO) {
        currentTransport.broadcastFrame(frame, excludedPeerId)
    }

    fun isTransportSupported(type: TransportType): Boolean {
        return when (type) {
            TransportType.WIFI_AWARE -> wifiAwareTransport.isSupported()
            TransportType.BLUETOOTH_CLASSIC -> bluetoothTransport.isSupported()
        }
    }

    fun isTransportAvailable(type: TransportType): Pair<Boolean, String?> {
        return when (type) {
            TransportType.WIFI_AWARE -> wifiAwareTransport.isAvailable()
            TransportType.BLUETOOTH_CLASSIC -> bluetoothTransport.isAvailable()
        }
    }
}
