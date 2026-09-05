package app.astiko.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.astiko.ui.MapCenterButton
import app.astiko.ui.MapZoomInButton
import app.astiko.ui.MapZoomOutButton
import app.astiko.ui.theme.LocalMapDarkTheme
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Shared chrome for the map cards (line detail, arrivals): the
 * [TransitMap] surface, the style-ready loading panel, the zoom/center
 * cluster and the basemap setup. The caller owns the layers, taps,
 * camera fits and overlay cards. [onMapReady]/[onStyleReady] report the
 * internal state so the caller's effects can key on it.
 *
 * The basemap follows the map's effective dark flag: Bright (light) or
 * Fiord (dark), resolved against the in-app map override
 * (LocalMapDarkTheme). A setStyle wipes sources/layers/images, so the
 * layer setup re-runs on a flip. styleReady dips first to re-arm the
 * loading panel and the caller's data-push effects (they key on it, and
 * the fresh style's sources start empty).
 */
@Composable
fun MapCardSurface(
    modifier: Modifier = Modifier,
    layers: (Style) -> Unit,
    onMapReady: (MapLibreMap?) -> Unit = {},
    onStyleReady: (Boolean) -> Unit = {},
    onCenterClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
            ) { map ->
                mapRef = map
            }
            // The map's TextureView is transparent until the style's first
            // frame (network load). Show a neutral loading panel instead of a
            // bare hole in the card, dissolving when the map paints under it.
            AnimatedVisibility(
                visible = !styleReady,
                exit = fadeOut(tween(300)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }

            // Zoom + center controls. The zoom helps disambiguate
            // clustered markers. The center button refits to the card's
            // initial view.
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MapZoomInButton(
                    onClick = { mapRef?.animateCamera(CameraUpdateFactory.zoomBy(1.0)) },
                )
                Spacer(Modifier.height(8.dp))
                MapZoomOutButton(
                    onClick = { mapRef?.animateCamera(CameraUpdateFactory.zoomBy(-1.0)) },
                )
                Spacer(Modifier.height(8.dp))
                MapCenterButton(onClick = onCenterClick)
            }

            content()
        }
    }

    val darkTheme = LocalMapDarkTheme.current
    LaunchedEffect(mapRef, darkTheme) {
        val map = mapRef ?: return@LaunchedEffect
        styleReady = false
        map.setStyle(Style.Builder().fromUri(mapStyleUri(darkTheme))) { style ->
            style.tuneRoadLabels()
            layers(style)
            styleReady = true
        }
    }

    LaunchedEffect(mapRef) { onMapReady(mapRef) }
    LaunchedEffect(styleReady) { onStyleReady(styleReady) }
}
