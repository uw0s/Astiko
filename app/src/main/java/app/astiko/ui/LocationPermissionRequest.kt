package app.astiko.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import app.astiko.util.hasLocationPermission

/**
 * The outcome of an in-app location permission request.
 *
 * [allowed] follows the app's rule: a COARSE-only grant ("Approximate
 * location", Android 12+) is enough for nearby stops and GPS city detection,
 * so it counts as allowed.
 *
 * [permanentlyDenied] means the system will not show the dialog again (two
 * denials on Android 11+, or an explicit "don't ask again"). The request
 * would silently do nothing, so the caller offers [Context.openAppSettings]
 * instead.
 */
data class LocationPermissionResult(
    val allowed: Boolean,
    val permanentlyDenied: Boolean,
)

/**
 * The location permission request as a launchable action, with the two rules
 * above applied. The system dialog is the one offering Precise or
 * Approximate, so the request itself always asks for FINE.
 */
@Composable
fun rememberLocationPermissionRequest(onResult: (LocationPermissionResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val allowed = granted || hasLocationPermission(context)
            val activity = context as? Activity
            val permanentlyDenied =
                !allowed &&
                    activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
            result(LocationPermissionResult(allowed, permanentlyDenied))
        }
    return { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
}

/**
 * This app's page in system settings, where a permanently denied permission
 * can be re-enabled. There is no deep link to the per-app permission page:
 * MANAGE_APP_PERMISSIONS is gated by the system-only
 * GRANT_RUNTIME_PERMISSIONS, and APP_PERMISSION_DETAILS_SETTINGS was dropped
 * from Android 16's permission controller. App info > Permissions is the
 * closest public destination, one tap away.
 */
fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
