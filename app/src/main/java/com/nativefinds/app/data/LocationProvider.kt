package com.nativefinds.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class LocationProvider(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Best current fix within ~15 s, falling back to the last known location. */
    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val fix = withTimeoutOrNull(15_000) {
            runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }.getOrNull()
        }
        if (fix == null) cts.cancel()
        return fix ?: runCatching { client.lastLocation.await() }.getOrNull()
    }
}
