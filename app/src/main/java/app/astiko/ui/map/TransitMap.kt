package app.astiko.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

const val BRIGHT_STYLE = "https://tiles.openfreemap.org/styles/bright"
const val FIORD_STYLE = "https://tiles.openfreemap.org/styles/fiord"

/**
 * Basemap by the map's effective dark flag: Bright (light) or Fiord
 * (dark, blue-tinted, matching the brand blue better than the
 * near-black Dark style).
 */
fun mapStyleUri(dark: Boolean): String = if (dark) FIORD_STYLE else BRIGHT_STYLE

/**
 * The base style's own POI icons clash with our markers. The
 * "poi_transit" layer draws bus/rail/airport icons at every zoom
 * (including the same "bus" glyph we use for live vehicles), and the
 * ranked POI layers can repeat transit icons at high zoom. Hide all
 * transit-class POIs. Ordinary POIs (shops, landmarks…) stay.
 */
fun Style.hideTransitPois() {
    if (getLayer("poi_transit") != null) removeLayer("poi_transit")
    val notTransit =
        Expression.all(
            Expression.neq(Expression.get("class"), "bus"),
            Expression.neq(Expression.get("class"), "rail"),
            Expression.neq(Expression.get("class"), "airport"),
        )
    listOf("poi_r20", "poi_r7", "poi_r1").forEach { id ->
        getLayerAs<SymbolLayer>(id)?.let { layer ->
            val current = layer.filter
            layer.setFilter(
                if (current != null) Expression.all(current, notTransit) else notTransit,
            )
        }
    }
}

/**
 * Road-name gating so both basemaps behave alike.
 *
 * Bright gates names per class: majors at 12.2, minor/service/track at
 * 15, path at 15.5. Fiord's single "highway_name_other" layer had no
 * minzoom, so it drew every name the tiles carry, at city zoom, with
 * the transliteration first: "KLEISOBHS ΚΛΕΙΣΟΒΗΣ", uppercase.
 *
 * Both themes now show majors at 12.2, minor/service/track at 14, path
 * at 15.5. Fiord prints the native-script name, sentence case. The
 * zoom check lives in the layer filter, where ["zoom"] is available.
 * Layer ids differ per style, so lookups are null-guarded.
 */
fun Style.tuneRoadLabels() {
    getLayerAs<SymbolLayer>("highway-name-minor")?.setMinZoom(14f)

    getLayerAs<SymbolLayer>("highway_name_other")?.let { layer ->
        val cls = Expression.get("class")
        val major =
            Expression.any(
                Expression.eq(cls, "primary"),
                Expression.eq(cls, "secondary"),
                Expression.eq(cls, "tertiary"),
                Expression.eq(cls, "trunk"),
            )
        val streets =
            Expression.any(
                Expression.eq(cls, "minor"),
                Expression.eq(cls, "service"),
                Expression.eq(cls, "track"),
            )
        layer.setMinZoom(12.2f)
        // Keep the layer's own checks (not motorway, line geometry).
        layer.setFilter(
            Expression.all(
                layer.filter ?: Expression.literal(true),
                Expression.any(
                    major,
                    Expression.all(Expression.gte(Expression.zoom(), 14.0), streets),
                    Expression.all(
                        Expression.gte(Expression.zoom(), 15.5),
                        Expression.eq(cls, "path"),
                    ),
                ),
            ),
        )
        layer.setProperties(
            PropertyFactory.textField(
                Expression.coalesce(
                    Expression.get("name:nonlatin"),
                    Expression.get("name:latin"),
                    Expression.get("name"),
                ),
            ),
            PropertyFactory.textTransform("none"),
        )
    }
}

/**
 * Rounded directional arrow (the "bus" marker), pointing north. Rotated by
 * the vehicle heading on the map. Rendered with anti-aliasing, a white
 * outline, rounded joins and a soft drop shadow so it stays visible on the
 * light map at any zoom.
 */
