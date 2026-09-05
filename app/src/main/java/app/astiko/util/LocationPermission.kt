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
