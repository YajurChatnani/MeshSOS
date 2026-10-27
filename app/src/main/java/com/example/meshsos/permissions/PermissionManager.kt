package com.example.meshsos.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.meshsos.transport.TransportType

object PermissionManager {

    /**
     * Checks if a single permission is granted.
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns the list of runtime permissions needed for Wi-Fi Aware on the current Android version.
     */
    fun getWifiAwarePermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Below Android 13, Wi-Fi Aware discovery requires Fine Location
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions
    }

    /**
     * Returns the list of runtime permissions needed for Bluetooth Classic on the current Android version.
     */
    fun getBluetoothPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions
    }

    /**
     * Returns notification permission required for Android 13+ foreground service.
     */
    fun getNotificationPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }

    /**
     * Returns location permissions for emergency coordinate acquisition.
     */
    fun getLocationPermissions(): List<String> {
        return listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /**
     * Checks if all required permissions for a specific transport are currently granted.
     */
    fun hasTransportPermissions(context: Context, transport: TransportType): Boolean {
        val perms = when (transport) {
            TransportType.WIFI_AWARE -> getWifiAwarePermissions()
            TransportType.BLUETOOTH_CLASSIC -> getBluetoothPermissions()
        }
        return perms.all { hasPermission(context, it) }
    }

    /**
     * Returns all permissions that are currently missing for the given transport.
     */
    fun getMissingTransportPermissions(context: Context, transport: TransportType): List<String> {
        val perms = when (transport) {
            TransportType.WIFI_AWARE -> getWifiAwarePermissions()
            TransportType.BLUETOOTH_CLASSIC -> getBluetoothPermissions()
        }
        return perms.filter { !hasPermission(context, it) }
    }

    /**
     * Checks if notification permission is granted.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    /**
     * Checks if location permission is granted (for optional coordinates).
     */
    fun hasLocationPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
               hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
