package app.astiko.ui

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.FavoritesStore
import app.astiko.data.POLL_INTERVAL_MS
import app.astiko.data.TransitRepository
import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.Stop
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ArrivalsViewModel(
    private val repository: TransitRepository,
    private val favoritesRepository: FavoritesStore,
    private val stop: Stop,
    /** Injectable monotonic clock; tests drive the foreground age gate. */
    private val nowRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {
    /** Favorites of this provider, for the top-bar heart. Seeded from the
     *  repository's warmed value (the DataStore read starts at app start),
     *  so the heart is correct from the first frame. */
    val favoriteStops: StateFlow<List<Stop>> =
        favoritesRepository.favoriteStops
            .filteredByProvider(stop.provider, viewModelScope) { it.provider }

    val supportsTimetable: Boolean = repository.supportsStopTimetable

    private val _routes = MutableStateFlow<List<Line>>(emptyList())
    val routes: StateFlow<List<Line>> = _routes.asStateFlow()

    private val _arrivals = MutableStateFlow<List<Arrival>>(emptyList())
    val arrivals: StateFlow<List<Arrival>> = _arrivals.asStateFlow()

    private val _selectedArrival = MutableStateFlow<Arrival?>(null)

    /** The bus whose map card and route polyline are shown. Lives here, not
     *  in the screen, because the poll owns the dismiss rule: an emission
     *  that no longer reports the selected bus (it crossed the stop,
     *  direction changed, service ended) clears the selection on the spot,
     *  so the card animates out with the marker instead of showing a stale
     *  "now" forever. */
    val selectedArrival: StateFlow<Arrival?> = _selectedArrival.asStateFlow()

    private val _routeGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())

    /** Polyline of the selected bus's direction (empty = no selection, or no
     *  route known yet). Drawn on the arrivals map card under the bus
     *  markers. Fetched lazily on tap, one cached call per direction. */
    val routeGeometry: StateFlow<List<GeoPoint>> = _routeGeometry.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastUpdated = MutableStateFlow<String?>(null)
    val lastUpdated: StateFlow<String?> = _lastUpdated.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    /** In-flight geometry fetch; cancelled on a new selection or a clear. */
    private var geometryJob: Job? = null

    /** Per-screen memory of fetched geometries, keyed like the offline
     *  cache's variantKey (route id + OSETh shape). Re-tapping a bus, or
     *  tapping another bus of the same direction, reuses the polyline
     *  without even re-reading the 30-day disk cache. */
    private val geometryCache = mutableMapOf<String, List<GeoPoint>>()

    /**
     * The bus selected from the arrivals list or by tapping the map.
     * Shows the bus card's route as a polyline on the arrivals map.
     * One lazy geometry call per direction, served from the 30-day
     * offline cache after the first visit to that direction's screen
     * (or the city prefetch). Never fetched while no bus is selected.
     * A null selection (card closed) clears the route. Scheduled rows
     * (offline estimates) have no route id and never draw a line.
     */
    fun selectBus(arrival: Arrival?) {
        _selectedArrival.value = arrival
        geometryJob?.cancel()
        geometryJob = null
        val variant = arrival?.let { repository.variantFor(it) }
        if (variant == null) {
            // No selection, unknown route, or a scheduled estimate wipes
            // any previously drawn geometry. A cancelled selection must
            // not leave the old route on screen.
            _routeGeometry.value = emptyList()
            return
        }
        val key =
            if (variant.shapeId.isNullOrBlank()) variant.id else "${variant.id}-${variant.shapeId}"
        geometryCache[key]?.let {
            _routeGeometry.value = it
            return
        }
        // Clear first. While the new fetch is in flight the map must not
        // keep the previous bus's route. A stale polyline under a new
        // selection reads as a wrong route. The bus card is the immediate
        // feedback, the line arrives with it.
        _routeGeometry.value = emptyList()
        geometryJob =
            viewModelScope.launch {
                runCatchingNotCancelled { repository.getRouteGeometry(variant) }
                    .onSuccess {
                        geometryCache[key] = it
                        _routeGeometry.value = it
                    }.onFailure {
                        // No route, but the selection stays: the card and the
                        // camera move are the feedback, a missing line is not
                        // an error state on an unofficial API.
                        _routeGeometry.value = emptyList()
                    }
            }
    }

    /** The bus left the poll (crossed the stop, dropped off service), or
     *  the selection was cleared: drop the card and its route in one go. */
    private fun clearSelection() {
        _selectedArrival.value = null
        geometryJob?.cancel()
        geometryJob = null
        _routeGeometry.value = emptyList()
    }

    fun toggleFavorite(stop: Stop) {
        viewModelScope.launch { favoritesRepository.toggle(stop) }
    }

    private fun load() {
        _loading.value = true
        _error.value = null
        // Kill the previous poll before the retry's routes fetch. An orphaned
        // poll tick can fail mid-retry and flash its error over the retry's
        // LOADING state. The poll only restarts after the routes succeed, so
        // the window is the whole routes fetch.
        pollJob?.cancel()
        viewModelScope.launch {
            runCatchingNotCancelled { repository.getStopRoutes(stop.id) }
                .onSuccess { lines ->
                    // The full list feeds the arrivals enrichment. The display
                    // list is deduped per line, one row per line not per
                    // direction (see TransitRepository.stopLinesForDisplay).
                    _routes.value = repository.stopLinesForDisplay(lines)
                    startPolling(lines)
                    // The user opened this stop's arrivals, so warm its
                    // timetable for today. A later offline visit then degrades
                    // to the schedule instead of a dead screen.
                    warmStopTimetable()
                }.onFailure { e ->
                    _error.value = e.message
                    _loading.value = false
                }
        }
    }

    private var pollJob: Job? = null

    /** Raw lines of the running poll; null until the routes fetch
     *  succeeded. Lets [refreshNow] restart the poll without re-fetching
     *  the routes. */
    private var pollLines: List<Line>? = null

    /** Monotonic time of the last successful emission; 0 = none yet. The
     *  [refreshNow] gate: quick app switches must not double-fetch. */
    private var lastEmitRealtime = 0L

    /**
     * Silent one-time warm-up of today's stop timetable. The arrivals
     * screen is the app's highest-traffic screen, so it warms exactly the
     * weekday the offline degrade-to-schedule fallback reads. The full
     * week comes from the timetable screen's own warm-up. Failures are
     * ignored. A failed routes fetch skips it (the API is unreachable
     * anyway), and re-visits are harmless: a fresh day serves from cache,
     * no network.
     */
    private fun warmStopTimetable() {
        if (!supportsTimetable) return
        viewModelScope.launch {
            runCatchingNotCancelled {
                repository.getStopTimetable(
                    stop.id,
                    LocalDate.now().dayOfWeek,
                )
            }
        }
    }

    private fun startPolling(lines: List<Line>) {
        pollLines = lines
        pollJob?.cancel()
        pollJob =
            viewModelScope.launch {
                while (true) {
                    try {
                        repository
                            .observeArrivals(stop.id, lines)
                            .collect { list ->
                                _arrivals.value = list
                                // A selected bus the poll no longer reports
                                // (it crossed the stop, changed direction,
                                // left service) must not leave its card
                                // pinned on the map with a stale ETA. Prune
                                // on the very emission that dropped it, so
                                // the card animates out with the marker.
                                _selectedArrival.value?.let { selected ->
                                    // Re-resolve the selection against the
                                    // fresh emission: the card must track
                                    // its bus (ETA/position updates), not
                                    // freeze the tapped instance at "2′"
                                    // while the list counts down past it.
                                    val current =
                                        list.firstOrNull { it.isSameVehicle(selected) }
                                    if (current != null) {
                                        _selectedArrival.value = current
                                    } else {
                                        // The poll no longer reports the
                                        // selected bus. Prune on the very
                                        // emission that dropped it, so the
                                        // card animates out with the
                                        // marker and the polyline follows.
                                        // No stale ETA pinned on the map.
                                        clearSelection()
                                    }
                                }
                                lastEmitRealtime = nowRealtime()
                                _lastUpdated.value = LocalTime.now().format(TIME_FORMAT)
                                _error.value = null
                                _loading.value = false
                            }
                        // The real adapters poll forever. A flow that ends
                        // without an error has nothing more to emit.
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Restart the loop after the poll interval, so a single
                        // transient network blip does not leave the screen frozen
                        // on the error state until a manual retry. The next tick
                        // recovers by itself.
                        // Only surface the error while the screen has never shown
                        // live data. Backgrounding the app or locking the screen
                        // drops the network (Doze, Wi-Fi sleep) and every poll
                        // fails with "Unable to resolve host". Surfacing those
                        // would swap the last known arrivals (and the map) for a
                        // full-screen error on return. A successful emission,
                        // even an empty one ("no buses at night"), means the
                        // screen is live, so keep the content. The frozen
                        // last-updated timestamp is the staleness hint.
                        if (_lastUpdated.value == null) {
                            _error.value = e.message
                        }
                        Log.w(TAG, "arrivals poll failed", e)
                        _loading.value = false
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
    }

    /**
     * Foreground hook (ON_START of the host lifecycle). Restarts the
     * poll instead of waiting for its next tick. After a screen-off
     * wake the in-flight fetch died on a stale socket. Skipped when no
     * data was ever shown (the initial load owns the fetch) and when an
     * emission landed < 5 s ago (quick app switches must not
     * double-fetch).
     */
    fun refreshNow() {
        val lines = pollLines ?: return
        if (_lastUpdated.value == null) return
        if (nowRealtime() - lastEmitRealtime < FOREGROUND_REFRESH_MIN_AGE_MS) return
        pollJob?.cancel()
        startPolling(lines)
    }

    companion object {
        // Data younger than this skips the foreground restart.
        private const val FOREGROUND_REFRESH_MIN_AGE_MS = 5_000L
        private const val TAG = "ArrivalsViewModel"
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun factory(stop: Stop): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    ArrivalsViewModel(
                        repository = app.container.repository(stop.provider),
                        favoritesRepository = app.container.favoritesRepository,
                        stop = stop,
                    )
                }
            }
    }
}
