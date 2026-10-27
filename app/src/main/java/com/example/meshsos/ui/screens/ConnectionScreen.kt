package com.example.meshsos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshsos.transport.ConnectionState
import com.example.meshsos.transport.DiscoveredPeer
import com.example.meshsos.transport.TransportType
import com.example.meshsos.ui.components.ConnectionStatusBadge
import com.example.meshsos.ui.components.TransportSelector
import com.example.meshsos.ui.theme.SosRed
import com.example.meshsos.ui.theme.StatusAmber
import com.example.meshsos.ui.theme.StatusBlue
import com.example.meshsos.ui.theme.StatusGreen
import com.example.meshsos.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedTransport by viewModel.selectedTransport.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()

    val isSupported = viewModel.isTransportSupported(selectedTransport)
    val availability = viewModel.getTransportAvailability(selectedTransport)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Peer Connection") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Transport Selector
            TransportSelector(
                selectedTransport = selectedTransport,
                onSelectTransport = { viewModel.selectTransport(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // State & Status Banner
            ConnectionStatusCard(
                state = connectionState,
                isSupported = isSupported,
                availability = availability,
                connectedPeers = connectedPeers,
                onDisconnectAll = { viewModel.disconnect() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Controls: Start / Stop Discovery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isDiscovering = connectionState is ConnectionState.Discovering

                Button(
                    onClick = {
                        if (isDiscovering) {
                            viewModel.stopDiscovery()
                        } else {
                            viewModel.startDiscovery()
                        }
                    },
                    enabled = isSupported && availability.first && connectionState !is ConnectionState.Connecting,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop Discovery")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Find Nearby Peers")
                    }
                }

                if (connectedPeers.isNotEmpty() || connectionState is ConnectionState.Connected) {
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SosRed)
                    ) {
                        Text("Disconnect All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Discovered Peers List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISCOVERED PEERS (${discoveredPeers.size})",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (connectedPeers.isNotEmpty()) {
                    Text(
                        text = "${connectedPeers.size} Connected",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = StatusGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Peers List
            if (discoveredPeers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (connectionState is ConnectionState.Discovering) {
                                "Scanning for offline peers nearby…"
                            } else {
                                "No peers discovered yet"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap 'Find Nearby Peers' to broadcast & listen on both phones",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(discoveredPeers) { peer ->
                        val isConnectedToThis = connectedPeers.any { it.id == peer.id }
                        val isConnectingToThis = connectionState is ConnectionState.Connecting &&
                                (connectionState as ConnectionState.Connecting).peerName == peer.name

                        PeerItemCard(
                            peer = peer,
                            isConnected = isConnectedToThis,
                            isConnecting = isConnectingToThis,
                            onConnect = { viewModel.connectPeer(peer) },
                            onDisconnect = { viewModel.disconnectPeer(peer) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    state: ConnectionState,
    isSupported: Boolean,
    availability: Pair<Boolean, String?>,
    connectedPeers: List<DiscoveredPeer>,
    onDisconnectAll: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConnectionStatusBadge(
                    connectionState = state,
                    connectedPeersCount = connectedPeers.size
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            val statusText = when {
                connectedPeers.isNotEmpty() -> {
                    if (connectedPeers.size == 1) {
                        "Connected to ${connectedPeers.first().name}"
                    } else {
                        "Connected to ${connectedPeers.size} peers: ${connectedPeers.joinToString { it.name }}"
                    }
                }
                else -> state.statusLabel()
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = when {
                    connectedPeers.isNotEmpty() || state is ConnectionState.Connected -> StatusGreen
                    state is ConnectionState.Failed -> SosRed
                    state is ConnectionState.Connecting || state is ConnectionState.Discovering -> StatusBlue
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            if (!isSupported) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = SosRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "This device lacks the required hardware feature.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SosRed
                    )
                }
            } else if (!availability.first) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = StatusAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = availability.second ?: "Transport unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerItemCard(
    peer: DiscoveredPeer,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (peer.transport == TransportType.WIFI_AWARE) Icons.Default.Wifi else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                        maxLines = 1
                    )
                    Text(
                        text = peer.transport.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        tint = StatusGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = StatusGreen
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SosRed)
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !isConnecting,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Connect")
                    }
                }
            }
        }
    }
}
