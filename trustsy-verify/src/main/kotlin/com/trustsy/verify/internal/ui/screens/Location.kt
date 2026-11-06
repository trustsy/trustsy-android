package com.trustsy.verify.internal.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.resume

/** Why no position could be had. Each names something different for the applicant to do. */
internal enum class LocationProblem { PERMISSION_DENIED, LOCATION_OFF, UNAVAILABLE, TIMEOUT }

internal sealed interface LocationOutcome {
    data class Fix(val location: Location) : LocationOutcome
    data class Failed(val problem: LocationProblem) : LocationOutcome
}

/**
 * A FRESH, high-accuracy fix (the web's `maximumAge: 0`): a position recorded
 * as evidence of where a person consented must not be one cached earlier.
 */
internal object Locator {
    private const val TIMEOUT_MS = 20_000L

    @SuppressLint("MissingPermission") // checked by the caller before asking
    suspend fun current(context: Context): LocationOutcome {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return LocationOutcome.Failed(LocationProblem.UNAVAILABLE)
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocationOutcome.Failed(LocationProblem.LOCATION_OFF)
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        val location = withTimeoutOrNull(TIMEOUT_MS + 2_000) {
            if (gms) fused(context) else platform(context, manager)
        } ?: return LocationOutcome.Failed(LocationProblem.TIMEOUT)
        return location.getOrNull()?.let(LocationOutcome::Fix) ?: LocationOutcome.Failed(LocationProblem.UNAVAILABLE)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fused(context: Context): Result<Location?> = suspendCancellableCoroutine { cont ->
        val cancel = CancellationTokenSource()
        cont.invokeOnCancellation { cancel.cancel() }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(TIMEOUT_MS)
            .build()
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(request, cancel.token)
            .addOnSuccessListener { if (cont.isActive) cont.resume(Result.success(it)) }
            .addOnFailureListener { if (cont.isActive) cont.resume(Result.failure(it)) }
    }

    /** No Play services: the platform's own single fix. */
    @SuppressLint("MissingPermission")
    private suspend fun platform(context: Context, manager: LocationManager): Result<Location?> =
        suspendCancellableCoroutine { cont ->
            val provider = when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> LocationManager.NETWORK_PROVIDER
            }
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                if (cont.isActive) cont.resume(Result.success(it))
            }
        }

    /** `CONSENT_GIVEN` payload; the server's struct refuses unknown fields. */
    fun consentPayload(location: Location): JsonObject = buildJsonObject {
        putJsonObject("geolocation") {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0)
            if (location.hasAltitude()) put("altitude", location.altitude)
            put("timestamp", location.time)
        }
    }
}
