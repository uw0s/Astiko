package app.astiko.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.FavoritesStore
import app.astiko.data.TransitRepository
import app.astiko.data.model.City
import app.astiko.data.model.Stop
import app.astiko.util.LocationTracker
import app.astiko.util.hasLocationPermission
import app.astiko.util.runCatchingNotCancelled
import app.astiko.util.withDistanceFrom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Stop search. All the UI logic is a debounced query flow: type, pause
 * ~350 ms, search. The results are ranked locally over the provider's
 * full stop catalog (see [TransitRepository.searchStops]. Both OSETh
 * and CityBus filter client-side, since OSETh's name-search param is
 * ignored by the server). No polling and no listeners: it reads the last
 * known fix once per search when location is granted, so an idle instance
 * does no background work.
 *
 * Session-scoped: the instance lives in the root's per-city search
 * session (see TransitAppRoot), not in the entry store, so covering
 * the screen with arrivals does not kill it and the results come back
 * without re-searching. [reset] is the opposite: the search
 * entry was popped, so everything resets for the next open.
 */
class StopSearchViewModel(
    private val repository: TransitRepository,
    private val favoritesRepository: FavoritesStore,
    private val locationProvider: LocationTracker,
    private val hasLocationPermission: () -> Boolean,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Scroll of the results list, kept with the session. The composable
     *  is rebuilt on return (fresh LazyColumn), so the position has to
     *  live outside it or it snaps back to the top. */
    val listState = LazyListState()

    /** Favorites of this provider, for the cluster chooser's hearts
     *  (same source as the Στάσεις tab). */
    val favoriteStops: StateFlow<List<Stop>> =
        favoritesRepository.favoriteStops
            .filteredByProvider(repository.provider, viewModelScope) { it.provider }

    /** The chooser hearts toggle favorites, same rule as the nearby list:
     *  the heart lives in the sheet and on the arrivals screen, never in
     *  a result row. */
    fun toggleFavorite(stop: Stop) {
        viewModelScope.launch { favoritesRepository.toggle(stop) }
    }

    sealed interface SearchState {
        /** Query empty (or blank): show the prompt, no data requested. */
        data object Idle : SearchState

        data object Loading : SearchState

        data class Ready(
            val stops: List<Stop>,
        ) : SearchState

        data class Error(
            val message: String?,
        ) : SearchState
    }

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    // Retry is a counter, not a re-set of the query: a re-set of the same
    // string would be deduped by the flow and never re-search.
    private val retryTick = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val searchTrigger =
        combine(_query, retryTick) { q, _ -> q }.debounce(SEARCH_DEBOUNCE_MS)

    init {
        viewModelScope.launch {
            // collectLatest cancels the in-flight search on the next query
            // change (and on retry), so a fast retype never lands a stale
            // result after a newer one (runCatchingNotCancelled rethrows
            // the cancellation instead of reporting it as a failure).
            searchTrigger.collectLatest { q -> runSearch(q) }
        }
    }

    private suspend fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            _state.value = SearchState.Idle
            return
        }
        _state.value = SearchState.Loading
        runCatchingNotCancelled { repository.searchStops(q) }
            .onSuccess { _state.value = SearchState.Ready(measured(it)) }
            .onFailure { _state.value = SearchState.Error(it.message) }
    }

    /**
     * Results with their distance from the last known fix. The provider
     * catalog carries none, and same-name stops are only told apart by
     * distance. The screen never asks for the permission and never starts
     * tracking, so a missing permission or fix leaves the results as they
     * came.
     */
    private fun measured(stops: List<Stop>): List<Stop> {
        if (!hasLocationPermission()) return stops
        val fix = locationProvider.lastKnownOrNull() ?: return stops
        return stops.withDistanceFrom(fix.latitude, fix.longitude)
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    /** Reset for a fresh search. Called when the search entry is (re)opened,
     *  not at pop: the outgoing screen stays composed during the exit
     *  animation, and clearing at pop would visibly wipe the results while
     *  the screen is still sliding away. A popped session parks its results
     *  until the next open, then this discards them. */
    fun reset() {
        _query.value = ""
        _state.value = SearchState.Idle
        listState.requestScrollToItem(0)
    }

    fun retry() {
        retryTick.value += 1
    }

    companion object {
        /** Type-ahead pause before hitting the network (OSETH searches
         *  server-side. CityBus is local and pays the same pause, so both
         *  behave alike). */
        private const val SEARCH_DEBOUNCE_MS = 350L

        fun factory(city: City): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    StopSearchViewModel(
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
