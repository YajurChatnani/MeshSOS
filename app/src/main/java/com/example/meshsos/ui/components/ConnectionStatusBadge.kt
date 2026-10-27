package com.example.meshsos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.meshsos.transport.ConnectionState
import com.example.meshsos.ui.theme.SosRed
import com.example.meshsos.ui.theme.StatusAmber
import com.example.meshsos.ui.theme.StatusAmberBg
import com.example.meshsos.ui.theme.StatusBlue
import com.example.meshsos.ui.theme.StatusBlueBg
import com.example.meshsos.ui.theme.StatusGray
import com.example.meshsos.ui.theme.StatusGreen
import com.example.meshsos.ui.theme.StatusGreenBg

@Composable
fun ConnectionStatusBadge(
    connectionState: ConnectionState,
    connectedPeersCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val (dotColor, bgColor, text) = when (connectionState) {
        is ConnectionState.Connected -> {
            val label = if (connectedPeersCount > 1) "Mesh ($connectedPeersCount)" else "Connected"
            Triple(StatusGreen, StatusGreenBg, label)
        }
        is ConnectionState.Connecting -> Triple(StatusBlue, StatusBlueBg, "Connecting")
        is ConnectionState.Discovering -> Triple(StatusAmber, StatusAmberBg, "Discovering")
        is ConnectionState.PeerFound -> Triple(StatusAmber, StatusAmberBg, "Peer Found")
        is ConnectionState.Reconnecting -> Triple(StatusAmber, StatusAmberBg, "Reconnecting")
        is ConnectionState.Failed -> Triple(SosRed, Color(0xFFFEE2E2), "Error")
        is ConnectionState.Unavailable -> Triple(StatusGray, Color(0xFFE5E7EB), "Unavailable")
        is ConnectionState.CheckingSupport -> Triple(StatusGray, Color(0xFFE5E7EB), "Checking")
        is ConnectionState.Disconnected -> Triple(StatusGray, Color(0xFFE5E7EB), "Disconnected")
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF1F2937)
        )
    }
}
