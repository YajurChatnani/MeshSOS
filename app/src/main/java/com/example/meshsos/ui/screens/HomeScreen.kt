package com.example.meshsos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.protocol.DeliveryStatus
import com.example.meshsos.ui.components.ConnectionStatusBadge
import com.example.meshsos.ui.components.DeviceCard
import com.example.meshsos.ui.components.SosBigButton
import com.example.meshsos.ui.components.TransportSelector
import com.example.meshsos.ui.theme.SosRed
import com.example.meshsos.ui.theme.StatusAmber
import com.example.meshsos.ui.theme.StatusAmberBg
import com.example.meshsos.ui.theme.StatusGreen
import com.example.meshsos.ui.theme.StatusGreenBg
import com.example.meshsos.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToConnection: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToEmergencyAlert: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val selectedTransport by viewModel.selectedTransport.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val lastOutgoingSos by viewModel.lastOutgoingSos.collectAsState()
    val unacknowledgedAlerts by viewModel.unacknowledgedAlerts.collectAsState()
    val isAcquiringLocation by viewModel.isAcquiringLocation.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var showConfirmDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: App name and quick actions
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "OFFGRID SOS",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.2.sp
                            )
                        )
                        Text(
                            text = "Direct offline emergency mesh",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionStatusBadge(
                            connectionState = connectionState,
                            connectedPeersCount = connectedPeers.size
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onNavigateToConnection) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = "Connections",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNavigateToHistory) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "History",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Transport Selector Tabs
                TransportSelector(
                    selectedTransport = selectedTransport,
                    onSelectTransport = { viewModel.selectTransport(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Compact Device / Connection Card
                DeviceCard(
                    transport = selectedTransport,
                    connectionState = connectionState,
                    isSupported = viewModel.isTransportSupported(selectedTransport),
                    availability = viewModel.getTransportAvailability(selectedTransport),
                    connectedPeers = connectedPeers,
                    onManageConnection = onNavigateToConnection
                )

                if (unacknowledgedAlerts.isNotEmpty()) {
                    val activeAlert = unacknowledgedAlerts.first()
                    Spacer(modifier = Modifier.height(10.dp))
                    EmergencyAlertBanner(
                        alertCount = unacknowledgedAlerts.size,
                        latestAlert = activeAlert,
                        onClick = { onNavigateToEmergencyAlert(activeAlert.messageId) }
                    )
                }
            }

            // Center Area: Prominent Red SOS Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SosBigButton(
                    onClick = {
                        viewModel.prepareSos()
                        showConfirmDialog = true
                    }
                )
            }

            // Bottom Area: Delivery Status Pill for last outgoing SOS
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LastSosDeliveryStatusCard(
                    lastSos = lastOutgoingSos,
                    onClick = onNavigateToHistory
                )
            }
        }
    }

    if (showConfirmDialog) {
        SosConfirmDialog(
            selectedTransport = selectedTransport,
            locationResult = currentLocation,
            isAcquiringLocation = isAcquiringLocation,
            isSending = isSending,
            onConfirm = { text, includeLoc ->
                viewModel.sendSos(text, includeLoc)
                showConfirmDialog = false
            },
            onDismiss = { showConfirmDialog = false }
        )
    }
}

@Composable
private fun LastSosDeliveryStatusCard(
    lastSos: SosMessageEntity?,
    onClick: () -> Unit
) {
    if (lastSos == null) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "No active emergency alerts sent",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val (badgeBg, badgeText, statusText) = when (lastSos.deliveryStatus) {
        DeliveryStatus.ACKNOWLEDGED.name -> Triple(
            StatusGreenBg,
            StatusGreen,
            "Received & Acknowledged by Peer"
        )
        DeliveryStatus.SENT.name -> Triple(
            StatusAmberBg,
            StatusAmber,
            "Transmitted (Waiting for Peer ACK)"
        )
        DeliveryStatus.PENDING.name -> Triple(
            StatusAmberBg,
            StatusAmber,
            "Queued for Transmission"
        )
        DeliveryStatus.FAILED.name -> Triple(
            Color(0xFFFEE2E2),
            SosRed,
            "Delivery Failed (Peer unreachable)"
        )
        DeliveryStatus.EXPIRED.name -> Triple(
            Color(0xFFE5E7EB),
            Color(0xFF6B7280),
            "Expired before transmission"
        )
        else -> Triple(Color(0xFFE5E7EB), Color(0xFF6B7280), "Standby")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Last SOS Status",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(badgeBg)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = lastSos.deliveryStatus,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = badgeText
            )
        }
    }
}

@Composable
private fun EmergencyAlertBanner(
    alertCount: Int,
    latestAlert: SosMessageEntity,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SosRed)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🚨",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (alertCount > 1) "ACTIVE EMERGENCY ALERTS ($alertCount)" else "ACTIVE EMERGENCY ALERT",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    )
                    Text(
                        text = "From ${latestAlert.senderName ?: "Nearby Device"} — Tap to respond",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.9f)),
                        maxLines = 1
                    )
                }
            }
            Text(
                text = "RESPOND",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
