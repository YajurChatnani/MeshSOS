package com.example.meshsos.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.meshsos.MainActivity
import com.example.meshsos.data.local.SosMessageEntity
import java.util.concurrent.ConcurrentHashMap

object SosNotificationHelper {

    const val EMERGENCY_CHANNEL_ID = "meshsos_emergency_alerts"
    const val ACTION_VIEW_ALERT = "com.example.meshsos.ACTION_VIEW_ALERT"
    const val ACTION_ACKNOWLEDGE_ALERT = "com.example.meshsos.ACTION_ACKNOWLEDGE_ALERT"
    const val EXTRA_MESSAGE_ID = "extra_sos_message_id"

    private val notifiedMessageIds = ConcurrentHashMap.newKeySet<String>()

    fun createEmergencyChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Emergency SOS Alerts"
            val descriptionText = "High-priority alert notifications for incoming offline SOS messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(EMERGENCY_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)

                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmSound, audioAttributes)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun postEmergencyNotification(context: Context, entity: SosMessageEntity) {
        // Prevent duplicate sound/vibration alerts for the same messageId
        if (!notifiedMessageIds.add(entity.messageId)) {
            return
        }

        createEmergencyChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. PendingIntent to launch the Emergency Screen directly
        val viewIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_ALERT
            putExtra(EXTRA_MESSAGE_ID, entity.messageId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            entity.messageId.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. PendingIntent to directly Acknowledge from notification action bar
        val ackIntent = Intent(context, SosActionReceiver::class.java).apply {
            action = ACTION_ACKNOWLEDGE_ALERT
            putExtra(EXTRA_MESSAGE_ID, entity.messageId)
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            entity.messageId.hashCode() + 1,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val senderLabel = entity.senderName ?: "Nearby Device (${entity.senderId.takeLast(4)})"
        val locationSummary = if (entity.latitude != null && entity.longitude != null) {
            "📍 Location Attached (${String.format(java.util.Locale.US, "%.4f, %.4f", entity.latitude, entity.longitude)})"
        } else {
            "No GPS Coordinates"
        }

        val builder = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 EMERGENCY SOS: $senderLabel")
            .setContentText(entity.text)
            .setSubText(locationSummary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("🚨 EMERGENCY SOS: $senderLabel")
                    .bigText("${entity.text}\n\n$locationSummary")
            )
            .setColor(0xFFFF1744.toInt()) // High-visibility red
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(viewPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_view,
                "VIEW ALERT",
                viewPendingIntent
            )
            .addAction(
                android.R.drawable.checkbox_on_background,
                "ACKNOWLEDGE",
                ackPendingIntent
            )

        // Evaluate full-screen intent strictly in accordance with Android policies
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (notificationManager.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(viewPendingIntent, true)
            }
        } else {
            builder.setFullScreenIntent(viewPendingIntent, true)
        }

        val notificationId = entity.messageId.hashCode()
        notificationManager.notify(notificationId, builder.build())
    }

    fun dismissNotification(context: Context, messageId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(messageId.hashCode())
    }
}

