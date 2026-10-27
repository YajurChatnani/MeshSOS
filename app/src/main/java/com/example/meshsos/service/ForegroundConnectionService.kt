package com.example.meshsos.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.meshsos.MeshSosApplication
import com.example.meshsos.transport.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ForegroundConnectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObservationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ServiceNotificationHelper.createNotificationChannel(this)
        SosNotificationHelper.createEmergencyChannel(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                val app = application as MeshSosApplication
                serviceScope.launch {
                    app.transportManager.disconnect()
                    app.transportManager.stopDiscovery()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startInForeground()
                observeConnectionState()
            }
        }

        return START_STICKY
    }

    private fun startInForeground() {
        val app = application as MeshSosApplication
        val currentState = app.transportManager.connectionState.value
        val connectedPeers = app.transportManager.connectedPeers.value
        val notification = ServiceNotificationHelper.buildNotification(this, currentState, connectedPeers)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ServiceNotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(ServiceNotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun observeConnectionState() {
        stateObservationJob?.cancel()
        val app = application as MeshSosApplication
        stateObservationJob = serviceScope.launch {
            app.transportManager.connectedPeers.collectLatest { peers ->
                val state = app.transportManager.connectionState.value
                val notification = ServiceNotificationHelper.buildNotification(this@ForegroundConnectionService, state, peers)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(ServiceNotificationHelper.NOTIFICATION_ID, notification)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeshSOS:ConnectionWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) // 30 minute safety timeout
            }
        } catch (e: Exception) {
            Log.w("ForegroundService", "Could not acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateObservationJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.meshsos.service.ACTION_START"
        const val ACTION_STOP = "com.example.meshsos.service.ACTION_STOP"
        const val ACTION_DISCONNECT = "com.example.meshsos.service.ACTION_DISCONNECT"

        fun startService(context: Context) {
            val intent = Intent(context, ForegroundConnectionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ForegroundConnectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
