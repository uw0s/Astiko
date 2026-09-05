package app.astiko.ui

import android.util.Log
import androidx.compose.material.icons.automirrored.filled.List
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.FavoritesStore
import app.astiko.data.POLL_INTERVAL_MS
import app.astiko.data.TransitRepository
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Stop
import app.astiko.data.model.VehiclePosition
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class VariantStopsViewModel(
    private val repository: TransitRepository,
    private val favoritesRepository: FavoritesStore,
    variant: LineVariant,
) : ViewModel() {
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()

    private val _geometry = MutableStateFlow<List<GeoPoint>>(emptyList())
    val geometry: StateFlow<List<GeoPoint>> = _geometry.asStateFlow()

    private val _vehicles = MutableStateFlow<List<VehiclePosition>>(emptyList())
    val vehicles: StateFlow<List<VehiclePosition>> = _vehicles.asStateFlow()

    private var vehiclesJob: Job? = null
    private var loadJob: Job? = null

    /** The direction currently shown. The screen starts on the entry's
     *  variant, and the direction switcher replaces it in place. One
     *  back-stack entry, so popping returns to the Lines tab, not to the
     *  other direction. */
    private val _activeVariant = MutableStateFlow(variant)
    val activeVariant: StateFlow<LineVariant> = _activeVariant.asStateFlow()

    /** The parent line, reconstructed from the variant. Every adapter
     *  resolves getLineVariants against cached catalogs (keyed on shortName
     *  or id), so fetching the sibling directions costs no network. */
    val parentLine: Line =
        Line(
            provider = variant.provider,
            id = variant.lineId,
            shortName = variant.lineShortName,
            longName = variant.label,
        )

    /** Sibling directions of the line, for the direction switcher. Empty
     *  until loaded. A failed load keeps it empty and the switcher hidden,
     *  while the current direction keeps working. Not deduplicated. OSETh
     *  repeats routeIds for day variants (weekday/weekend shapes have
     *  different stop lists, 01A's weekend skips ΣΚΡΑ/ΠΑΡΚΟ ΣΜΥΡΝΗΣ) and
     *  even distinct services under one routeId (55K ΜΕΤΑΒΑΣΗ/ΕΠΙΣΤΡΟΦΗ,
     *  91T express/detour), so every headsign the initial sheet offers
     *  stays reachable from the switcher. */
    private val _variants = MutableStateFlow<List<LineVariant>>(emptyList())
    val variants: StateFlow<List<LineVariant>> = _variants.asStateFlow()

    /** Favorites of this provider, for the row hearts. Seeded from the
     *  repository's warmed value (the DataStore read starts at app start),
     *  so hearts are correct from the first frame. */
    val favoriteStops: StateFlow<List<Stop>> =
        favoritesRepository.favoriteStops
            .filteredByProvider(variant.provider, viewModelScope) { it.provider }

    val favoriteLines: StateFlow<List<LineVariant>> =
        favoritesRepository.favoriteLines
            .filteredByProvider(variant.provider, viewModelScope) { it.provider }

    val supportsTimetable: Boolean = repository.supportsLineTimetable

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load()
        loadVariants()
    }

    /** Switch the shown direction in place: reloads stops, geometry and
     *  the vehicle polling for the new direction. */
    fun switchVariant(variant: LineVariant) {
        if (variant.isSameRoute(_activeVariant.value)) return
        _activeVariant.value = variant
        load()
    }

    private fun loadVariants() {
        viewModelScope.launch {
            runCatchingNotCancelled { repository.getLineVariants(parentLine) }
                .onSuccess { list -> _variants.value = list }
            // Failure. Keep the switcher hidden, the direction in view still
            // works without it.
        }
    }

    fun toggleFavorite(stop: Stop) {
        viewModelScope.launch { favoritesRepository.toggle(stop) }
    }

    fun toggleFavoriteLine(variant: LineVariant) {
        viewModelScope.launch { favoritesRepository.toggle(variant) }
    }

    fun retry() = load()

    private fun load() {
        // A re-entry (retry, direction switch) supersedes the in-flight
        // load. Cancelling it keeps a slow first fetch from overwriting the
        // direction the user switched to.
        loadJob?.cancel()
        vehiclesJob?.cancel()
        _loading.value = true
        _error.value = null
        // The switched-away direction's map must not flash while the new
        // fetch is in flight.
        _geometry.value = emptyList()
        _vehicles.value = emptyList()
        loadJob =
            viewModelScope.launch {
                val active = _activeVariant.value
                var stopsLoaded = false
                coroutineScope {
                    launch {
                        runCatchingNotCancelled { repository.getVariantStops(active) }
                            .onSuccess {
                                stopsLoaded = true
                                _stops.value = it
                                _loading.value = false
                                // The user opened this line's stop list. Warm
                                // today's timetable for offline while they look
                                // around.
                                warmLineTimetables(active)
                            }.onFailure { e ->
                                _error.value = e.message
                                _loading.value = false
                            }
                    }
                    launch {
                        runCatchingNotCancelled { repository.getRouteGeometry(active) }
                            .onSuccess { _geometry.value = it }
                    }
                }
                // Poll only when the screen has content. A failed load shows the
                // error state, and a 15 s vehicle loop behind it would be wasted
                // work, since the map is not there to update anyway. The flag is
                // safe, both launches run on the main dispatcher.
                if (stopsLoaded) startVehiclesPolling(active)
            }
    }

    private fun startVehiclesPolling(variant: LineVariant) {
        vehiclesJob =
            viewModelScope.launch {
                while (true) {
                    try {
                        repository.observeVehicles(variant).collect { _vehicles.value = it }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep the last known positions and retry on the next
                        // tick, so the map stays alive through a failed poll.
                        Log.w(TAG, "vehicles poll failed", e)
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
    }

    /**
     * Silent one-time preload of today's line timetable. The stop list is
     * medium-traffic, so only one day is warmed, the weekday the timetable
     * screen defaults to. The full week comes from the timetable screen's
     * own preload. Failures are ignored (this must never affect the
     * screen), a failed stops load skips it (the API is unreachable
     * anyway), and re-visits are harmless (a fresh day serves from cache,
     * no network). Leaving the screen cancels the fetch. Warms the
     * direction in view.
     */
    private fun warmLineTimetables(variant: LineVariant) {
        if (!supportsTimetable) return
        viewModelScope.launch {
            runCatchingNotCancelled {
                repository.getLineTimetable(
                    variant,
                    LocalDate.now().dayOfWeek,
                )
            }
        }
    }

    override fun onCleared() {
        vehiclesJob?.cancel()
        loadJob?.cancel()
    }

    companion object {
        private const val TAG = "VariantStopsViewModel"

        fun factory(variant: LineVariant): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    VariantStopsViewModel(
                        repository = app.container.repository(variant.provider),
                        favoritesRepository = app.container.favoritesRepository,
                        variant = variant,
                    )
                }
            }
    }
}
