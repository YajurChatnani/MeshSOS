package com.example.meshsos.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meshsos.MeshSosApplication
import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.location.LocationResult
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.service.ForegroundConnectionService
import com.example.meshsos.transport.ConnectionState
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MeshSosApplication
    private val transportManager = app.transportManager
    private val repository = app.repository
    private val locationManager = app.locationManager
    val preferences = app.preferences

    val selectedTransport: StateFlow<TransportType> = transportManager.selectedTransport
    val connectionState: StateFlow<ConnectionState> = transportManager.connectionState
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = transportManager.discoveredPeers
    val connectedPeers: StateFlow<List<DiscoveredPeer>> = transportManager.connectedPeers
    val messages: StateFlow<List<SosMessageEntity>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unacknowledgedAlerts: StateFlow<List<SosMessageEntity>> = repository.unacknowledgedAlerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val incomingAlertEvents: SharedFlow<SosMessageEntity> = repository.incomingAlertEvents

    val lastOutgoingSos: StateFlow<SosMessageEntity?> = repository.allMessages
        .map { list -> list.firstOrNull { it.direction == "OUTGOING" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isAcquiringLocation = MutableStateFlow(false)
    val isAcquiringLocation: StateFlow<Boolean> = _isAcquiringLocation.asStateFlow()

    private val _currentLocation = MutableStateFlow<LocationResult?>(null)
    val currentLocation: StateFlow<LocationResult?> = _currentLocation.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun selectTransport(type: TransportType) {
        viewModelScope.launch {
            transportManager.selectTransport(type)
        }
    }

    fun startDiscovery() {
        viewModelScope.launch {
            val (avail, reason) = transportManager.isTransportAvailable(selectedTransport.value)
            if (!avail) {
                _statusMessage.value = reason ?: "Selected transport unavailable"
                return@launch
            }
            ForegroundConnectionService.startService(app)
            transportManager.startDiscovery()
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            transportManager.stopDiscovery()
        }
    }

    fun connectPeer(peer: DiscoveredPeer) {
        viewModelScope.launch {
            ForegroundConnectionService.startService(app)
            transportManager.connect(peer)
        }
    }

    fun disconnectPeer(peer: DiscoveredPeer) {
        viewModelScope.launch {
            transportManager.disconnectPeer(peer.id)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            transportManager.disconnect()
            transportManager.stopDiscovery()
            ForegroundConnectionService.stopService(app)
        }
    }

    fun acknowledgeSos(messageId: String) {
        viewModelScope.launch {
            repository.acknowledgeMessage(messageId)
        }
    }

    suspend fun getMessageById(messageId: String): SosMessageEntity? {
        return repository.getMessageById(messageId)
    }

    fun prepareSos() {
        viewModelScope.launch {
            _isAcquiringLocation.value = true
            _currentLocation.value = locationManager.acquireLocation(timeoutMs = 3500)
            _isAcquiringLocation.value = false
        }
    }

    fun sendSos(text: String, includeCoordinates: Boolean) {
        viewModelScope.launch {
            _isSending.value = true
            val loc = if (includeCoordinates) _currentLocation.value else null

            val sos = repository.createAndSendSos(
                text = text,
                latitude = if (includeCoordinates) loc?.latitude else null,
                longitude = if (includeCoordinates) loc?.longitude else null,
                accuracy = if (includeCoordinates) loc?.accuracy else null
            )

            _isSending.value = false
            _statusMessage.value = "SOS broadcast initiated"
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch {
            repository.deleteMessage(id)
        }
    }

    fun isTransportSupported(type: TransportType): Boolean {
        return transportManager.isTransportSupported(type)
    }

    fun getTransportAvailability(type: TransportType): Pair<Boolean, String?> {
        return transportManager.isTransportAvailable(type)
    }
}
