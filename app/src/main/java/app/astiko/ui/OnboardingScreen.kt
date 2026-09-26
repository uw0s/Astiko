package app.astiko.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.astiko.R
import app.astiko.TransitApp
import app.astiko.data.model.City
import app.astiko.ui.theme.AstikoBlue
import app.astiko.util.hasLocationPermission

/**
 * First-launch onboarding: pick a city, or let the location decide.
 * Choosing a city never touches location. The location choice asks for
 * the permission first and explains when it's denied.
 */
@Composable
fun OnboardingScreen(
    onPickCity: (City) -> Unit,
    onUseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var permissionDenied by remember { mutableStateOf(false) }
    // Two denials flip Android 11+ into "don't ask again", and further
    // requests silently no-op forever. The button points at Settings
    // instead, same detection as the StopsScreen permission card.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val app = context.applicationContext as TransitApp
    val openAppSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
    // Coming back from the settings page with the permission granted
    // finishes onboarding (GPS city detection). The settings button would
    // otherwise be a dead end. Guarded on the denial state, so a plain
    // background/foreground trip does nothing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME &&
                    permissionDenied && hasLocationPermission(app)
                ) {
                    permissionDenied = false
                    onUseLocation()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            // granted=false can still mean "Approximate location". Android
            // 12+ grants coarse access then, which is enough for GPS city
            // detection.
            if (granted || hasLocationPermission(app)) {
                permissionDenied = false
                onUseLocation()
            } else {
                permissionDenied = true
                val activity = context as? Activity
                permanentlyDenied = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
            }
        }

    // Full-screen Surface paints the theme background over the whole
    // window. Modifier-order games with verticalScroll leave strips of the
    // window background visible. This is the only screen without a
    // Scaffold.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Cap the city list against the real viewport, not a fixed 440.dp.
        // On short screens (or with large font/display scaling) the fixed cap
        // pushes the use-location button below the fold, and since the list
        // consumes scroll gestures at its edges the page can only be scrolled
        // from thin strips outside it. The non-list content is measured live
        // (font-scale safe), so the list keeps its 440.dp cap on tall screens
        // and shrinks on short ones, and the page always fits. The
        // permission-denied hint has a slot of its own that is always part of
        // the measured content (invisible until denied), so the city list
        // keeps a constant size in both states.
        BoxWithConstraints(
            Modifier.fillMaxSize(),
        ) {
            val density = LocalDensity.current
            // The scroll viewport = window minus system insets (the Column
            // below consumes safeDrawingPadding itself).
            val viewportPx =
                with(density) {
                    maxHeight.toPx() -
                        WindowInsets.safeDrawing.getTop(density) -
                        WindowInsets.safeDrawing.getBottom(density)
                }
            var contentPx by remember { mutableIntStateOf(0) }
            var listPx by remember { mutableIntStateOf(0) }
            val listMax =
                with(density) {
                    (viewportPx - (contentPx - listPx))
                        // 48.dp floor = one city row. The page always fits down
                        // to that, then the outer scroll takes over as a last
                        // resort (huge fonts). The denial slot is always part of
                        // the measured content (see below), so the list never
                        // resizes when the box appears.
                        .coerceIn(48.dp.toPx(), 440.dp.toPx())
                        .toDp()
                }
            Column(
                Modifier
                    .fillMaxSize()
                    // Edge-to-edge. Keep the logo/header below the status bar
                    // and punch-hole cutout, and the button above the gesture
                    // bar.
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .onSizeChanged { contentPx = it.height }
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(AstikoBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Spacer(Modifier.height(32.dp))

                // One card holding the full city list (12 separate stacked cards
                // made the screen a full-page scroll). The list is always
                // expanded and scrolls inside the card only. consumeEdgeOverscroll
                // stops the leftover scroll at the list edges from drifting the
                // whole page (the outer scroll stays only as a small-screen safety
                // net, reachable by scrolling outside the list).
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Column {
                        Text(
                            stringResource(R.string.which_city),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = listMax)
                                .consumeEdgeOverscroll()
                                .onSizeChanged { listPx = it.height },
                        ) {
                            itemsIndexed(City.pickerOrder, key = {
                                _,
                                c,
                                ->
                                c.name
                            }) { index, city ->
                                CityPickerRow(city = city, onClick = { onPickCity(city) })
                                if (index < City.pickerOrder.lastIndex) {
                                    HorizontalDivider(
                                        Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.or),
                        Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }

                Button(
                    onClick = {
                        if (permanentlyDenied) {
                            // The request would silently no-op. Go to Settings.
                            openAppSettings()
                        } else {
                            permissionDenied = false
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        if (permanentlyDenied) Icons.Filled.Settings else Icons.Filled.LocationOn,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            if (permanentlyDenied) R.string.open_settings else R.string.use_location,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                // Denial slot, always composed but invisible until denied: the
                // list budget above accounts for it from the start, so the city
                // list keeps a constant size and nothing resizes when the box
                // appears. Tapping the box retries the permission request (or
                // opens Settings once the request can never succeed again).
                Spacer(Modifier.height(16.dp))
                Surface(
                    onClick = {
                        if (permanentlyDenied) {
                            openAppSettings()
                        } else {
                            permissionDenied = false
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    enabled = permissionDenied,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (permissionDenied) 1f else 0f)
                            .then(
                                if (permissionDenied) {
                                    Modifier
                                } else {
                                    Modifier.semantics { hideFromAccessibility() }
                                },
                            ),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        stringResource(R.string.location_denied_body),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CityPickerRow(
    city: City,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ListRowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) { CityNameAndOperator(city) }
    }
}
