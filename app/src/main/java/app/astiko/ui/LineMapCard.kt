package app.astiko.ui

import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.astiko.R
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Stop
import app.astiko.data.model.VehiclePosition
import app.astiko.ui.map.MapCardSurface
import app.astiko.ui.map.hideTransitPois
import app.astiko.ui.map.vehicleBitmap
import app.astiko.util.busBetweenStops
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Live line map card for the line detail screen: the route polyline,
 * its stops (red pins. Tap one for an info card with an "Arrivals"
 * button), the terminus markers (green = start, charcoal = end) and the
 * buses in transit (blue arrows, refreshed by the same 15 s poll). A bus
 * tap shows a card with the vehicle id and the stops bounding its leg.
 * Renders nothing until the caller's state arrives. The caller owns
 * the polling (see VariantStopsViewModel). The selected stop is lifted
 * to the caller (selectedStop/onSelectStop) so the stop list below can
 * scroll to and highlight the tapped stop. The selected bus stays local,
 * the list below has no bus rows.
 */
@Composable
fun LineMapCard(
    variant: LineVariant,
    geometry: List<GeoPoint>,
    stops: List<Stop>,
    vehicles: List<VehiclePosition>,
    onStopClick: (Stop) -> Unit,
    selectedStop: Stop?,
    onSelectStop: (Stop?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    var centerTick by remember { mutableIntStateOf(0) }
    var stopsById by remember { mutableStateOf(emptyMap<String, Stop>()) }
    var vehiclesById by remember { mutableStateOf(emptyMap<String, VehiclePosition>()) }
    // The tapped bus, keyed by id: the 15 s poll replaces every position
    // object, and the card must track the same bus as it moves.
    var selectedVehicleId by remember { mutableStateOf<String?>(null) }
    val selectedVehicle = vehicles.firstOrNull { it.vehicleId == selectedVehicleId }

    MapCardSurface(
        modifier = modifier.fillMaxWidth().height(240.dp),
        layers = ::addMapLayers,
        onMapReady = { mapRef = it },
        onStyleReady = { styleReady = it },
        // Refits the whole route. The zoom buttons can leave it off-screen.
        onCenterClick = { centerTick++ },
    ) {
        // Tap a stop pin to show its name plus an "Arrivals" button.
        // `shownStop` keeps the last selection alive through the exit
        // animation. The card's button opens that stop's arrivals
        // directly, since a mid-route pin may sit far from the visible
        // list rows below. The list also scrolls to the tapped stop
        // (see VariantStopsScreen).
        var shownStop by remember { mutableStateOf<Stop?>(null) }
        LaunchedEffect(selectedStop) {
            if (selectedStop != null) shownStop = selectedStop
        }
        AnimatedVisibility(
            visible = selectedStop != null,
            enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            shownStop?.let { s ->
                MapInfoCard(
                    onClose = { onSelectStop(null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    action = {
                        // Square tonal button. The clock means
                        // "arrival times", distinct from the calendar
                        // (timetable) in the top bar. In the header row
                        // next to the ✕, so the card keeps its compact
                        // height.
                        FilledTonalIconButton(onClick = { onStopClick(s) }) {
                            Icon(
                                painterResource(R.drawable.ic_arrivals),
                                contentDescription = stringResource(R.string.stop_arrivals),
                            )
                        }
                    },
                ) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    s.street?.let { street ->
                        Text(
                            street,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // `shownVehicle` keeps the last selection alive during the
        // exit animation, like the stop card above.
        var shownVehicle by remember { mutableStateOf<VehiclePosition?>(null) }
        LaunchedEffect(selectedVehicle) {
            if (selectedVehicle != null) shownVehicle = selectedVehicle
        }
        AnimatedVisibility(
            visible = selectedVehicle != null,
            enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            (selectedVehicle ?: shownVehicle)?.let { vehicle ->
                MapInfoCard(
                    onClose = { selectedVehicleId = null },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                ) {
                    Text(
                        stringResource(R.string.bus_vehicle, vehicle.vehicleId),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    busBetweenStops(stops, vehicle.lat, vehicle.lon)?.let { (prev, next) ->
                        Text(
                            stringResource(
                                R.string.bus_between,
                                stops[prev].name,
                                stops[next].name,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    // The click listener reads the latest poll through these id->object
    // maps, not the composition-time lists.
    LaunchedEffect(stops) { stopsById = stops.associateBy { it.id } }
    LaunchedEffect(vehicles) { vehiclesById = vehicles.associateBy { it.vehicleId } }

    // A direction switch must not keep the bus selection: the same fleet
    // id serves both directions.
    LaunchedEffect(variant) { selectedVehicleId = null }

    DisposableEffect(mapRef, styleReady) {
        val map = mapRef ?: return@DisposableEffect onDispose {}
        if (!styleReady) return@DisposableEffect onDispose {}
        val clickListener =
            MapLibreMap.OnMapClickListener { latLng ->
                val screenPoint = map.projection.toScreenLocation(latLng)
                // Query a small box, not a point. Finger taps rarely land dead-
                // center on a ~15 px dot, and point queries hit-test the exact
                // pixel.
                val rect =
                    RectF(
                        screenPoint.x - 30f,
                        screenPoint.y - 30f,
                        screenPoint.x + 30f,
                        screenPoint.y + 30f,
                    )
                // Buses render on top of the stop dots. Try them first.
                val vehicleId =
                    map
                        .queryRenderedFeatures(rect, VEHICLES_LAYER)
                        .mapNotNull { it.getStringProperty("vehicle_id") }
                        .minByOrNull { id ->
                            val v = vehiclesById[id]
                            if (v == null) {
                                Float.MAX_VALUE
                            } else {
                                val p =
                                    map.projection.toScreenLocation(LatLng(v.lat, v.lon))
                                val dx = p.x - screenPoint.x
                                val dy = p.y - screenPoint.y
                                dx * dx + dy * dy
                            }
                        }
                if (vehicleId != null) {
                    selectedVehicleId = vehicleId
                    // A bus and a stop never share the card, so clear the
                    // stop selection.
                    onSelectStop(null)
                    true
                } else {
                    // The endpoint markers sit on top of the first/last stop
                    // dots, hiding their pixels. Query those layers too, or the
                    // terminus stops would be untappable.
                    val features =
                        map.queryRenderedFeatures(
                            rect,
                            LINE_STOP_LAYER,
                            ROUTE_START_LAYER,
                            ROUTE_END_LAYER,
                        )
                    // Stops can sit close enough that several dots fall inside the
                    // 60 px tolerance box. Pick the one nearest the tap instead of
                    // an arbitrary first match.
                    val id =
                        features
                            .mapNotNull { it.getStringProperty("stop_id") }
                            .minByOrNull { id ->
                                val stop = stopsById[id]
                                if (stop == null) {
                                    Float.MAX_VALUE
                                } else {
                                    val p =
                                        map.projection.toScreenLocation(
                                            LatLng(stop.lat, stop.lon),
                                        )
                                    val dx = p.x - screenPoint.x
                                    val dy = p.y - screenPoint.y
                                    dx * dx + dy * dy
                                }
                            }
                    val stop = id?.let { stopsById[it] }
                    if (stop != null) {
                        selectedVehicleId = null
                        onSelectStop(stop)
                        true
                    } else {
                        // Empty map tap: deselect (the dot goes back to normal)
                        // and let the map pan.
                        selectedVehicleId = null
                        onSelectStop(null)
                        false
                    }
                }
            }
        map.addOnMapClickListener(clickListener)
        onDispose { map.removeOnMapClickListener(clickListener) }
    }

    // Camera fit on the route (fall back to the stop pins when the
    // provider has no polyline). Shared by the one-time fit on load and
    // the center button's refit.
    fun fitRoute(map: MapLibreMap) {
        val points = geometry.ifEmpty { stops.map { GeoPoint(it.lat, it.lon) } }
        if (points.isEmpty()) return
        val bounds =
            LatLngBounds
                .Builder()
                .includes(points.map { LatLng(it.lat, it.lon) })
                .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
    }

    // One-time camera fit on the route.
    LaunchedEffect(geometry, stops, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || fitted) return@LaunchedEffect
        fitted = true
        fitRoute(map)
    }

    // Center button: re-run the same fit after the user panned away.
    LaunchedEffect(centerTick, geometry, stops, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || centerTick == 0) return@LaunchedEffect
        fitRoute(map)
    }

    // Keep the selected stop's highlight dot in sync. The highlight
    // borrows the stop's own marker color, so terminus stops stay green/
    // charcoal when selected instead of turning red.
    LaunchedEffect(selectedStop, stops, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(SELECTED_STOP_SOURCE)
            if (selectedStop != null) {
                val kind = routeEndRole(stops, selectedStop.id).geoJson
                source?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        listOf(
                            Feature.fromGeometry(
                                Point.fromLngLat(selectedStop.lon, selectedStop.lat),
                                JsonObject().apply { addProperty("kind", kind) },
                            ),
                        ),
                    ),
                )
            } else {
                // Deselected: clear the source, or the dot lingers.
                source?.setGeoJson(emptyFeatures())
            }
        }
    }

    // Push geometry/stops/vehicles into the map sources. Selection is a
    // key so the tapped bus's boost applies immediately.
    LaunchedEffect(geometry, stops, vehicles, selectedVehicleId, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect

        map.getStyle { style ->
            val lineSource = style.getSourceAs<GeoJsonSource>(LINE_SOURCE)
            val stopsSource = style.getSourceAs<GeoJsonSource>(LINE_STOPS_SOURCE)
            val vehiclesSource = style.getSourceAs<GeoJsonSource>(VEHICLES_SOURCE)
            val startSource = style.getSourceAs<GeoJsonSource>(ROUTE_START_SOURCE)
            val endSource = style.getSourceAs<GeoJsonSource>(ROUTE_END_SOURCE)

            if (geometry.isNotEmpty()) {
                lineSource?.setGeoJson(
                    Feature.fromGeometry(
                        LineString.fromLngLats(geometry.map { Point.fromLngLat(it.lon, it.lat) }),
                    ),
                )
            }
            stopsSource?.setGeoJson(
                FeatureCollection.fromFeatures(
                    stops.map { stop ->
                        Feature.fromGeometry(
                            Point.fromLngLat(stop.lon, stop.lat),
                            JsonObject().apply { addProperty("stop_id", stop.id) },
                        )
                    },
                ),
            )
            vehiclesSource?.setGeoJson(
                FeatureCollection.fromFeatures(
                    vehicles.map { v ->
                        Feature.fromGeometry(
                            Point.fromLngLat(v.lon, v.lat),
                            JsonObject().apply {
                                addProperty("heading", v.heading ?: 0f)
                                addProperty("vehicle_id", v.vehicleId)
                                addProperty("selected", v.vehicleId == selectedVehicleId)
                            },
                        )
                    },
                ),
            )

            // Terminus markers: the first stop of the ordered list is the
            // origin (green), the last is the terminus (charcoal). They
            // carry stop_id so the tap handler resolves them like any
            // other stop (the underlying dots stay in the stop layer,
            // queryable beneath the marker). Loop routes (first == last
            // stop) get only the start marker, or the two would overlap
            // into one blob.
            val (start, end) = routeEndpoints(stops)
            if (start != null) {
                startSource?.setGeoJson(
                    Feature.fromGeometry(
                        Point.fromLngLat(start.lon, start.lat),
                        JsonObject().apply { addProperty("stop_id", start.id) },
                    ),
                )
            } else {
                startSource?.setGeoJson(emptyFeatures())
            }
            if (end != null) {
                endSource?.setGeoJson(
                    Feature.fromGeometry(
                        Point.fromLngLat(end.lon, end.lat),
                        JsonObject().apply { addProperty("stop_id", end.id) },
                    ),
                )
            } else {
                endSource?.setGeoJson(emptyFeatures())
            }
        }
    }
}

private const val LINE_SOURCE = "line"
private const val LINE_STOPS_SOURCE = "line-stops"
private const val LINE_STOP_LAYER = "line-stop-dots"
private const val ROUTE_START_SOURCE = "route-start"
private const val ROUTE_END_SOURCE = "route-end"
private const val ROUTE_START_LAYER = "route-start-dot"
private const val ROUTE_END_LAYER = "route-end-dot"
private const val VEHICLES_SOURCE = "vehicles"
private const val VEHICLES_LAYER = "vehicles-layer"
private const val SELECTED_STOP_SOURCE = "selected-stop"
private const val SELECTED_STOP_LAYER = "selected-stop-dot"

private fun addMapLayers(style: Style) {
    style.addSource(GeoJsonSource(LINE_SOURCE, emptyFeatures()))
    style.addLayer(
        LineLayer("line-route", LINE_SOURCE)
            .withProperties(
                PropertyFactory.lineColor("#E53935"),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap("round"),
            ),
    )

    style.addSource(GeoJsonSource(LINE_STOPS_SOURCE, emptyFeatures()))
    style.addLayer(
        CircleLayer(LINE_STOP_LAYER, LINE_STOPS_SOURCE)
            .withProperties(
                // Clean modern stop dot: white center with a red ring.
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleRadius(5.5f),
                PropertyFactory.circleStrokeColor("#E53935"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
    )

    // Terminus markers: solid green (start) and charcoal (end) dots,
    // larger than the stop pins, over the first/last stop dots. Layered
    // BELOW the selected-stop highlight so a tapped terminus shows the
    // same red highlight as any other stop.
    style.addSource(GeoJsonSource(ROUTE_START_SOURCE, emptyFeatures()))
    style.addLayer(
        CircleLayer(ROUTE_START_LAYER, ROUTE_START_SOURCE)
            .withProperties(
                PropertyFactory.circleColor(hexRgb(ROUTE_START_ARGB)),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
    )
    style.addSource(GeoJsonSource(ROUTE_END_SOURCE, emptyFeatures()))
    style.addLayer(
        CircleLayer(ROUTE_END_LAYER, ROUTE_END_SOURCE)
            .withProperties(
                PropertyFactory.circleColor(hexRgb(ROUTE_END_ARGB)),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
    )

    // Selected stop: bigger, solid, thick white ring over the dots.
    // Color follows the stop's own marker: red for regular stops, the
    // terminus colors when the first/last stop is selected.
    style.addSource(GeoJsonSource(SELECTED_STOP_SOURCE, emptyFeatures()))
    style.addLayer(
        CircleLayer(SELECTED_STOP_LAYER, SELECTED_STOP_SOURCE)
            .withProperties(
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("kind"),
                        Expression.literal("#E53935"),
                        Expression.stop("start", hexRgb(ROUTE_START_ARGB)),
                        Expression.stop("end", hexRgb(ROUTE_END_ARGB)),
                    ),
                ),
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
    )

    style.addSource(GeoJsonSource(VEHICLES_SOURCE, emptyFeatures()))
    style.addImage("bus", vehicleBitmap())
    style.addLayer(
        SymbolLayer(VEHICLES_LAYER, VEHICLES_SOURCE)
            .withProperties(
                PropertyFactory.iconImage("bus"),
                // The tapped bus renders bigger (data-driven "selected").
                PropertyFactory.iconSize(
                    Expression.switchCase(
                        Expression.eq(Expression.get("selected"), true),
                        Expression.literal(1.25f),
                        Expression.literal(1.0f),
                    ),
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconRotationAlignment("map"),
                PropertyFactory.iconRotate(Expression.get("heading")),
            ),
    )

    // The base style's bus-stop/transit icons would clash with our pins.
    style.hideTransitPois()
}

private fun emptyFeatures() = FeatureCollection.fromFeatures(emptyList())

/** "#RRGGBB" for MapLibre style properties, from the shared ARGB ints. */
private fun hexRgb(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
