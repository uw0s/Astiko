package app.astiko.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Whether the app can read a location fix: FINE **or** COARSE granted.
 *
 * Android 12+ offers the user an "Approximate location" option in the
 * permission dialog. That grants COARSE and denies FINE. Nearby stops,
 * GPS city detection and the arrivals feature all work fine with
 * approximate accuracy, so a COARSE-only grant must not dead-end them.
 * The permission REQUEST still asks for FINE. The system dialog is the
 * one offering Precise/Approximate.
 */
fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The outcome of an in-app location permission request.
 *
 * [allowed] follows the same rule as [hasLocationPermission]: a COARSE-only
 * grant counts, so an "Approximate location" answer never dead-ends a screen
 * that only needs a rough fix.
 *
 * [permanentlyDenied] means the request would silently do nothing from now on
 * (two denials on Android 11+, or an explicit "don't ask again"). The caller
 * then offers the app settings page instead of a dead button.
 */
data class LocationPermissionResult(
    val allowed: Boolean,
    val permanentlyDenied: Boolean,
)

/**
 * The verdict for one request result. Pure, so the rules above can be tested
 * without an Activity.
 *
 * [canAskAgain] is the system's answer to
 * shouldShowRequestPermissionRationale, or true when the asking context is
 * unknown. Only an explicit "the dialog will not come back" makes the denial
 * permanent, so a caller that cannot tell keeps offering the request.
 */
fun locationPermissionResult(
    granted: Boolean,
    hasPermission: Boolean,
    canAskAgain: Boolean,
): LocationPermissionResult {
    val allowed = granted || hasPermission
    return LocationPermissionResult(
        allowed = allowed,
        permanentlyDenied = !allowed && !canAskAgain,
    )
}
