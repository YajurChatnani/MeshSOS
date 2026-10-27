package com.example.meshsos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.meshsos.MainActivity
import com.example.meshsos.R
import com.example.meshsos.transport.ConnectionState

object ServiceNotificationHelper {

    const val CHANNEL_ID = "offgrid_sos_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.channel_name)
            val descriptionText = context.getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        state: ConnectionState,
        connectedPeers: List<com.example.meshsos.transport.DiscoveredPeer> = emptyList()
    ): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(context, ForegroundConnectionService::class.java).apply {
            action = ForegroundConnectionService.ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            context,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when {
            connectedPeers.isNotEmpty() -> {
                if (connectedPeers.size == 1) {
                    "Connected to ${connectedPeers.first().name}"
                } else {
                    "Mesh Active: Connected to ${connectedPeers.size} peers"
                }
            }
            state is ConnectionState.Connected -> "Connected to ${state.peer.name} (${state.transport.displayName})"
            state is ConnectionState.Connecting -> "Connecting to ${state.peerName}…"
            state is ConnectionState.Discovering -> "Discovering nearby peers…"
            state is ConnectionState.PeerFound -> "Found peer: ${state.peer.name}"
            state is ConnectionState.Reconnecting -> "Reconnecting (${state.attempt}/${state.maxAttempts})…"
            state is ConnectionState.Unavailable -> "Unavailable: ${state.reason}"
            state is ConnectionState.Failed -> "Error: ${state.error}"
            else -> "Active (Standby)"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Offgrid SOS")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.disconnect_action),
                disconnectPendingIntent
            )

        return builder.build()
    }
}
