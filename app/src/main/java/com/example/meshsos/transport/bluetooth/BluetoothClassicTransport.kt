package com.example.meshsos.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.example.meshsos.permissions.PermissionManager
import com.example.meshsos.protocol.MessageFraming
import com.example.meshsos.transport.ConnectionState
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.Transport
import com.example.meshsos.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

class BluetoothClassicTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Transport {

    override val type: TransportType = TransportType.BLUETOOTH_CLASSIC

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val connectedPeers: StateFlow<List<DiscoveredPeer>> = _connectedPeers.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val _incomingTransportFrames = MutableSharedFlow<com.example.meshsos.transport.TransportFrame>(extraBufferCapacity = 64)
    override val incomingTransportFrames: SharedFlow<com.example.meshsos.transport.TransportFrame> = _incomingTransportFrames.asSharedFlow()

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var activePeer: DiscoveredPeer? = null

    private var serverJob: Job? = null
    private var readJob: Job? = null
    private var discoveryReceiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent?.let {
                        androidx.core.content.IntentCompat.getParcelableExtra(
                            it,
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    }

                    if (device != null) {
                        val name = try {
                            device.name ?: device.address ?: "Unknown Bluetooth Peer"
                        } catch (e: SecurityException) {
                            device.address ?: "Unknown Bluetooth Peer"
                        }
                        val peer = DiscoveredPeer(
                            id = device.address,
                            name = name,
                            transport = TransportType.BLUETOOTH_CLASSIC,
                            address = device.address,
                            rawHandle = device
                        )
                        addOrUpdatePeer(peer)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_connectionState.value is ConnectionState.Discovering) {
                        // Remain in discovering state as server socket is still listening
                    }
                }
            }
        }
    }

    override fun isSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
               bluetoothAdapter != null
    }

    override fun isAvailable(): Pair<Boolean, String?> {
        if (!isSupported()) {
            return Pair(false, "Device hardware does not support Bluetooth")
        }
        if (!PermissionManager.hasTransportPermissions(context, TransportType.BLUETOOTH_CLASSIC)) {
            val missing = PermissionManager.getMissingTransportPermissions(context, TransportType.BLUETOOTH_CLASSIC)
            return Pair(false, "Missing permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
        }
        if (bluetoothAdapter?.isEnabled != true) {
            return Pair(false, "Bluetooth is turned off in device settings")
        }
        return Pair(true, null)
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        val (available, reason) = isAvailable()
        if (!available) {
            _connectionState.value = ConnectionState.Unavailable(reason ?: "Bluetooth unavailable")
            return@withContext
        }

        if (_connectionState.value is ConnectionState.Connected) {
            return@withContext
        }

        _connectionState.value = ConnectionState.Discovering
        _discoveredPeers.value = emptyList()

        // 1. Load already paired devices
        try {
            val paired = bluetoothAdapter?.bondedDevices
            paired?.forEach { device ->
                val name = device.name ?: device.address ?: "Paired Peer"
                val peer = DiscoveredPeer(
                    id = device.address,
                    name = "$name (Paired)",
                    transport = TransportType.BLUETOOTH_CLASSIC,
                    address = device.address,
                    rawHandle = device
                )
                addOrUpdatePeer(peer)
            }
        } catch (e: SecurityException) {
            // Permission missing
        }

        // 2. Register discovery broadcast receiver and scan for new devices
        registerDiscoveryReceiver()
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
        } catch (e: SecurityException) {
            // Ignore
        }

        // 3. Start server socket listener to accept incoming connections from other devices
        startServerListener()
    }

    @SuppressLint("MissingPermission")
    private fun startServerListener() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    SERVICE_UUID
                )

                while (isActive) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) {
                        null
                    } ?: break

                    // Incoming connection accepted!
                    val remoteDevice = socket.remoteDevice
                    val name = try {
                        remoteDevice.name ?: remoteDevice.address ?: "Bluetooth Peer"
                    } catch (e: SecurityException) {
                        remoteDevice.address ?: "Bluetooth Peer"
                    }
                    val peer = DiscoveredPeer(
                        id = remoteDevice.address,
                        name = name,
                        transport = TransportType.BLUETOOTH_CLASSIC,
                        address = remoteDevice.address,
                        rawHandle = remoteDevice
                    )

                    onSocketConnected(socket, peer)
                    break
                }
            } catch (e: Exception) {
                // Server listener terminated
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() = withContext(Dispatchers.IO) {
        unregisterDiscoveryReceiver()
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: SecurityException) {
            // Ignore
        }
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        serverSocket = null

        if (_connectionState.value is ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: DiscoveredPeer) = withContext(Dispatchers.IO) {
        val (available, reason) = isAvailable()
        if (!available) {
            _connectionState.value = ConnectionState.Unavailable(reason ?: "Bluetooth unavailable")
            return@withContext
        }

        disconnect()

        _connectionState.value = ConnectionState.Connecting(peer.name)

        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: SecurityException) {
            // Ignore
        }

        val device: BluetoothDevice = peer.rawHandle as? BluetoothDevice
            ?: bluetoothAdapter?.getRemoteDevice(peer.address ?: peer.id)
            ?: run {
                _connectionState.value = ConnectionState.Failed("Unable to resolve Bluetooth peer device")
                return@withContext
            }

        try {
            val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            onSocketConnected(socket, peer)
        } catch (e: IOException) {
            _connectionState.value = ConnectionState.Failed("Connection failed: ${e.message}")
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.Failed("Bluetooth permission denied")
        }
    }

    private fun onSocketConnected(socket: BluetoothSocket, peer: DiscoveredPeer) {
        activeSocket = socket
        activePeer = peer
        _connectedPeers.value = listOf(peer)
        _connectionState.value = ConnectionState.Connected(
            peer = peer,
            transport = TransportType.BLUETOOTH_CLASSIC
        )

        readJob?.cancel()
        readJob = scope.launch {
            try {
                val inputStream = socket.inputStream
                while (isActive && activeSocket == socket) {
                    val frame = MessageFraming.readFrame(inputStream) ?: break
                    _incomingFrames.emit(frame)
                    _incomingTransportFrames.emit(
                        com.example.meshsos.transport.TransportFrame(
                            peerId = peer.id,
                            data = frame,
                            transport = TransportType.BLUETOOTH_CLASSIC
                        )
                    )
                }
            } catch (e: Exception) {
                // Read failed or socket closed
            } finally {
                if (activeSocket == socket) {
                    disconnect()
                }
            }
        }
    }

    override suspend fun sendFrame(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val socket = activeSocket
        if (socket == null || !socket.isConnected) {
            return@withContext false
        }
        return@withContext try {
            MessageFraming.writeFrame(socket.outputStream, frame)
            true
        } catch (e: IOException) {
            disconnect()
            false
        }
    }

    override suspend fun sendFrameToPeer(peerId: String, frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (activePeer?.id == peerId) {
            sendFrame(frame)
        } else {
            false
        }
    }

    override suspend fun broadcastFrame(frame: ByteArray, excludedPeerId: String?): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val currentPeer = activePeer
        if (currentPeer != null && currentPeer.id != excludedPeerId) {
            val ok = sendFrame(frame)
            mapOf(currentPeer.id to ok)
        } else {
            emptyMap()
        }
    }

    override suspend fun disconnectPeer(peerId: String) = withContext(Dispatchers.IO) {
        if (activePeer?.id == peerId) {
            disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        readJob?.cancel()
        readJob = null

        try {
            activeSocket?.close()
        } catch (e: IOException) {
            // Ignore
        }
        activeSocket = null
        activePeer = null
        _connectedPeers.value = emptyList()

        _connectionState.value = ConnectionState.Disconnected
    }

    private fun addOrUpdatePeer(peer: DiscoveredPeer) {
        val current = _discoveredPeers.value.toMutableList()
        val index = current.indexOfFirst { it.id == peer.id }
        if (index >= 0) {
            current[index] = peer
        } else {
            current.add(peer)
        }
        _discoveredPeers.value = current
    }

    private fun registerDiscoveryReceiver() {
        if (!discoveryReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(discoveryReceiver, filter)
            discoveryReceiverRegistered = true
        }
    }

    private fun unregisterDiscoveryReceiver() {
        if (discoveryReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: Exception) {
                // Ignore
            }
            discoveryReceiverRegistered = false
        }
    }

    companion object {
        const val SERVICE_NAME = "Offline_SOS_Mesh"
        val SERVICE_UUID: UUID = UUID.fromString("4a21e42a-8d76-4d0f-a39c-72e2cf178a9c")
    }
}
