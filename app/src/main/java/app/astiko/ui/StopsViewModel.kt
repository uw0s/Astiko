package app.astiko.ui

import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.FavoritesStore
import app.astiko.data.TransitRepository
import app.astiko.data.model.City
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.util.LocationTracker
import app.astiko.util.hasLocationPermission
import app.astiko.util.mapBounded
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StopsViewModel(
    private val city: City,
    private val repository: TransitRepository,
    private val favoritesRepository: FavoritesStore,
    private val locationProvider: LocationTracker,
    private val hasLocationPermission: () -> Boolean,
    /** Time source; tests inject a fake clock. */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val provider: Provider get() = city.provider

    /** Favorites filtered to the current city's provider. Seeded from the
     *  repository's warmed value (the DataStore read starts at app start),
     *  so the section renders correctly on the first frame. */
    val favoriteStops: StateFlow<List<Stop>> =
        favoritesRepository.favoriteStops.filteredByProvider(provider, viewModelScope) { it.provider }

    /**
     * Favorites for the list. Like [favoriteStops], but with serving lines
     * fetched for rows that lack them (favorites saved from the line flow
     * carry no badges) and the stale nearby-distance stripped, since it
     * was measured at save time and means nothing later. The result is
     * persisted back to the store, so each favorite fetches only once and
     * later app opens are instant. Rows are emitted only when ready, so
     * there is no badge-less flash.
     */
    private val _favoriteRows = MutableStateFlow<List<Stop>>(emptyList())
    val favoriteRows: StateFlow<List<Stop>> = _favoriteRows.asStateFlow()

    init {
        // Warm the stop catalog in the background (used by the stop
        // search). The OSETH catalog is 4 slow server pages, and the user
        // must never watch that at the search screen: the city opening is
        // the right moment (the search icon is a tap away). Swallowed by
        // the decorator. Offline just leaves the entry unbuilt and the
        // first search reports it. The VM is per-city, so a city switch
        // warms the new city's catalog, and the old city's job dies with
        // its store.
        viewModelScope.launch { repository.warmStopCatalog() }
        viewModelScope.launch {
            favoriteStops.collect { favorites ->
                if (favorites.isEmpty()) {
                    _favoriteRows.value = emptyList()
                    return@collect
                }
                val enriched =
                    // Bounded, so a dozen favorites do not become a dozen
                    // simultaneous calls at the unofficial APIs.
                    favorites.mapBounded(FAVORITES_CONCURRENCY) { enrich(it) }
                _favoriteRows.value = enriched
            }
        }
    }

    private suspend fun enrich(stop: Stop): Stop {
        if (stop.servingLines.isNotEmpty()) return stop.copy(distanceKm = null)
        val result = runCatchingNotCancelled { repository.getStopRoutes(stop.id) }
        val enriched =
            stop.copy(
                servingLines = result.getOrDefault(emptyList()).map { it.shortName }.distinct(),
                distanceKm = null,
            )
        // Persist only a successful, non-empty enrichment. A failed fetch
        // (offline) must not write a badge-less copy back. The write re-emits
        // the favorites flow, which re-runs this enrichment, and with the copy
        // still badge-less the cycle repeats: endless failed fetches and disk
        // writes while the screen is open. A legitimately line-less stop has
        // the same shape, so empties are never persisted either. It just
        // re-fetches on the next favorites change, which is rare.
        result.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            favoritesRepository.replace(enriched)
        }
        return enriched
    }

    sealed interface NearbyState {
        data object Loading : NearbyState

        data object NeedsPermission : NearbyState

        data class Ready(
            val stops: List<Stop>,
            val locationUnavailable: Boolean,
        ) : NearbyState

        data class Error(
            val message: String?,
        ) : NearbyState
    }

    private val _nearby = MutableStateFlow<NearbyState>(NearbyState.NeedsPermission)
    val nearby: StateFlow<NearbyState> = _nearby.asStateFlow()

    private var refreshJob: Job? = null
    private var lastLocation: Location? = null
    private var trackingHandle: AutoCloseable? = null
    private var pollJob: Job? = null

    /** When the last successful fetch landed. Re-entry skips refetch for
     *  [NEARBY_TTL_MS] after this. Movement still refreshes via
     *  [onLocationFix]. */
    private var lastNearbyFetchAt = 0L

    /** When the last AUTO refresh happened (GPS-driven). A jittery GPS
     *  can bounce across the 200 m threshold repeatedly, and each crossing
     *  refetches the whole nearby list (plus OASA's per-stop badge calls)
     *  and flashes the Loading state. Manual refreshes are never
     *  throttled. */
    private var lastAutoRefreshAt = 0L

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            startTrackingLocation()
            startLocationPolling()
            refreshNearby()
        } else {
            _nearby.value = NearbyState.NeedsPermission
        }
    }

    fun refreshNearby(force: Boolean = false) {
        // Without location permission the fallback would silently show
        // city-center stops. Show the permission hint instead.
        if (!hasLocationPermission()) {
            _nearby.value = NearbyState.NeedsPermission
            return
        }
        // Re-entry: a fresh list is left as is, a stale one refreshes
        // silently. Only an explicit refresh shows the spinner or an
        // error.
        if (!force &&
            _nearby.value is NearbyState.Ready &&
            nowMs() - lastNearbyFetchAt < NEARBY_TTL_MS
        ) {
            return
        }
        fetchNearby(silent = !force && _nearby.value is NearbyState.Ready)
    }

    private fun fetchNearby(silent: Boolean) {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                if (!silent) _nearby.value = NearbyState.Loading
                val location = locationProvider.currentLocationOrNull() ?: lastLocation
                lastLocation = location
                if (location == null) {
                    if (!silent) {
                        _nearby.value =
                            NearbyState.Ready(emptyList(), locationUnavailable = true)
                    }
                    return@launch
                }
                // runCatchingNotCancelled. A cancelled refresh (user re-tapped,
                // GPS moved) must not land its error after the new one started.
                runCatchingNotCancelled {
                    repository.getStopsNear(
                        location.latitude,
                        location.longitude,
                    )
                }.onSuccess { stops ->
                    _nearby.value = NearbyState.Ready(stops, locationUnavailable = false)
                    lastNearbyFetchAt = nowMs()
                }.onFailure { e ->
                    // A failed silent refresh keeps the previous rows. An
                    // explicit one surfaces the error.
                    if (!silent) _nearby.value = NearbyState.Error(e.message)
                }
            }
    }

    fun toggleFavorite(stop: Stop) {
        viewModelScope.launch { favoritesRepository.toggle(stop) }
    }

    /**
     * Listens for GPS fixes and refreshes "nearby" automatically when the
     * position moves by more than 200 m (emulator location change, walking…).
     */
    private fun startTrackingLocation() {
        if (trackingHandle != null) return
        trackingHandle = locationProvider.startTracking { location -> onLocationFix(location) }
    }

    /**
     * A cheap lastKnown poll covers fixes that update the cache without
     * reaching listeners. On real devices the push listener handles
     * movement and this is a harmless no-op.
     */
    private fun startLocationPolling() {
        if (pollJob != null) return
        pollJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(LOCATION_POLL_MS)
                    locationProvider.lastKnownOrNull()?.let { onLocationFix(it) }
                }
            }
    }

    private fun onLocationFix(location: Location) {
        val now = nowMs()
        val last = lastLocation
        val moved = last == null || last.distanceTo(location) > MOVE_THRESHOLD_M
        if (moved && now - lastAutoRefreshAt >= AUTO_REFRESH_MIN_INTERVAL_MS) {
            lastAutoRefreshAt = now
            lastLocation = location
            refreshFrom(location)
        } else if (moved) {
            // Within the cooldown. Remember the position so the next fix
            // compares against it (no repeated threshold crossings). The
            // 15 s poll picks up the move afterwards.
            lastLocation = location
        }
    }

    private fun refreshFrom(location: Location) {
        // Movement refreshes silently too, old rows stay while the new
        // area's stops load.
        fetchNearby(silent = _nearby.value is NearbyState.Ready)
    }

    override fun onCleared() {
        trackingHandle?.close()
        trackingHandle = null
        pollJob?.cancel()
    }

    companion object {
        private const val MOVE_THRESHOLD_M = 200f
        private const val LOCATION_POLL_MS = 15_000L
        private const val AUTO_REFRESH_MIN_INTERVAL_MS = 15_000L

        /** Re-entry within this window keeps the list as-is. */
        private const val NEARBY_TTL_MS = 60_000L
        private const val FAVORITES_CONCURRENCY = 6

        fun factory(city: City): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    StopsViewModel(
                        city = city,
                        repository = app.container.repository(city.provider),
                        favoritesRepository = app.container.favoritesRepository,
                        locationProvider = app.container.locationProvider,
                        hasLocationPermission = {
                            hasLocationPermission(app)
                        },
                    )
                }
            }
    }
}