fun vehicleBitmap(): android.graphics.Bitmap {
    val size = 56
    val bitmap =
        android.graphics.Bitmap.createBitmap(
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
    val canvas = android.graphics.Canvas(bitmap)
    // Arrow body: tip (north), concave tail notch, like the Material
    // "near me" glyph.
    val path =
        android.graphics.Path().apply {
            moveTo(28f, 5f) // tip (north)
            lineTo(45f, 45f) // bottom right
            lineTo(28f, 35f) // tail notch
            lineTo(11f, 45f) // bottom left
            close()
        }
    // Soft drop shadow, offset downward.
    val shadow =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            maskFilter =
                android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
    canvas.save()
    canvas.translate(0f, 3f)
    canvas.drawPath(path, shadow)
    canvas.restore()
    // White outline first, then the blue body (rounded joins smooth the
    // corners and the tip).
    val stroke =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
    val fill =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1565C0.toInt()
            style = android.graphics.Paint.Style.FILL
        }
    canvas.drawPath(path, stroke)
    canvas.drawPath(path, fill)
    return bitmap
}

/**
 * Teardrop "you are here" pin for the stop: red head with a white outline,
 * soft shadow and a white center dot. Anchor the symbol layer to the bottom
 * so the tip sits exactly on the stop.
 */
fun stopPinBitmap(): android.graphics.Bitmap {
    val w = 48
    val h = 60
    val bitmap =
        android.graphics.Bitmap.createBitmap(
            w,
            h,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
    val canvas = android.graphics.Canvas(bitmap)
    // Head circle: center (24, 24), radius 17. Tangents from the tip
    // (24, 57) touch the circle at (15.2, 38.6) and (32.8, 38.6). The arc
    // sweeps over the top between them.
    val path =
        android.graphics.Path().apply {
            moveTo(24f, 57f) // tip
            lineTo(15.2f, 38.6f)
            addArc(android.graphics.RectF(7f, 7f, 41f, 41f), 121f, 298f)
            lineTo(24f, 57f)
            close()
        }
    val shadow =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            maskFilter =
                android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
    canvas.save()
    canvas.translate(0f, 2.5f)
    canvas.drawPath(path, shadow)
    canvas.restore()
    val stroke =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
    val fill =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt()
            style = android.graphics.Paint.Style.FILL
        }
    canvas.drawPath(path, stroke)
    canvas.drawPath(path, fill)
    // White center dot in the head.
    canvas.drawCircle(
        24f,
        24f,
        5.5f,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        },
    )
    return bitmap
}

/**
 * Compose wrapper around MapLibre's MapView (the official
 * MapLibre Compose wrapper is still immature). Handles the view lifecycle.
 * The caller receives the MapLibreMap instance to configure layers.
 * The style is applied by the caller via map.setStyle(...).
 *
 * Renderer: TextureView, not the default SurfaceView. A SurfaceView
 * punches a hole in the window: while the GL surface has no content
 * (map initializing mid-transition, or torn down on pop) the branded
 * window background flashes through. A translucent TextureView renders
 * into the view hierarchy instead: before the first frame it is
 * transparent and shows the card's own surface color, so transitions
 * stay clean. (translucentTextureSurface -> setOpaque(false). The
 * OpenFreeMap style paints a full background, so the map itself stays
 * opaque.)
 */
@Composable
fun TransitMap(
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView =
        remember {
            MapView(
                context,
                MapLibreMapOptions
                    .createFromAttributes(context)
                    .textureMode(true)
                    .translucentTextureSurface(true),
            )
        }

    // Forward the host lifecycle to the MapView (the official MapLibre
    // requirement: onStart/onResume/onPause/onStop/onDestroy). Without
    // onResume/onPause the map keeps rendering while the app is
    // backgrounded (battery drain) and can lose its GL context on
    // resume. The currentState checks cover the "already started" case:
    // an observer only receives FUTURE events, so a map composed while
    // the activity is resumed must be started here.
    DisposableEffect(lifecycleOwner, mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        mapView.onStart()
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        mapView.onResume()
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        mapView.onPause()
                    }

                    Lifecycle.Event.ON_STOP -> {
                        mapView.onStop()
                    }

                    Lifecycle.Event.ON_DESTROY -> {
                        mapView.onDestroy()
                    }

                    else -> {}
                }
            }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Screen popped while the host is still alive: tear the map
            // down. If the host itself is being destroyed, the observer
            // already delivered ON_DESTROY. Calling it again would be
            // a double destroy.
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )

    androidx.compose.runtime.LaunchedEffect(Unit) {
        mapView.getMapAsync { map -> onMapReady(map) }
    }
}
