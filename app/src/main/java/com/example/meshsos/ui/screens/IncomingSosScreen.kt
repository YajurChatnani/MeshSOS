package com.example.meshsos.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshsos.data.local.SosMessageEntity
import com.example.meshsos.ui.theme.SosRed
import com.example.meshsos.ui.theme.StatusGreen
import com.example.meshsos.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingSosScreen(
    viewModel: MainViewModel,
    initialMessageId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val allMessages by viewModel.messages.collectAsState()
    val unacknowledgedAlerts by viewModel.unacknowledgedAlerts.collectAsState()

    // Determine current active alert
    var currentAlertIndex by remember { mutableIntStateOf(0) }
    var loadedTargetAlert by remember { mutableStateOf<SosMessageEntity?>(null) }

    LaunchedEffect(initialMessageId) {
        if (!initialMessageId.isNullOrBlank()) {
            val found = viewModel.getMessageById(initialMessageId)
            loadedTargetAlert = found
        }
    }

    // Active alert to display:
    // If loaded by specific initialMessageId, use that; otherwise use active unacknowledged alert
    val activeAlert: SosMessageEntity? = when {
        loadedTargetAlert != null -> {
            // Keep updated state from allMessages
            allMessages.firstOrNull { it.messageId == loadedTargetAlert?.messageId } ?: loadedTargetAlert
        }
        unacknowledgedAlerts.isNotEmpty() -> {
            val safeIndex = currentAlertIndex.coerceIn(0, unacknowledgedAlerts.size - 1)
            unacknowledgedAlerts[safeIndex]
        }
        else -> {
            allMessages.firstOrNull { it.direction == "INCOMING" }
        }
    }

    val totalAlertsCount = if (unacknowledgedAlerts.isNotEmpty()) unacknowledgedAlerts.size else 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Response", fontWeight = FontWeight.Bold) },
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
        if (activeAlert == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No active emergency alerts",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Multi-alert pager banner if more than 1 active alert exists
                if (unacknowledgedAlerts.size > 1 && loadedTargetAlert == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SosRed.copy(alpha = 0.12f))
                            .border(1.dp, SosRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (currentAlertIndex > 0) currentAlertIndex--
                                },
                                enabled = currentAlertIndex > 0
                            ) {
                                Icon(Icons.Default.NavigateBefore, contentDescription = "Previous Alert")
                            }

                            Text(
                                text = "Alert ${currentAlertIndex + 1} of $totalAlertsCount",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = SosRed
                            )

                            IconButton(
                                onClick = {
                                    if (currentAlertIndex < unacknowledgedAlerts.size - 1) currentAlertIndex++
                                },
                                enabled = currentAlertIndex < unacknowledgedAlerts.size - 1
                            ) {
                                Icon(Icons.Default.NavigateNext, contentDescription = "Next Alert")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Urgent Emergency Header Badge
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(SosRed.copy(alpha = 0.15f))
                        .border(2.dp, SosRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Emergency Alert",
                        tint = SosRed,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "EMERGENCY ALERT",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    ),
                    color = SosRed
                )

                Text(
                    text = "Incoming SOS transmission received",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Sender Details Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DISTRESS SENDER",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(activeAlert.timestamp))
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val displayName = activeAlert.senderName ?: "Nearby Device (${activeAlert.senderId.takeLast(4)})"
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Node ID: ${activeAlert.senderId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Message Text Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.5.dp, SosRed.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Text(
                            text = "EMERGENCY MESSAGE",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                            color = SosRed
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = activeAlert.text.ifBlank { "No message text provided." },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Coordinates & Map Action Card
                if (activeAlert.latitude != null && activeAlert.longitude != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .padding(18.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = SosRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LOCATION COORDINATES",
                                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "${String.format(Locale.US, "%.5f", activeAlert.latitude)}°, ${String.format(Locale.US, "%.5f", activeAlert.longitude)}°",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )

                            if (activeAlert.locationAccuracy != null) {
                                Text(
                                    text = "Accuracy: ±${activeAlert.locationAccuracy}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    val geoUri = Uri.parse("geo:${activeAlert.latitude},${activeAlert.longitude}?q=${activeAlert.latitude},${activeAlert.longitude}(Emergency+Location)")
                                    val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    try {
                                        context.startActivity(mapIntent)
                                    } catch (e: Exception) {
                                        // Browser fallback
                                        val browserUri = Uri.parse("https://maps.google.com/?q=${activeAlert.latitude},${activeAlert.longitude}")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, browserUri).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        })
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Location on Map")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons: ACKNOWLEDGE & DISMISS
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isAck = activeAlert.isAcknowledged

                    Button(
                        onClick = {
                            viewModel.acknowledgeSos(activeAlert.messageId)
                        },
                        enabled = !isAck,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAck) StatusGreen else SosRed,
                            disabledContainerColor = StatusGreen
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (isAck) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Acknowledged & Confirmed",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ACKNOWLEDGE ALERT",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onNavigateBack,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("Dismiss (Keep in History)")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
