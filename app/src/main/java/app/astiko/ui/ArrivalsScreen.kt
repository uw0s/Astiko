package app.astiko.ui

import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.astiko.R
import app.astiko.data.POLL_INTERVAL_MS
import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Stop
import app.astiko.ui.map.MapCardSurface
import app.astiko.ui.map.hideTransitPois
import app.astiko.ui.map.stopPinBitmap
import app.astiko.ui.map.vehicleBitmap
import app.astiko.util.mapsDirectionsUrl
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrivalsScreen(
    stop: Stop,
    onBack: () -> Unit,
    onVariantClick: (LineVariant) -> Unit,
    onTimetableClick: (() -> Unit)? = null,
) {
    val viewModel: ArrivalsViewModel =
        viewModel(
            key = "arrivals-${stop.provider}-${stop.id}",
            factory = ArrivalsViewModel.factory(stop),
        )
    val routes by viewModel.routes.collectAsState()
    val arrivals by viewModel.arrivals.collectAsState()
    val routeGeometry by viewModel.routeGeometry.collectAsState()
    val selectedArrival by viewModel.selectedArrival.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val favorites by viewModel.favoriteStops.collectAsState()
    val isFavorite = favorites.any { it.id == stop.id }

    // Hidden when the provider has no stop timetable. OASA has
    // none, OSETh and CityBus do.
    val supportsTimetable = viewModel.supportsTimetable

    val context = LocalContext.current
    val onDirectionsClick: () -> Unit = {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(mapsDirectionsUrl(GeoPoint(stop.lat, stop.lon))),
            ),
        )
    }

    // The bus selected from the arrivals list or by tapping the map lives
    // in the ViewModel: the poll owns the dismiss rule (a bus that leaves
    // the poll clears its own selection). The focus tick re-triggers the
    // camera move even when the same row is tapped again (the Arrival
    // instance may be unchanged between polls).
    var focusTick by remember { mutableIntStateOf(0) }
    var sheetLine by remember { mutableStateOf<Line?>(null) }

    // Foreground hook: ON_START restarts the poll instead of waiting
    // for its next tick (see refreshNow for the no-double-fetch gate).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) viewModel.refreshNow()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val onArrivalClick: (Arrival?) -> Unit = { arrival ->
        focusTick++
        // The selection (card + route polyline) lives in the ViewModel.
        // Null (card closed) clears it.
        viewModel.selectBus(arrival)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stop.name) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        // Directions first. The calendar below it depends
                        // on the provider.
                        IconButton(onClick = onDirectionsClick) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_directions_walk),
                                contentDescription =
                                    stringResource(R.string.directions_to_stop),
                            )
                        }
                        // Full-day schedule (static, per weekday). Only for
                        // providers whose API exposes a stop timetable.
                        if (supportsTimetable) {
                            onTimetableClick?.let { click ->
                                IconButton(onClick = click) {
                                    Icon(
                                        Icons.Filled.DateRange,
                                        contentDescription = stringResource(R.string.schedule),
                                    )
                                }
                            }
                        }
                        // FavoriteIcon's own clickable box is the button.
                        // Wrapping it in an IconButton would swallow the
                        // tap, the inner clickable consumes it first.
                        // Favorites are warmed at app start, so the heart's
                        // state is correct from the first frame. Same 40.dp
                        // state layer as the calendar's IconButton (48.dp
                        // reserved for the touch target).
                        FavoriteIcon(
                            isFavorite = isFavorite,
                            onClick = { viewModel.toggleFavorite(stop) },
                            modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
                        )
                    },
                )
                OfflineBanner(stop.provider)
            }
        },
    ) { padding ->
        // Crossfade between loading / error / content, so the spinner
        // does not cut straight into the map card.
        AnimatedContent(
            targetState =
                when {
                    loading -> ArrivalsScreenState.LOADING
                    error != null -> ArrivalsScreenState.ERROR
                    else -> ArrivalsScreenState.CONTENT
                },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "arrivals-state",
        ) { state ->
            when (state) {
                ArrivalsScreenState.LOADING -> {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                ArrivalsScreenState.ERROR -> {
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.error_generic),
                        body = error ?: stringResource(R.string.unknown_error),
                        error = true,
                        center = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = viewModel::retry,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }

                ArrivalsScreenState.CONTENT -> {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        // Offline/schedule fallback. Every row is a
                        // cached-schedule estimate, so the "last updated"
                        // timestamp must not masquerade as live telemetry.
                        val scheduled = arrivals.isNotEmpty() && arrivals.all { it.isScheduled }
                        ArrivalsMapCard(
                            stop = stop,
                            arrivals = arrivals,
                            selectedArrival = selectedArrival,
                            focusTick = focusTick,
                            routeGeometry = routeGeometry,
                            onArrivalClick = onArrivalClick,
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp,
                                ),
                        )
                        LazyColumn(Modifier.weight(1f)) {
                            item {
                                SectionHeader(stringResource(R.string.next_arrivals)) {}
                            }
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(
                                        horizontal = 16.dp,
                                        vertical = 4.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CountdownRing(lastUpdated)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (scheduled) {
                                            stringResource(R.string.scheduled_notice)
                                        } else {
                                            stringResource(
                                                R.string.last_updated,
                                                lastUpdated ?: "–",
                                            )
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (arrivals.isEmpty()) {
                                item {
                                    EmptyState(
                                        icon = ImageVector.vectorResource(R.drawable.ic_bus),
                                        title = stringResource(R.string.no_arrivals),
                                    )
                                }
                            } else {
                                // Keyed with an index suffix, so a duplicate
                                // identity in one emission can never crash the
                                // list. Loop routes like Xanthi 02-12 or Serres
                                // 002/004/023 can report both passes of one trip
                                // in a single stops/live response, and the next
                                // pass, the earliest ETA, wins (CityBus dedupes
                                // those too).
                                itemsIndexed(arrivals, key = { index, a ->
                                    // OASA's tripId is always null and vehicleId
                                    // is the bus number, so a loop bus would
                                    // collide without the suffix. OSETh's tripId
                                    // is stable across polls, so the suffix only
                                    // kicks in on such duplicates. Row identity
                                    // on reorder is the only cosmetic regression.
                                    "${a.tripId ?: "${a.routeCode}-${a.vehicleId ?: "nov"}"}-$index"
                                }) { _, arrival ->
                                    ArrivalRow(
                                        arrival,
                                        onClick = { onArrivalClick(arrival) },
                                        modifier =
                                            Modifier.animateItem(
                                                fadeInSpec = null,
                                                fadeOutSpec = null,
                                            ),
                                    )
                                }
                            }

                            item {
                                SectionHeader(stringResource(R.string.lines_at_stop)) {}
                            }
                            // Keyed with an index suffix. A route_code could appear
                            // twice at one stop (merged public numbers, direction
                            // variants), and a bare id would crash the list.
                            itemsIndexed(routes, key = {
                                index,
                                line,
                                ->
                                "${line.id}-$index"
                            }) { _, line ->
                                LineRow(
                                    line,
                                    onClick = { sheetLine = line },
                                    modifier =
                                        Modifier.animateItem(
                                            fadeInSpec = null,
                                            fadeOutSpec = null,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Tap a line to pick its direction, then open its stop list (with map).
    sheetLine?.let { line ->
        ModalBottomSheet(
            onDismissRequest = { sheetLine = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            // Own ViewModel store per sheet visit (see LinesScreen).
            ScopedViewModelStore {
                LineVariantsSheet(
                    line = line,
                    onPick = { variant ->
                        sheetLine = null
                        onVariantClick(variant)
                    },
                )
            }
        }
    }
}

@Composable
private fun LineRow(
    line: Line,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(
                ListRowShape,
            ).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(line.shortName)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                line.longName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.destination.isNotEmpty() && line.destination != line.longName) {
                Text(
                    line.destination,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Live arrival whose incoming bus has no GPS fix. The arrivals row
 * shows the location-off icon (pin + slash) so the missing position is
 * obvious before tapping. Scheduled (offline) rows never qualify.
 * They carry the calendar icon instead, and a schedule estimate has
 * no bus to locate.
 */
fun Arrival.showsNoLocationIcon(): Boolean = !isScheduled && vehicle == null

/**
 * Live countdown within the 90-minute window (see [arrivalTimeText]).
 * The list row and the card badge share this one check.
 */
private val Arrival.isImminent: Boolean
    get() = !isScheduled && (etaMinutes ?: Int.MAX_VALUE) <= 90

@Composable
private fun ArrivalRow(
    arrival: Arrival,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live rows count down in minutes when imminent and show the clock
    // time when far. Scheduled (offline) rows always show the scheduled
    // clock time. A schedule is not a live countdown. The calendar icon
    // marks the row.
    val imminent = arrival.isImminent
    val nowLabel = stringResource(R.string.now)
    val etaText = arrivalTimeText(arrival, nowLabel) ?: "–"
    // The rows are keyed by trip, so this state survives across polls.
    // When a 15 s poll changes an ETA, the row flashes briefly instead of
    // silently swapping, and unchanged rows stay still. Keyed on the
    // rendered text, not etaMinutes. Scheduled rows show a static clock
    // time, so they don't flash as the countdown decrements.
    var prevEta by remember { mutableStateOf(etaText) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(etaText) {
        val changed = prevEta != etaText
        prevEta = etaText
        if (changed) {
            flash = true
            delay(700)
            flash = false
        }
    }
    val flashColor by animateColorAsState(
        targetValue =
            if (flash) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                Color.Transparent
            },
        animationSpec = tween(250),
        label = "eta-flash",
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(ListRowShape)
            .clickable(onClick = onClick)
            .background(flashColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(arrival.lineShortName)
        Spacer(Modifier.width(12.dp))
        Text(
            arrival.destination.ifEmpty { arrival.lineName },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Same gap for the plain row. The icon branches end in their own 4.dp.
        Spacer(Modifier.width(8.dp))
        // Scheduled (offline) rows. The same calendar icon as the top-bar
        // timetable action, anchored to the time rather than a chip
        // floating mid-row. Marks the time as scheduled from the
        // timetable, not live telemetry.
        if (arrival.isScheduled) {
            Icon(
                Icons.Filled.DateRange,
                contentDescription = stringResource(R.string.scheduled_arrival),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else if (arrival.showsNoLocationIcon()) {
            // Live arrival whose bus has no GPS fix (CityBus "0"/"0",
            // OASA/OSETh may miss the vehicle in the live feed). The ETA is
            // real, only the position is missing. Same anchor slot as the
            // scheduled calendar icon, and scheduled rows always have
            // vehicle == null, so the two never appear together.
            Icon(
                ImageVector.vectorResource(R.drawable.ic_location_off),
                contentDescription = stringResource(R.string.no_bus_location),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            etaText,
            style = MaterialTheme.typography.titleMedium,
            color = if (imminent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Countdown/schedule badge for the map card. A live countdown gets the
 * soft tonal pill (secondaryContainer) so it doesn't compete with the
 * blue line badge. Far and scheduled times get the neutral gray, the
 * same quiet treatment the list row gives those ETAs.
 *
 * Min-width keeps "1′" and "30′" the same pill width (same trick as
 * [LineBadge]).
 */
@Composable
private fun EtaBadge(
    text: String,
    imminent: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color =
            if (imminent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier.widthIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (imminent) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private enum class ArrivalsScreenState { LOADING, ERROR, CONTENT }

/**
 * Ring next to the last-updated label. Sweeps over the poll interval
 * and resets when data lands (lastUpdated changes on every emission,
 * including the degraded schedule rows). Past POLL_INTERVAL_MS plus
 * POLL_GRACE_MS it goes indeterminate: from a screen-off wake the app
 * retries on its own schedule and a failed fetch can take seconds, so
 * a moving arc says "working on it" where a frozen full circle read as
 * stuck.
 *
 * Progress is wall-clock time since the last emission, not an animation
 * clock. An animation freezes with the process and jumps to its end on
 * wake, leaving the ring full and static until the next emission.
 */
@Composable
private fun CountdownRing(lastUpdated: String?) {
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lastEmitAt = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lastUpdated) {
        lastEmitAt.longValue = SystemClock.elapsedRealtime()
    }
    // 4 Hz ticker, gated on STARTED so it writes nothing while the app
    // is backgrounded. The loop writes before it delays. The first tick
    // on return is immediate, so the wake frame is never stale.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
    }
    val sinceEmitMs = nowMs - lastEmitAt.longValue
    val progress = (sinceEmitMs / POLL_INTERVAL_MS.toFloat()).coerceIn(0f, 1f)
    val stale = sinceEmitMs > POLL_INTERVAL_MS + POLL_GRACE_MS
    if (stale) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private const val POLL_GRACE_MS = 5_000L // slow-fetch allowance, see CountdownRing

// Tap-to-route layers: the selected bus's direction drawn under the stop
// pin and the bus markers (added first, so everything else renders on top).
private const val ARRIVAL_ROUTE_SOURCE = "arrival-route"
private const val ARRIVAL_ROUTE_LAYER = "arrival-route-line"

/** Green route polyline. Same green family as the line map's origin pins
 *  (ROUTE_START_ARGB), brightened one step: the route-start green alone is
 *  too dark for a 4 dp stroke on the dark Fiord basemap. */
private const val ARRIVAL_ROUTE_COLOR = "#43A047"

/**
 * Arrivals map layers. Re-run after every setStyle. A style swap wipes
 * sources and images, so the setup must be re-runnable (theme flips).
 */
private fun addArrivalsLayers(style: Style) {
    style.addSource(
        GeoJsonSource(ARRIVAL_ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyList())),
    )
    style.addLayer(
        LineLayer(ARRIVAL_ROUTE_LAYER, ARRIVAL_ROUTE_SOURCE)
            .withProperties(
                PropertyFactory.lineColor(ARRIVAL_ROUTE_COLOR),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap("round"),
            ),
    )
    style.addSource(GeoJsonSource("arrivals", FeatureCollection.fromFeatures(emptyList())))
    style.addImage("bus", vehicleBitmap())
    style.addImage("stop-pin", stopPinBitmap())
    style.addLayer(
        // Teardrop pin, tip anchored on the stop.
        SymbolLayer("arrival-stop", "arrivals")
            .withFilter(Expression.eq(Expression.get("kind"), "stop"))
            .withProperties(
                PropertyFactory.iconImage("stop-pin"),
                PropertyFactory.iconAnchor("bottom"),
                PropertyFactory.iconAllowOverlap(true),
            ),
    )
    // Soft halo + crisp white ring around the selected bus's
    // arrow (drawn beneath the enlarged icon). The translucent
    // fill must use the rgba() expression. MapLibre's parser
    // rejects 8-digit hex and would fall back to opaque black.
    style.addLayer(
        CircleLayer("arrival-selected", "arrivals")
            .withFilter(
                Expression.all(
                    Expression.eq(Expression.get("kind"), "vehicle"),
                    Expression.eq(Expression.get("selected"), true),
                ),
            ).withProperties(
                PropertyFactory.circleColor(Expression.rgba(245, 124, 0, 0.35f)),
                PropertyFactory.circleRadius(32f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(4f),
            ),
    )
    style.addLayer(
        SymbolLayer("arrival-vehicles", "arrivals")
            .withFilter(Expression.eq(Expression.get("kind"), "vehicle"))
            .withProperties(
                PropertyFactory.iconImage("bus"),
                // The selected bus renders bigger, alongside its
                // highlight halo (data-driven via "selected").
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
    // The base style's bus-stop/transit icons would clash with
    // the stop pin and our bus markers. (Fiord has no POI layers,
    // so this is a no-op there.)
    style.hideTransitPois()
}

/**
 * Compact map on the arrivals board: the stop pin plus the live
 * positions of the incoming buses, refreshed with the same 15 s poll.
 */
@Composable
private fun ArrivalsMapCard(
    stop: Stop,
    arrivals: List<Arrival>,
    selectedArrival: Arrival?,
    focusTick: Int,
    routeGeometry: List<GeoPoint>,
    onArrivalClick: (Arrival?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mapRef by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null)
    }
    var styleReady by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var centerTick by remember { mutableIntStateOf(0) }
    // Initial camera keyed on styleReady, so a theme flip's
    // style reload does not yank the view back to the stop.
    var centered by remember { mutableStateOf(false) }
    val currentArrivals by rememberUpdatedState(arrivals)
    // Captured in the composition (LocalDensity is @Composable) for use in
    // the camera effects below.
    val density = LocalDensity.current

    // The card container: rounded surface with the live badge overlaying
    // the map. Zoom helps pick a specific bus among the ones clustering
    // at the stop (pinch is cramped on a 220 dp card).
    MapCardSurface(
        modifier = modifier.fillMaxWidth().height(220.dp),
        layers = ::addArrivalsLayers,
        onMapReady = { mapRef = it },
        onStyleReady = { styleReady = it },
        onCenterClick = { centerTick++ },
    ) {
        // Tap a bus to identify the vehicle. The card slides up
        // from the map's bottom edge instead of popping in. `shownArrival`
        // keeps the last selection alive through the exit animation (the
        // selection itself is already null by then).
        var shownArrival by remember { mutableStateOf<Arrival?>(null) }
        LaunchedEffect(selectedArrival) {
            if (selectedArrival != null) shownArrival = selectedArrival
        }
        AnimatedVisibility(
            visible = selectedArrival != null,
            enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            shownArrival?.let { arrival ->
                MapInfoCard(
                    onClose = { onArrivalClick(null) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                ) {
                    // One row: badge, destination, ETA. MapInfoCard's
                    // header-less layout puts the ✕ after the content, next
                    // to the ETA. The bus number is left out. The tapped row
                    // is on screen, and the vehicle id adds nothing the
                    // list doesn't already say.
                    val nowLabel = stringResource(R.string.now)
                    val etaText = arrivalTimeText(arrival, nowLabel)
                    val showLineRow =
                        arrival.lineShortName.isNotEmpty() ||
                            arrival.destination.isNotEmpty() ||
                            etaText != null
                    if (showLineRow) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (arrival.lineShortName.isNotEmpty()) {
                                LineBadge(arrival.lineShortName)
                                Spacer(Modifier.width(8.dp))
                            }
                            // Scroll long variant names instead of truncating.
                            Text(
                                arrival.destination.ifEmpty { arrival.lineName },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            initialDelayMillis = 600,
                                        ),
                            )
                            // The status glyph in the same anchor slot as
                            // the list rows. A schedule estimate gets the
                            // calendar, a live trip without a GPS fix
                            // (CityBus "0"/"0", OASA/OSETh may miss the
                            // vehicle) gets the location-off. The countdown
                            // is real regardless, only the position is
                            // missing.
                            if (arrival.isScheduled) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.DateRange,
                                    contentDescription =
                                        stringResource(R.string.scheduled_arrival),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            } else if (arrival.showsNoLocationIcon()) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    ImageVector.vectorResource(R.drawable.ic_location_off),
                                    contentDescription =
                                        stringResource(R.string.no_bus_location),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            if (etaText != null) {
                                Spacer(Modifier.width(8.dp))
                                EtaBadge(text = etaText, imminent = arrival.isImminent)
                            }
                        }
                    }
                }
            }
        }
    }
    // Tap a bus to show which vehicle it is. `currentArrivals` keeps the
    // listener reading the latest poll (the arrivals list itself is not
    // an effect key, so the closure must not capture it directly).
    DisposableEffect(mapRef, styleReady) {
        val map = mapRef ?: return@DisposableEffect onDispose {}
        if (!styleReady) return@DisposableEffect onDispose {}
        val clickListener =
            MapLibreMap.OnMapClickListener { latLng ->
                val screenPoint = map.projection.toScreenLocation(latLng)
                // Box query for the same tap tolerance as the line map.
                val rect =
                    RectF(
                        screenPoint.x - 30f,
                        screenPoint.y - 30f,
                        screenPoint.x + 30f,
                        screenPoint.y + 30f,
                    )
                val features = map.queryRenderedFeatures(rect, "arrival-vehicles")
                // Buses can sit close enough that several arrows fall inside the
                // tolerance box. Pick the one nearest the tap instead of an
                // arbitrary first match.
                val id =
                    features
                        .mapNotNull { it.getStringProperty("vehicle_id") }
                        .minByOrNull { id ->
                            val v =
                                currentArrivals
                                    .firstOrNull { it.vehicle?.vehicleId == id }
                                    ?.vehicle
                            if (v == null) {
                                Float.MAX_VALUE
                            } else {
                                val p = map.projection.toScreenLocation(LatLng(v.lat, v.lon))
                                val dx = p.x - screenPoint.x
                                val dy = p.y - screenPoint.y
                                dx * dx + dy * dy
                            }
                        }
                if (id != null) {
                    onArrivalClick(currentArrivals.firstOrNull { it.vehicle?.vehicleId == id })
                    true
                } else {
                    false
                }
            }
        map.addOnMapClickListener(clickListener)
        onDispose { map.removeOnMapClickListener(clickListener) }
    }

    LaunchedEffect(mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || centered) return@LaunchedEffect
        centered = true
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition
                    .Builder()
                    .target(LatLng(stop.lat, stop.lon))
                    .zoom(14.0)
                    .build(),
            ),
        )
    }

    // Center button: animate back to the initial view (stop at zoom 14).
    LaunchedEffect(centerTick, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady || centerTick == 0) return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition
                    .Builder()
                    .target(LatLng(stop.lat, stop.lon))
                    .zoom(14.0)
                    .build(),
            ),
        )
    }

    // Selection from the arrivals list (or a bus tap): fit the camera so
    // both the stop pin and the bus stay in view. A fly-to-bus that
    // centered the bus at zoom 16 would lose the stop (and the user's
    // bearings) when the bus was far away. Bottom padding keeps the bus
    // clear of the info card.
    //
    // Keyed on focusTick, not on selectedArrival. The selection re-resolves
    // on every poll (the card tracks its bus: ETA/position updates), so a
    // selection key would re-animate the camera every 15 s. focusTick is
    // the user action. A tap moves the camera, a poll update only moves
    // the marker under it.
    val cameraArrival by rememberUpdatedState(selectedArrival)
    LaunchedEffect(focusTick, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val vehicle = cameraArrival?.vehicle ?: return@LaunchedEffect
        val stopPos = LatLng(stop.lat, stop.lon)
        val busPos = LatLng(vehicle.lat, vehicle.lon)
        if (busPos.distanceTo(stopPos) < 80.0) {
            // Bus sitting on its stop: a bounds fit would zoom to the map's
            // maximum and spin the user around. Hold a moderate zoom with
            // both markers in the frame instead.
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition
                        .Builder()
                        .target(busPos)
                        .zoom(15.5)
                        .build(),
                ),
            )
        } else {
            val pad = with(density) { 48.dp.toPx().roundToInt() }
            // The card is one row (badge/destination/ETA + ✕). Only a bus
            // with a fix reaches this branch, so the compact card is always
            // the one on screen (the no-location note never adds a line
            // here).
            val padBottom = with(density) { 84.dp.toPx().roundToInt() }
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds
                        .Builder()
                        .include(stopPos)
                        .include(busPos)
                        .build(),
                    pad,
                    pad,
                    pad,
                    padBottom,
                ),
            )
        }
    }

    // Selected bus's route polyline. Empty clears the source. A deselected
    // bus must not leave its line on screen.
    LaunchedEffect(routeGeometry, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(ARRIVAL_ROUTE_SOURCE)
            if (routeGeometry.size >= 2) {
                source?.setGeoJson(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            routeGeometry.map { Point.fromLngLat(it.lon, it.lat) },
                        ),
                    ),
                )
            } else {
                source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            }
        }
    }

    // Keep the bus markers (and the selected-bus highlight) in sync with
    // the latest poll and the current selection.
    LaunchedEffect(arrivals, selectedArrival, mapRef, styleReady) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val features =
            buildList {
                add(
                    Feature.fromGeometry(
                        Point.fromLngLat(stop.lon, stop.lat),
                        JsonObject().apply { addProperty("kind", "stop") },
                    ),
                )
                arrivals.forEach { arrival ->
                    val v = arrival.vehicle ?: return@forEach
                    add(
                        Feature.fromGeometry(
                            Point.fromLngLat(v.lon, v.lat),
                            JsonObject().apply {
                                addProperty("kind", "vehicle")
                                addProperty("heading", v.heading ?: 0f)
                                addProperty("vehicle_id", v.vehicleId)
                                addProperty(
                                    "selected",
                                    selectedArrival?.vehicle?.vehicleId == v.vehicleId,
                                )
                            },
                        ),
                    )
                }
            }
        map.getStyle { style ->
            (style.getSourceAs<GeoJsonSource>("arrivals"))
                ?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }
}
