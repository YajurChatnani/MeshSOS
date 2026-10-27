package com.example.meshsos.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import com.example.meshsos.permissions.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationResult(
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val isFresh: Boolean
)

class SosLocationManager(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Attempts to acquire current location with a strict timeout.
     * If permission is missing, providers are disabled, or timeout expires, returns LocationResult with nulls.
     * NEVER blocks indefinitely.
     */
    suspend fun acquireLocation(timeoutMs: Long = 3500L): LocationResult = withContext(Dispatchers.IO) {
        if (!PermissionManager.hasLocationPermission(context) || locationManager == null) {
            return@withContext LocationResult(null, null, null, false)
        }

        try {
            // First check if a fresh last known location is already available
            val lastKnown = getRecentLastKnownLocation()
            if (lastKnown != null) {
                return@withContext LocationResult(
                    latitude = lastKnown.latitude,
                    longitude = lastKnown.longitude,
                    accuracy = if (lastKnown.hasAccuracy()) lastKnown.accuracy else null,
                    isFresh = true
                )
            }

            // Attempt to get a new fix within timeoutMs
            val freshFix = withTimeoutOrNull(timeoutMs) {
                requestSingleUpdate()
            }

            if (freshFix != null) {
                LocationResult(
                    latitude = freshFix.latitude,
                    longitude = freshFix.longitude,
                    accuracy = if (freshFix.hasAccuracy()) freshFix.accuracy else null,
                    isFresh = true
                )
            } else {
                // If timeout expired, fall back to any last known location even if older
                val fallback = getAnyLastKnownLocation()
                LocationResult(
                    latitude = fallback?.latitude,
                    longitude = fallback?.longitude,
                    accuracy = fallback?.let { if (it.hasAccuracy()) it.accuracy else null },
                    isFresh = false
                )
            }
        } catch (e: SecurityException) {
            LocationResult(null, null, null, false)
        } catch (e: Exception) {
            LocationResult(null, null, null, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getRecentLastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val now = System.currentTimeMillis()
        val twoMinutesAgo = now - (2 * 60 * 1000L)

        for (provider in providers) {
            if (!locationManager!!.isProviderEnabled(provider)) continue
            val loc = locationManager.getLastKnownLocation(provider) ?: continue
            if (loc.time >= twoMinutesAgo) {
                return loc
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun getAnyLastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            if (!locationManager!!.isProviderEnabled(provider)) continue
            val loc = locationManager.getLastKnownLocation(provider) ?: continue
            if (best == null || loc.time > best.time) {
                best = loc
            }
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
            }

            val provider = if (locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                context.mainExecutor
            ) { location ->
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager!!.removeUpdates(this)
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            continuation.invokeOnCancellation {
                locationManager!!.removeUpdates(listener)
            }

            val provider = if (locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }

            try {
                locationManager.requestSingleUpdate(provider, listener, null)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
