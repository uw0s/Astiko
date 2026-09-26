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
import app.astiko.util.LocationPermissionResult
import app.astiko.util.hasLocationPermission
import app.astiko.util.locationPermissionResult

/**
 * The location permission request as a launchable action. The verdict (a
 * COARSE-only grant counts, a denial is permanent only once the dialog is
 * gone for good) comes from [locationPermissionResult].
 *
 * The system dialog is the one offering Precise or Approximate, so the
 * request itself always asks for FINE.
 */
@Composable
fun rememberLocationPermissionRequest(onResult: (LocationPermissionResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val activity = context as? Activity
            result(
                locationPermissionResult(
                    granted = granted,
                    hasPermission = hasLocationPermission(context),
                    canAskAgain =
                        activity == null ||
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                ),
            )
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
