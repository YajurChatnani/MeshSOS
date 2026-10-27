package com.example.meshsos.ui.screens

import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check


import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshsos.permissions.PermissionManager
import com.example.meshsos.protocol.MessageFraming
import com.example.meshsos.protocol.SosMessage
import com.example.meshsos.transport.TransportType
import com.example.meshsos.ui.theme.SosRed
import com.example.meshsos.ui.theme.StatusGreen
import com.example.meshsos.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val wifiSupported = viewModel.isTransportSupported(TransportType.WIFI_AWARE)
    val (wifiAvail, wifiReason) = viewModel.getTransportAvailability(TransportType.WIFI_AWARE)

    val btSupported = viewModel.isTransportSupported(TransportType.BLUETOOTH_CLASSIC)
    val (btAvail, btReason) = viewModel.getTransportAvailability(TransportType.BLUETOOTH_CLASSIC)

    val hasNearbyWifi = PermissionManager.hasTransportPermissions(context, TransportType.WIFI_AWARE)
    val hasBluetoothPerms = PermissionManager.hasTransportPermissions(context, TransportType.BLUETOOTH_CLASSIC)
    val hasLocationPerms = PermissionManager.hasLocationPermission(context)
    val hasNotificationPerms = PermissionManager.hasNotificationPermission(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Device Identity
            SettingsSectionCard(title = "DEVICE IDENTITY") {
                SettingRow(label = "Sender Device UUID", value = viewModel.preferences.deviceId.take(18) + "…")
                SettingRow(label = "Model Name", value = viewModel.preferences.deviceName)
                SettingRow(label = "Android Version", value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                SettingRow(label = "Hardware Manufacturer", value = Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            }

            // Section 2: Hardware Transport Capabilities
            SettingsSectionCard(title = "TRANSPORT CAPABILITIES") {
                CapabilityRow(
                    name = "Wi-Fi Aware (NAN)",
                    isSupported = wifiSupported,
                    isAvailable = wifiAvail,
                    reason = wifiReason
                )
                Spacer(modifier = Modifier.height(10.dp))
                CapabilityRow(
                    name = "Bluetooth Classic (RFCOMM)",
                    isSupported = btSupported,
                    isAvailable = btAvail,
                    reason = btReason
                )
            }

            // Section 3: Permission Status
            SettingsSectionCard(title = "RUNTIME PERMISSIONS") {
                PermissionStatusRow(
                    title = "Wi-Fi & Nearby Devices",
                    description = if (Build.VERSION.SDK_INT >= 33) "NEARBY_WIFI_DEVICES" else "ACCESS_FINE_LOCATION",
                    isGranted = hasNearbyWifi
                )
                PermissionStatusRow(
                    title = "Bluetooth Radios",
                    description = if (Build.VERSION.SDK_INT >= 31) "BLUETOOTH_CONNECT, BLUETOOTH_SCAN" else "BLUETOOTH, BLUETOOTH_ADMIN",
                    isGranted = hasBluetoothPerms
                )
                PermissionStatusRow(
                    title = "Location for Coordinates",
                    description = "ACCESS_FINE_LOCATION (Optional on-demand)",
                    isGranted = hasLocationPerms
                )
                PermissionStatusRow(
                    title = "Persistent Notifications",
                    description = "POST_NOTIFICATIONS (Android 13+)",
                    isGranted = hasNotificationPerms
                )
            }

            // Section 4: Architecture & Protocol Diagnostics
            SettingsSectionCard(title = "PROTOCOL SPECIFICATIONS") {
                SettingRow(label = "Protocol Version", value = "v${SosMessage.CURRENT_PROTOCOL_VERSION}")
                SettingRow(label = "Transport Framing", value = "Length-Prefixed (4-byte BE)")
                SettingRow(label = "Max Frame Size", value = "${MessageFraming.MAX_FRAME_SIZE / 1024} KiB")
                SettingRow(label = "Default TTL", value = "24 Hours")
                SettingRow(label = "Bounded Backoff Retries", value = "Max 5 Attempts (Exponential)")
                SettingRow(label = "Foreground Service Type", value = "connectedDevice")
                SettingRow(label = "Mesh Forwarding Readiness", value = "Hop count enabled (Initial hop=0)")
            }

            // Section 5: App Version
            SettingsSectionCard(title = "ABOUT") {
                SettingRow(label = "Application", value = "Offgrid SOS")
                SettingRow(label = "Build Version", value = "1.0.0 (Release 37)")
                SettingRow(label = "Target Platform", value = "Android 14 – 16 Native")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CapabilityRow(
    name: String,
    isSupported: Boolean,
    isAvailable: Boolean,
    reason: String?
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            val (badgeText, badgeBg, badgeColor) = when {
                !isSupported -> Triple("Unsupported", Color(0xFFFEE2E2), SosRed)
                isAvailable -> Triple("Ready", Color(0xFFDCFCE7), StatusGreen)
                else -> Triple("Unavailable", Color(0xFFFEF3C7), Color(0xFFD97706))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeBg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = badgeColor
                )
            }
        }
        if (!isAvailable && reason != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    description: String,
    isGranted: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isGranted) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (isGranted) StatusGreen else SosRed,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
