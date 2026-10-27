package com.example.meshsos.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.meshsos.transport.TransportType
import java.util.UUID

class DevicePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    var deviceName: String
        get() {
            val custom = prefs.getString(KEY_DEVICE_NAME, null)
            if (!custom.isNullOrBlank()) return custom
            val model = Build.MODEL ?: "Android Device"
            return model.take(24)
        }
        set(value) {
            prefs.edit().putString(KEY_DEVICE_NAME, value.trim().take(24)).apply()
        }

    var selectedTransport: TransportType
        get() {
            val raw = prefs.getString(KEY_SELECTED_TRANSPORT, TransportType.WIFI_AWARE.name)
            return try {
                TransportType.valueOf(raw ?: TransportType.WIFI_AWARE.name)
            } catch (e: Exception) {
                TransportType.WIFI_AWARE
            }
        }
        set(value) {
            prefs.edit().putString(KEY_SELECTED_TRANSPORT, value.name).apply()
        }

    companion object {
        private const val PREFS_NAME = "offgrid_sos_prefs"
        private const val KEY_DEVICE_ID = "pref_device_id"
        private const val KEY_DEVICE_NAME = "pref_device_name"
        private const val KEY_SELECTED_TRANSPORT = "pref_selected_transport"
    }
}
