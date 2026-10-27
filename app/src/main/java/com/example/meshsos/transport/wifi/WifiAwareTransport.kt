package com.example.meshsos.transport.wifi

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.meshsos.data.preferences.DevicePreferences
import com.example.meshsos.permissions.PermissionManager
import com.example.meshsos.protocol.MessageFraming
import com.example.meshsos.transport.ConnectionState
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.Transport
import com.example.meshsos.transport.TransportFrame
import com.example.meshsos.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-hardened, multi-peer offline Wi-Fi Aware (NAN) transport for MeshSOS.
 *
 * Implements:
 * - Multi-peer concurrent data paths keyed by stable device ID.
 * - Versioned identity advertisement (friendly names, stable application device IDs).
 * - Out-of-band discovery handshake (CONNECT_REQ / CONNECT_RESP) for deterministic roles.
 * - Publisher/Server (ServerSocket + setPort) and Subscriber/Client (onCapabilitiesChanged + socketFactory) sequence.
 * - Isolated per-peer sockets, network callbacks, error handling, and lifecycles.
 * - TTL-based stale-peer eviction and onServiceLost support (Android 13+).
 * - Hardware capacity boundary guards (handling max concurrent data paths).
 */
class WifiAwareTransport(
    private val context: Context,
    private val preferences: DevicePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : Transport {

    override val type: TransportType = TransportType.WIFI_AWARE

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val connectedPeers: StateFlow<List<DiscoveredPeer>> = _connectedPeers.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private val _incomingTransportFrames = MutableSharedFlow<TransportFrame>(extraBufferCapacity = 64)
    override val incomingTransportFrames: SharedFlow<TransportFrame> = _incomingTransportFrames.asSharedFlow()

    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectionMutex = Mutex()
    private val messageCounter = AtomicInteger(1)

    // Internal record binding an active DiscoveredPeer to its exact session and handle
    private data class AwarePeerRecord(
        val peer: DiscoveredPeer,
        val peerHandle: PeerHandle,
        val session: DiscoverySession,
        var lastSeenMs: Long
    )

    enum class PeerRole {
        INITIATOR, // Client role
        RESPONDER  // Server role
    }

    sealed class PeerConnectionState {
        object Connecting : PeerConnectionState()
        object Connected : PeerConnectionState()
        data class Failed(val error: String) : PeerConnectionState()
        object Disconnected : PeerConnectionState()
    }

    /**
     * Connection record for each individual peer.
     */
    private data class AwarePeerConnection(
        val peerId: String,
        val peerName: String,
        val peerHandle: PeerHandle,
        val discoverySession: DiscoverySession,
        val role: PeerRole,
        var state: PeerConnectionState = PeerConnectionState.Connecting,
        var serverSocket: ServerSocket? = null,
        var socket: Socket? = null,
        var network: Network? = null,
        var networkCallback: ConnectivityManager.NetworkCallback? = null,
        var handshakeSessionId: String? = null,
        var readJob: Job? = null,
        var watchdogJob: Job? = null,
        var lastActivityMs: Long = System.currentTimeMillis()
    ) {
        val isConnected: Boolean
            get() = socket != null && socket!!.isConnected && !socket!!.isClosed && state == PeerConnectionState.Connected
    }

    private val peerRecords = ConcurrentHashMap<String, AwarePeerRecord>()
    private val activeConnections = ConcurrentHashMap<String, AwarePeerConnection>()

    private var isDiscovering = false
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private var pruneJob: Job? = null
    private var stateReceiverRegistered = false

    private val awareStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED == intent?.action) {
                val available = wifiAwareManager?.isAvailable == true
                Log.d(TAG, "WifiAware state changed: isAvailable=$available")
                if (!available) {
                    scope.launch {
                        handleAwareBecameUnavailable("Wi-Fi Aware became unavailable (Wi-Fi turned off)")
                    }
                }
            }
        }
    }

    override fun isSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
                wifiAwareManager != null
    }

    override fun isAvailable(): Pair<Boolean, String?> {
        if (!isSupported()) {
            return Pair(false, "Device hardware lacks Wi-Fi Aware (NAN) support")
        }
        if (!PermissionManager.hasTransportPermissions(context, TransportType.WIFI_AWARE)) {
            val missing = PermissionManager.getMissingTransportPermissions(context, TransportType.WIFI_AWARE)
            return Pair(false, "Missing permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
        }
        if (wifiAwareManager?.isAvailable != true) {
            return Pair(false, "Wi-Fi Aware is currently turned off or unavailable")
        }
        return Pair(true, null)
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        val (available, reason) = isAvailable()
        if (!available) {
            _connectionState.value = ConnectionState.Unavailable(reason ?: "Wi-Fi Aware unavailable")
            return@withContext
        }

        registerStateReceiver()

        if (awareSession != null) {
            Log.d(TAG, "Reusing existing active WifiAwareSession")
            startPublishAndSubscribe()
            return@withContext
        }

        _connectionState.value = ConnectionState.Discovering

        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                Log.d(TAG, "WifiAwareSession successfully attached")
                awareSession = session
                startPublishAndSubscribe()
            }

            override fun onAttachFailed() {
                Log.e(TAG, "Failed attaching WifiAwareSession")
                _connectionState.value = ConnectionState.Failed("Could not attach Wi-Fi Aware session")
            }

            override fun onAwareSessionTerminated() {
                Log.d(TAG, "WifiAwareSession terminated")
                awareSession = null
                scope.launch {
                    stopDiscovery()
                }
            }
        }, mainHandler)
    }

    @SuppressLint("MissingPermission")
    private fun startPublishAndSubscribe() {
        val currentAwareSession = awareSession ?: run {
            Log.w(TAG, "Cannot start publish/subscribe: awareSession is null")
            return
        }

        try { publishSession?.close() } catch (e: Exception) {}
        publishSession = null
        try { subscribeSession?.close() } catch (e: Exception) {}
        subscribeSession = null

        val identity = WifiAwareIdentity(
            version = WifiAwareIdentity.CURRENT_VERSION,
            deviceId = preferences.deviceId,
            deviceName = preferences.deviceName
        )
        val identityBytes = identity.toByteArray()
        Log.d(TAG, "Starting discovery with identity: deviceId=${identity.deviceId}, deviceName=${identity.deviceName}")

        val pubConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(identityBytes)
            .build()

        currentAwareSession.publish(pubConfig, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.d(TAG, "PublishDiscoverySession started")
                publishSession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val currentPub = publishSession ?: return
                handleDiscoveryMessage(peerHandle, message, receivedOnSession = currentPub)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                Log.d(TAG, "PublishSession: message $messageId sent successfully")
            }

            override fun onMessageSendFailed(messageId: Int) {
                Log.w(TAG, "PublishSession: message $messageId send failed")
            }

            override fun onSessionTerminated() {
                Log.d(TAG, "PublishDiscoverySession terminated")
                publishSession = null
            }
        }, mainHandler)

        val subConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        currentAwareSession.subscribe(subConfig, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.d(TAG, "SubscribeDiscoverySession started")
                subscribeSession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                if (!isDiscovering) return
                val currentSub = subscribeSession ?: return

                val remoteIdentity = WifiAwareIdentity.fromByteArray(serviceSpecificInfo)

                if (remoteIdentity != null && remoteIdentity.deviceId == preferences.deviceId) {
                    return
                }

                val stableId = remoteIdentity?.deviceId ?: "aware_${peerHandle.hashCode()}"
                val friendlyName = remoteIdentity?.deviceName ?: "Wi-Fi Aware Peer (${peerHandle.hashCode().toString().takeLast(4)})"

                val peer = DiscoveredPeer(
                    id = stableId,
                    name = friendlyName,
                    transport = TransportType.WIFI_AWARE,
                    rawHandle = peerHandle
                )

                peerRecords[stableId] = AwarePeerRecord(
                    peer = peer,
                    peerHandle = peerHandle,
                    session = currentSub,
                    lastSeenMs = System.currentTimeMillis()
                )

                _discoveredPeers.value = peerRecords.values.map { it.peer }
            }

            @TargetApi(Build.VERSION_CODES.TIRAMISU)
            override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                Log.d(TAG, "onServiceLost: peerHandle=$peerHandle reason=$reason")
                val removed = peerRecords.entries.removeIf { it.value.peerHandle == peerHandle }
                if (removed) {
                    _discoveredPeers.value = peerRecords.values.map { it.peer }
                }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val currentSub = subscribeSession ?: return
                handleDiscoveryMessage(peerHandle, message, receivedOnSession = currentSub)
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                Log.d(TAG, "SubscribeSession: message $messageId sent successfully")
            }

            override fun onMessageSendFailed(messageId: Int) {
                Log.w(TAG, "SubscribeSession: message $messageId send failed")
            }

            override fun onSessionTerminated() {
                Log.d(TAG, "SubscribeDiscoverySession terminated")
                subscribeSession = null
            }
        }, mainHandler)

        isDiscovering = true
        startPeriodicPruneJob()
        updateConnectionState()
    }

    private fun startPeriodicPruneJob() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive && isDiscovering) {
                delay(STALE_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                var changed = false
                val iterator = peerRecords.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeenMs > STALE_PEER_TTL_MS) {
                        iterator.remove()
                        changed = true
                    }
                }
                if (changed) {
                    _discoveredPeers.value = peerRecords.values.map { it.peer }
                }
            }
        }
    }

    private fun handleDiscoveryMessage(
        peerHandle: PeerHandle,
        message: ByteArray,
        receivedOnSession: DiscoverySession
    ) {
        val handshake = WifiAwareHandshake.fromByteArray(message) ?: return
        Log.d(TAG, "Discovery handshake received: type=${handshake.type}, sid=${handshake.sessionId}")

        when (handshake.type) {
            HandshakeType.CONNECT_REQ -> {
                scope.launch {
                    handleIncomingConnectRequest(peerHandle, handshake, receivedOnSession)
                }
            }
            HandshakeType.CONNECT_RESP -> {
                scope.launch {
                    handleIncomingConnectResponse(peerHandle, handshake)
                }
            }
            HandshakeType.CONNECT_CANCEL -> {
                scope.launch {
                    val conn = activeConnections.values.firstOrNull { it.handshakeSessionId == handshake.sessionId }
                    if (conn != null) {
                        disconnectPeerInternal(conn.peerId)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: DiscoveredPeer) = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            val existing = activeConnections[peer.id]
            if (existing != null && existing.isConnected) {
                Log.d(TAG, "Already connected to peer ${peer.name} (${peer.id})")
                return@withContext
            }

            val record = peerRecords[peer.id] ?: run {
                _connectionState.value = ConnectionState.Failed("Peer is no longer in range")
                return@withContext
            }

            val connectedCount = activeConnections.values.count { it.isConnected }
            if (connectedCount >= MAX_CONCURRENT_DATA_PATHS) {
                _connectionState.value = ConnectionState.Failed("Maximum concurrent Wi-Fi Aware data paths reached ($MAX_CONCURRENT_DATA_PATHS)")
                return@withContext
            }

            val session = record.session
            val peerHandle = record.peerHandle
            val sessionId = UUID.randomUUID().toString()

            val connection = AwarePeerConnection(
                peerId = peer.id,
                peerName = peer.name,
                peerHandle = peerHandle,
                discoverySession = session,
                role = PeerRole.INITIATOR,
                state = PeerConnectionState.Connecting,
                handshakeSessionId = sessionId
            )
            activeConnections[peer.id] = connection
            _connectionState.value = ConnectionState.Connecting(peer.name)

            val req = WifiAwareHandshake.createConnectRequest(
                sessionId = sessionId,
                initiatorDeviceId = preferences.deviceId,
                initiatorName = preferences.deviceName,
                targetDeviceId = peer.id
            )

            try {
                session.sendMessage(peerHandle, messageCounter.incrementAndGet(), req.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending CONNECT_REQ: ${e.message}")
                disconnectPeerInternal(peer.id)
                _connectionState.value = ConnectionState.Failed("Failed to send connect request: ${e.message}")
                return@withContext
            }

            startConnectionWatchdog(connection, timeoutMs = 25000L)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleIncomingConnectRequest(
        peerHandle: PeerHandle,
        req: WifiAwareHandshake,
        session: DiscoverySession
    ) = connectionMutex.withLock {
        if (!req.targetDeviceId.isNullOrBlank() && req.targetDeviceId != preferences.deviceId) {
            return@withLock
        }

        val remotePeerId = req.initiatorDeviceId ?: "aware_${peerHandle.hashCode()}"
        val remotePeerName = req.initiatorName ?: "Wi-Fi Aware Peer"

        val existing = activeConnections[remotePeerId]
        if (existing != null && existing.isConnected) {
            // Already connected to this peer; ignore request
            return@withLock
        }

        // Simultaneous connection tie-break: lower device ID yields and becomes RESPONDER
        if (existing != null && existing.role == PeerRole.INITIATOR && !existing.isConnected) {
            val myId = preferences.deviceId
            if (myId < remotePeerId) {
                Log.d(TAG, "Simultaneous connect: local device yielding to remote initiator ($remotePeerId)")
                disconnectPeerInternal(remotePeerId)
            } else {
                Log.d(TAG, "Simultaneous connect: local device retains priority, rejecting incoming request")
                val busyResp = WifiAwareHandshake.createConnectResponse(
                    sessionId = req.sessionId,
                    responderDeviceId = preferences.deviceId,
                    status = HandshakeStatus.REJECTED_BUSY,
                    reason = "Simultaneous attempt priority"
                )
                try {
                    session.sendMessage(peerHandle, messageCounter.incrementAndGet(), busyResp.toByteArray())
                } catch (e: Exception) {}
                return@withLock
            }
        }

        val connectedCount = activeConnections.values.count { it.isConnected }
        if (connectedCount >= MAX_CONCURRENT_DATA_PATHS) {
            val busyResp = WifiAwareHandshake.createConnectResponse(
                sessionId = req.sessionId,
                responderDeviceId = preferences.deviceId,
                status = HandshakeStatus.REJECTED_BUSY,
                reason = "Hardware data path limit reached"
            )
            try {
                session.sendMessage(peerHandle, messageCounter.incrementAndGet(), busyResp.toByteArray())
            } catch (e: Exception) {}
            return@withLock
        }

        val connection = AwarePeerConnection(
            peerId = remotePeerId,
            peerName = remotePeerName,
            peerHandle = peerHandle,
            discoverySession = session,
            role = PeerRole.RESPONDER,
            state = PeerConnectionState.Connecting,
            handshakeSessionId = req.sessionId
        )
        activeConnections[remotePeerId] = connection

        val serverSocket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening ServerSocket for $remotePeerId: ${e.message}")
            disconnectPeerInternal(remotePeerId)
            return@withLock
        }
        connection.serverSocket = serverSocket
        val serverPort = serverSocket.localPort

        val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(PASSPHRASE)
            .setPort(serverPort)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connection.network = network
                scope.launch(Dispatchers.IO) {
                    try {
                        val clientSocket = serverSocket.accept()
                        onSocketConnected(connection, clientSocket)
                    } catch (e: Exception) {
                        if (isActive && connection.serverSocket == serverSocket && !connection.isConnected) {
                            Log.e(TAG, "Server accept failed: ${e.message}")
                            disconnectPeerInternal(remotePeerId)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                scope.launch {
                    disconnectPeerInternal(remotePeerId)
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    disconnectPeerInternal(remotePeerId)
                }
            }
        }

        connection.networkCallback = callback
        try {
            connectivityManager?.requestNetwork(networkRequest, callback, 25000)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork failed for responder: ${e.message}")
            disconnectPeerInternal(remotePeerId)
            return@withLock
        }

        val resp = WifiAwareHandshake.createConnectResponse(
            sessionId = req.sessionId,
            responderDeviceId = preferences.deviceId,
            status = HandshakeStatus.ACCEPTED,
            serverPort = serverPort
        )

        try {
            session.sendMessage(peerHandle, messageCounter.incrementAndGet(), resp.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending CONNECT_RESP: ${e.message}")
            disconnectPeerInternal(remotePeerId)
        }

        startConnectionWatchdog(connection, timeoutMs = 25000L)
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleIncomingConnectResponse(
        peerHandle: PeerHandle,
        resp: WifiAwareHandshake
    ) = connectionMutex.withLock {
        val connection = activeConnections.values.firstOrNull { it.handshakeSessionId == resp.sessionId }
            ?: return@withLock

        if (resp.status != HandshakeStatus.ACCEPTED) {
            Log.w(TAG, "Peer ${connection.peerName} rejected connection: ${resp.reason}")
            disconnectPeerInternal(connection.peerId)
            return@withLock
        }

        val session = connection.discoverySession
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(PASSPHRASE)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val negotiatedPort = resp.serverPort ?: TCP_PORT
        val clientConnected = AtomicBoolean(false)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                connection.network = network
                val awareInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                } else null

                val peerIpv6 = awareInfo?.peerIpv6Addr
                val port = if (awareInfo != null && awareInfo.port > 0) awareInfo.port else negotiatedPort

                if (peerIpv6 != null && !clientConnected.getAndSet(true)) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val socket = network.socketFactory.createSocket()
                            socket.connect(InetSocketAddress(peerIpv6, port), 10000)
                            onSocketConnected(connection, socket)
                        } catch (e: Exception) {
                            Log.e(TAG, "Client TCP connect failed: ${e.message}")
                            disconnectPeerInternal(connection.peerId)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                scope.launch {
                    disconnectPeerInternal(connection.peerId)
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    disconnectPeerInternal(connection.peerId)
                }
            }
        }

        connection.networkCallback = callback
        try {
            connectivityManager?.requestNetwork(networkRequest, callback, 25000)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork failed for initiator: ${e.message}")
            disconnectPeerInternal(connection.peerId)
        }
    }

    private fun onSocketConnected(connection: AwarePeerConnection, socket: Socket) {
        connection.watchdogJob?.cancel()
        connection.watchdogJob = null

        connection.socket = socket
        connection.state = PeerConnectionState.Connected
        connection.lastActivityMs = System.currentTimeMillis()

        Log.d(TAG, "Peer ${connection.peerName} (${connection.peerId}) connected successfully!")

        updateConnectionState()

        connection.readJob?.cancel()
        connection.readJob = scope.launch(Dispatchers.IO) {
            try {
                val inputStream = socket.getInputStream()
                while (isActive && connection.isConnected) {
                    val frame = MessageFraming.readFrame(inputStream) ?: break
                    connection.lastActivityMs = System.currentTimeMillis()
                    _incomingFrames.emit(frame)
                    _incomingTransportFrames.emit(
                        TransportFrame(
                            peerId = connection.peerId,
                            data = frame,
                            transport = TransportType.WIFI_AWARE
                        )
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Socket reader closed for ${connection.peerName}: ${e.message}")
            } finally {
                disconnectPeerInternal(connection.peerId)
            }
        }
    }

    override suspend fun sendFrameToPeer(peerId: String, frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val conn = activeConnections[peerId]
        if (conn == null || !conn.isConnected) {
            return@withContext false
        }
        val socket = conn.socket ?: return@withContext false
        return@withContext try {
            withTimeout(5000L) {
                MessageFraming.writeFrame(socket.getOutputStream(), frame)
                conn.lastActivityMs = System.currentTimeMillis()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending frame to peer $peerId: ${e.message}")
            disconnectPeerInternal(peerId)
            false
        }
    }

    override suspend fun broadcastFrame(frame: ByteArray, excludedPeerId: String?): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val eligible = activeConnections.values.filter { it.isConnected && it.peerId != excludedPeerId }
        if (eligible.isEmpty()) return@withContext emptyMap()

        val results = ConcurrentHashMap<String, Boolean>()
        val jobs = eligible.map { conn ->
            async {
                val ok = sendFrameToPeer(conn.peerId, frame)
                results[conn.peerId] = ok
            }
        }
        jobs.awaitAll()
        results
    }

    override suspend fun sendFrame(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val results = broadcastFrame(frame, excludedPeerId = null)
        results.values.any { it }
    }

    override suspend fun disconnectPeer(peerId: String) = withContext(Dispatchers.IO) {
        disconnectPeerInternal(peerId)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            val peerIds = activeConnections.keys.toList()
            for (id in peerIds) {
                cleanupConnectionRecord(id)
            }
            activeConnections.clear()
            updateConnectionState()
        }
    }

    private fun disconnectPeerInternal(peerId: String) {
        cleanupConnectionRecord(peerId)
        activeConnections.remove(peerId)
        updateConnectionState()
    }

    private fun cleanupConnectionRecord(peerId: String) {
        val conn = activeConnections[peerId] ?: return
        conn.watchdogJob?.cancel()
        conn.readJob?.cancel()

        try { conn.socket?.close() } catch (e: Exception) {}
        conn.socket = null

        try { conn.serverSocket?.close() } catch (e: Exception) {}
        conn.serverSocket = null

        conn.networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
        }
        conn.networkCallback = null
        conn.state = PeerConnectionState.Disconnected
    }

    private fun updateConnectionState() {
        val connectedList = activeConnections.values
            .filter { it.isConnected }
            .map { conn ->
                DiscoveredPeer(
                    id = conn.peerId,
                    name = conn.peerName,
                    transport = TransportType.WIFI_AWARE,
                    rawHandle = conn.peerHandle
                )
            }
        _connectedPeers.value = connectedList

        if (connectedList.isNotEmpty()) {
            _connectionState.value = ConnectionState.Connected(
                peer = connectedList.first(),
                transport = TransportType.WIFI_AWARE
            )
        } else if (isDiscovering) {
            _connectionState.value = ConnectionState.Discovering
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() = withContext(Dispatchers.IO) {
        isDiscovering = false
        pruneJob?.cancel()
        pruneJob = null

        unregisterStateReceiver()

        try { publishSession?.close() } catch (e: Exception) {}
        publishSession = null

        try { subscribeSession?.close() } catch (e: Exception) {}
        subscribeSession = null

        peerRecords.clear()
        _discoveredPeers.value = emptyList()

        updateConnectionState()
    }

    private fun handleAwareBecameUnavailable(reason: String) {
        scope.launch {
            disconnect()
            stopDiscovery()
            try { awareSession?.close() } catch (e: Exception) {}
            awareSession = null
            _connectionState.value = ConnectionState.Unavailable(reason)
        }
    }

    private fun startConnectionWatchdog(connection: AwarePeerConnection, timeoutMs: Long) {
        connection.watchdogJob?.cancel()
        connection.watchdogJob = scope.launch {
            delay(timeoutMs)
            if (connection.state == PeerConnectionState.Connecting && !connection.isConnected) {
                Log.w(TAG, "Connection attempt timed out for ${connection.peerName} (${connection.peerId})")
                disconnectPeerInternal(connection.peerId)
            }
        }
    }

    private fun registerStateReceiver() {
        if (!stateReceiverRegistered) {
            val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            context.registerReceiver(awareStateReceiver, filter)
            stateReceiverRegistered = true
        }
    }

    private fun unregisterStateReceiver() {
        if (stateReceiverRegistered) {
            try {
                context.unregisterReceiver(awareStateReceiver)
            } catch (e: Exception) {}
            stateReceiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "WifiAwareTransport"
        const val SERVICE_NAME = "Offline_SOS_Mesh"
        const val PASSPHRASE = "OffgridSosPass123"
        const val TCP_PORT = 49152
        const val MAX_CONCURRENT_DATA_PATHS = 4
        private const val STALE_CHECK_INTERVAL_MS = 5000L
        private const val STALE_PEER_TTL_MS = 15000L
    }
}
