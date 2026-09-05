package app.astiko.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/*
 * Wrapper around LocationManager, shared by ViewModels. Each caller gets
 * its own tracking handle, so multiple consumers can listen at once
 * (city detector, stops list). Callers may also poll [lastKnownOrNull],
 * since fixes sometimes update the cache without reaching listeners.
 */

interface LocationTracker {
    suspend fun currentLocationOrNull(timeoutMs: Long = 5_000L): Location?

    fun lastKnownOrNull(): Location?

    fun startTracking(
        minTimeMs: Long = 5_000L,
        minDistanceM: Float = 50f,
        onFix: (Location) -> Unit,
    ): AutoCloseable
}

/**
 * The guarded calls below are annotated because this class never asks
 * for permission. Callers gate on it (ViewModels check
 * ACCESS_FINE_LOCATION before tracking) and every call is wrapped in
 * runCatching, so a SecurityException degrades to null or no-op. Lint
 * cannot see either guard.
 */
@SuppressLint("MissingPermission")
class LocationProvider(
    private val context: Context,
) : LocationTracker {
    /**
     * Best-effort single fix. Last known, then a one-time request with
     * timeout. Fast-fails when location services are off (the one-time request
     * would silently never deliver and burn the whole timeout, so the
     * "location unavailable" state shows instantly). Both providers race,
     * whichever delivers first wins. A one-time request on a single provider would
     * burn the whole timeout when that provider is unusable (GPS indoors
     * without sky view, or a device without GPS).
     */
    override suspend fun currentLocationOrNull(timeoutMs: Long): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        lastKnown(lm)?.let { return it }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = oneShotListener(lm, cont)
                var registered = false
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .forEach { provider ->
                        val ok = runCatching { requestOneShot(lm, provider, listener) }.isSuccess
                        registered = registered || ok
                    }
                if (!registered && !cont.isCompleted) cont.resume(null)
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
    }

    /**
     * Both providers race on this listener, whichever delivers the first
     * fix wins the continuation. The winner removes the listener right
     * away. The loser's one-time request stays registered (invokeOnCancellation
     * only fires on timeout or cancel), so the GPS radio stays warm until
     * an unrelated fix arrives.
     */
    private fun oneShotListener(
        lm: LocationManager,
        cont: CancellableContinuation<Location?>,
    ): LocationListener {
        lateinit var listener: LocationListener
        // The listener only ever fires after requestOneShot, so the
        // lateinit is always assigned by then.
        listener =
            locationListener { location ->
                if (!cont.isCompleted) {
                    cont.resume(location)
                    runCatching { lm.removeUpdates(listener) }
                }
            }
        return listener
    }

    /** A [LocationListener] whose only implemented callback is
     *  [LocationListener.onLocationChanged]. The other callbacks are no-ops
     *  (the two-provider setup never needs them). */
    private fun locationListener(onChanged: (Location) -> Unit): LocationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = onChanged(location)

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

    /** requestSingleUpdate is the pre-API-30 one-time request. Its
     *  replacement needs API 30+, so the deprecated call stays for
     *  minSdk 26. Wrapped once here instead of suppressing at every call
     *  site. */
    @Suppress("DEPRECATION")
    private fun requestOneShot(
        lm: LocationManager,
        provider: String,
        listener: LocationListener,
    ) {
        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    }

    override fun lastKnownOrNull(): Location? {
        val lm = context.getSystemService(LocationManager::class.java)
        return lastKnown(lm)
    }

    /** Starts continuous updates for this caller. Returns a handle, call
     *  [AutoCloseable.close] to stop. Multiple callers may track at once. */
    override fun startTracking(
        minTimeMs: Long,
        minDistanceM: Float,
        onFix: (Location) -> Unit,
    ): AutoCloseable {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = locationListener { onFix(it) }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }
        return AutoCloseable {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    private fun lastKnown(lm: LocationManager): Location? {
        val fixes =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { provider ->
                    runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                }.mapNotNull { it.toFix() }
        // Newest fresh fix wins (see bestFix), so a stale fix never serves
        // as "current". When nothing is fresh the caller falls through to
        // the request race or keeps tracking.
        return bestFix(fixes, System.currentTimeMillis(), MAX_FIX_AGE_MS)?.toLocation()
    }
}

/** Plain-data seam for unit tests. Android's Location methods return
 *  stubs under local-unit-test returnDefaultValues, so the rule
 *  operates on plain data instead. */
data class LocationFix(
    val provider: String,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
)

/**
 * Best usable fix. Rejects fixes older than [maxAgeMs] (an old fix is
 * not "current location" and must not be served as one). Newest fresh
 * fix wins. Ties go to better accuracy (lower, non-null), unreported or
 * negative ranks last. Returns null when no fresh fix exists, and
 * callers then fall through to the request race or keep tracking
 * instead of trusting stale data.
 */
internal fun bestFix(
    candidates: List<LocationFix>,
    nowMs: Long,
    maxAgeMs: Long,
): LocationFix? =
    candidates
        .filter { nowMs - it.timeMs <= maxAgeMs }
        .maxWithOrNull(
            compareBy<LocationFix> { it.timeMs }.thenBy {
                val accuracy = it.accuracyM
                // Negated so "best" sorts largest. A smaller accuracy is
                // a larger negated value, and unknown accuracy never wins
                // a tie.
                if (accuracy == null || accuracy < 0f) -Float.MAX_VALUE else -accuracy
            },
        )

private fun Location.toFix(): LocationFix =
    LocationFix(
        provider = provider ?: "", // Location.provider is nullable in Android
        timeMs = time,
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (accuracy >= 0f) accuracy else null,
    )

/** Back to Location for callers. Lat/lon/time/accuracy survive, and
 *  provider feeds the source that won the selection. */
private fun LocationFix.toLocation(): Location =
    Location(provider).apply {
        time = timeMs
        latitude = this@toLocation.latitude
        longitude = this@toLocation.longitude
        accuracyM?.let { accuracy = it }
    }

private const val MAX_FIX_AGE_MS = 15L * 60 * 1000
