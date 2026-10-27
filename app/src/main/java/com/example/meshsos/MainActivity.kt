package com.example.meshsos

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.meshsos.permissions.PermissionManager
import com.example.meshsos.service.SosNotificationHelper
import com.example.meshsos.ui.screens.MainNavigation
import com.example.meshsos.ui.theme.MeshSosTheme
import com.example.meshsos.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var emergencyMessageIdState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        configureEmergencyScreenFlags()
        handleEmergencyIntent(intent)

        setContent {
            MeshSosTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // Permissions updated
                }

                LaunchedEffect(Unit) {
                    val neededPermissions = mutableListOf<String>()

                    // Android 13+ Notification permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        neededPermissions.addAll(PermissionManager.getNotificationPermissions())
                    }

                    // Transport permissions
                    neededPermissions.addAll(PermissionManager.getWifiAwarePermissions())
                    neededPermissions.addAll(PermissionManager.getBluetoothPermissions())
                    neededPermissions.addAll(PermissionManager.getLocationPermissions())

                    val ungranted = neededPermissions.filter {
                        !PermissionManager.hasPermission(this@MainActivity, it)
                    }.distinct()

                    if (ungranted.isNotEmpty()) {
                        permissionLauncher.launch(ungranted.toTypedArray())
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavigation(
                        viewModel = viewModel,
                        initialEmergencyMessageId = emergencyMessageIdState
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEmergencyIntent(intent)
    }

    private fun handleEmergencyIntent(intent: Intent?) {
        if (intent == null) return
        val messageId = intent.getStringExtra(SosNotificationHelper.EXTRA_MESSAGE_ID)
        if (!messageId.isNullOrBlank()) {
            emergencyMessageIdState = messageId
        } else if (intent.action == SosNotificationHelper.ACTION_VIEW_ALERT) {
            emergencyMessageIdState = "latest"
        }
    }

    private fun configureEmergencyScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}
