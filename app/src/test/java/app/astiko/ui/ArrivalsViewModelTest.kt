package app.astiko.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import app.astiko.data.FavoritesStore
import app.astiko.data.TransitRepository
import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.model.VehiclePosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Minimal TransitRepository for ViewModel tests, with only the arrivals bits wired. */
class FakeTransitRepository : TransitRepository {
    var stopRoutes: List<Line> = emptyList()
    var stopRoutesError: Throwable? = null

    /** When set, [getStopRoutes] suspends on it, letting a test hold the
     *  initial load in flight and then cancel it (screen popped). */
    var stopRoutesGate: CompletableDeferred<Unit>? = null
    var arrivals: List<Arrival> = emptyList()
    var arrivalsError: Throwable? = null

    /** When true, [observeArrivals] polls forever like the real adapters
     *  instead of emitting once. Needed to test poll failures after a
     *  successful emission (success, then the network dies, then heals). */
    var pollContinuously: Boolean = false

    /** Subscriptions to [observeArrivals]. Proves a refreshNow restart
     *  re-subscribed the poll without waiting for the next tick. */
    var arrivalsSubscriptions = 0
    var supportsStopTimetableValue: Boolean = false
    var supportsLineTimetableValue: Boolean = false

    /** Days recorded by [getStopTimetable]. The warm-up contract: the
     *  arrivals screen warms exactly today, the timetable screen all 7. */
    val stopTimetableCalls = mutableListOf<DayOfWeek>()
    val lineTimetableCalls = mutableListOf<DayOfWeek>()

    var variantStopsError: Throwable? = null

    /** Catalog behind the interface's default [searchStops]. [getStopCatalog]
     *  counts calls so debounce tests can assert one fetch per burst. */
    var stopCatalog: List<Stop> = emptyList()
    var stopCatalogError: Throwable? = null
    var stopCatalogCalls = 0

    /** When set, [getStopTimetable] suspends on it, letting a test hold a
     *  fetch in flight and release it later (day-switch race). */
    var stopTimetableGate: CompletableDeferred<Unit>? = null
    var stopTimetableResult: List<TimetableEntry> = emptyList()

    /** When set, [getStopTimetable] fails with it. This is the orphaned-fetch
     *  failure path of the day-switch race (a failure for an abandoned
     *  day must never overwrite the viewed day's content). */
    var stopTimetableError: Throwable? = null

    override val provider: Provider = Provider.OASA
    override val supportsStopTimetable: Boolean get() = supportsStopTimetableValue

    override suspend fun getStopRoutes(stopId: String): List<Line> {
        stopRoutesError?.let { throw it }
        stopRoutesGate?.await()
        return stopRoutes
    }

    // Same rule as the real adapters: one row per public line.
    override fun stopLinesForDisplay(lines: List<Line>): List<Line> = lines.distinctBy { it.shortName }

    override fun observeArrivals(
        stopId: String,
        lines: List<Line>,
    ): Flow<List<Arrival>> =
        flow {
            while (true) {
                arrivalsSubscriptions++
                arrivalsError?.let { throw it }
                emit(arrivals)
                if (!pollContinuously) break
                delay(15_000L) // matches the repositories' poll loop
            }
        }

    override suspend fun getStopsNear(
        lat: Double,
        lon: Double,
        limit: Int,
    ): List<Stop> {
        nearbyCalls += 1
        nearbyGate?.await()
        nearbyError?.let { throw it }
        return nearbyResult
    }

    // Tracked by StopsViewModelTest: "no fix -> must not fetch" is the
    // core contract of the location-unavailable state.
    var nearbyCalls = 0
    var nearbyResult: List<Stop> = emptyList()
    var nearbyError: Throwable? = null

    /** When set, [getStopsNear] suspends on it, so a test can hold a
     *  refresh in flight and observe the state mid-fetch. */
    var nearbyGate: CompletableDeferred<Unit>? = null

    override suspend fun getLines(): List<Line> = emptyList()

    override suspend fun getLineVariants(line: Line): List<LineVariant> {
        requestedLine = line
        lineVariantsError?.let { throw it }
        return lineVariants
    }

    /** Direction-switcher wiring: which variants [getLineVariants] returns
     *  and what [getVariantStops] serves per variant id. */
    var lineVariants: List<LineVariant> = emptyList()
    var lineVariantsError: Throwable? = null
    var variantStopsByVariant: Map<String, List<Stop>> = emptyMap()
    var geometry: List<GeoPoint> = emptyList()
    var vehicles: List<VehiclePosition> = emptyList()
    val requestedVariants = mutableListOf<LineVariant>()
    var requestedLine: Line? = null

    override suspend fun getVariantStops(variant: LineVariant): List<Stop> {
        requestedVariants += variant
        variantStopsError?.let { throw it }
        return variantStopsByVariant[variant.id] ?: emptyList()
    }

    // searchStops intentionally not overridden. The interface default
    // (rank over getStopCatalog) is the production path, so the fake
    // exercises it too.
    override suspend fun getStopCatalog(): List<Stop> {
        stopCatalogCalls++
        stopCatalogError?.let { throw it }
        return stopCatalog
    }

    /** [getRouteGeometry] recording and failure/gate hooks; the
     *  tap-to-route tests assert one fetch per direction (per-screen cache),
     *  a cancelled fetch never lands, and a failed fetch clears. */
    val geometryRequests = mutableListOf<LineVariant>()
    var geometryError: Throwable? = null
    var geometryGate: CompletableDeferred<Unit>? = null

    /** Per-variant geometry; falls back to [geometry]. The race test needs
     *  distinguishable polylines per direction. */
    var geometryByVariant: Map<String, List<GeoPoint>> = emptyMap()

    override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> {
        geometryRequests += variant
        geometryError?.let { throw it }
        geometryGate?.await()
        val key =
            if (variant.shapeId.isNullOrBlank()) {
                variant.id
            } else {
                "${variant.id}-${variant.shapeId}"
            }
        return geometryByVariant[key] ?: geometry
    }

    override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
        flow {
            emit(vehicles)
        }

    override val supportsLineTimetable: Boolean get() = supportsLineTimetableValue

    override suspend fun getStopTimetable(
        stopId: String,
        day: DayOfWeek,
    ): List<TimetableEntry> {
        stopTimetableCalls += day
        stopTimetableError?.let { throw it }
        stopTimetableGate?.await()
        return stopTimetableResult
    }

    override suspend fun getLineTimetable(
        variant: LineVariant,
        day: DayOfWeek,
    ): List<TimetableEntry> {
        lineTimetableCalls += day
        return emptyList()
    }
}

class FakeFavoritesRepository(
    initial: List<Stop> = emptyList(),
) : FavoritesStore {
    private val _favoriteStops = MutableStateFlow(initial)
    private val _favoriteLines = MutableStateFlow<List<LineVariant>>(emptyList())
    val toggled = mutableListOf<Stop>()
    val replaced = mutableListOf<Stop>()

    override val favoriteStops: StateFlow<List<Stop>> = _favoriteStops
    override val favoriteLines: StateFlow<List<LineVariant>> = _favoriteLines

    override suspend fun toggle(stop: Stop) {
        toggled += stop
        _favoriteStops.value =
            if (_favoriteStops.value.any { it.id == stop.id && it.provider == stop.provider }) {
                _favoriteStops.value.filterNot { it.id == stop.id && it.provider == stop.provider }
            } else {
                _favoriteStops.value + stop
            }
    }

    override suspend fun replace(stop: Stop) {
        replaced += stop
    }

    override suspend fun toggle(variant: LineVariant) {
        _favoriteLines.value =
            if (_favoriteLines.value.any { it.isSameRoute(variant) }) {
                _favoriteLines.value.filterNot { it.isSameRoute(variant) }
            } else {
                _favoriteLines.value + variant
            }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalsViewModelTest {
    private val stop =
        Stop(
            provider = Provider.OASA,
            id = "60010",
            name = "ΝΑΥΑΡΙΝΟΥ",
            lat = 37.9837,
            lon = 23.7349,
        )
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun arrival(
        minutes: Int,
        routeCode: String = "R1",
        shapeId: String? = null,
    ) = Arrival(routeCode, "01", "ΓΡΑΜΜΗ 1", "ΚΕΝΤΡΟ", minutes, shapeId = shapeId)

    private fun viewModel(
        repository: FakeTransitRepository = FakeTransitRepository(),
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        nowRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    ) = ArrivalsViewModel(repository, favorites, stop, nowRealtime)

    @Test
    fun load_success_routesDedupedAndArrivalsEmitted() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes =
                        listOf(
                            Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"),
                            Line(Provider.OASA, "R2", "01", "ΑΛΛΗ ΚΑΤΕΥΘΥΝΣΗ"),
                            // same public number
                            Line(Provider.OASA, "R3", "02", "ΤΕΡΜΑ"),
                        )
                    arrivals = listOf(arrival(3), arrival(1))
                }
            val vm = viewModel(repo)
            advanceUntilIdle()

            // stopLinesForDisplay applied: one row per public line.
            assertEquals(listOf("01", "02"), vm.routes.value.map { it.shortName })
            // Arrivals from the polling flow. Loading done, no error.
            assertEquals(listOf(3, 1), vm.arrivals.value.map { it.etaMinutes })
            assertFalse(vm.loading.value)
            assertNull(vm.error.value)
            assertNotNull(vm.lastUpdated.value) // HH:mm:ss timestamp set
        }

    @Test
    fun load_failure_showsErrorAndStops() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    stopRoutesError =
                        RuntimeException(
                            "δίκτυο κάτω",
                        )
                }
            val vm = viewModel(repo)
            advanceUntilIdle()

            assertFalse(vm.loading.value)
            assertEquals("δίκτυο κάτω", vm.error.value) // raw message, screens localize
            assertTrue(vm.routes.value.isEmpty())
            assertTrue(vm.arrivals.value.isEmpty())
        }

    @Test
    fun cancelledLoad_doesNotWriteErrorState() =
        runTest(mainDispatcher.scheduler) {
            // A cancelled load (the screen was popped, the entry's
            // ViewModelStore clears and cancels viewModelScope) must not land
            // its cancellation as an error. runCatchingNotCancelled keeps
            // cancelled fetches cancelled.
            val gate = CompletableDeferred<Unit>()
            val repo =
                FakeTransitRepository().apply {
                    stopRoutesGate = gate
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                }
            val store = ViewModelStore()
            val vm = ArrivalsViewModel(repo, FakeFavoritesRepository(), stop)
            store.put("arrivals", vm)
            runCurrent()
            assertTrue(vm.loading.value) // the fetch is suspended on the gate

            // The screen is popped: clearing the store cancels the scope.
            store.clear()
            gate.complete(Unit)
            runCurrent()

            // The cancellation must never surface as an error, and the routes
            // fetch's success path must not run either.
            assertNull(vm.error.value)
            assertTrue(vm.routes.value.isEmpty())
        }

    @Test
    fun pollFailure_setsError_thenRecoversOnNextTick() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivalsError = RuntimeException("poll broke")
                }
            val vm = viewModel(repo)
            // runCurrent, not advanceUntilIdle: the poll loop retries on a
            // timer, so the scheduler never goes idle while the error persists.
            runCurrent()

            assertEquals("poll broke", vm.error.value)
            assertFalse(vm.loading.value)

            // The network heals. The next poll tick recovers without a manual
            // retry (a plain .catch would have terminated the flow
            // permanently). This also ends the loop, so runTest can finish.
            repo.arrivalsError = null
            repo.arrivals = listOf(arrival(2))
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()

            assertNull(vm.error.value)
            assertEquals(listOf(2), vm.arrivals.value.map { it.etaMinutes })
        }

    @Test
    fun pollFailure_afterLiveData_keepsContent_noErrorScreen() =
        runTest(mainDispatcher.scheduler) {
            // Reported bug: app backgrounded, network dropped (Doze, Wi-Fi
            // sleep), every poll failed. The error state used to replace the
            // last known arrivals (and the map) with a full-screen error on
            // return. Once the screen has shown live data, poll failures must
            // keep the content visible and retry silently.
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivals = listOf(arrival(3), arrival(7))
                    pollContinuously = true
                }
            val vm = viewModel(repo)
            runCurrent()
            assertEquals(listOf(3, 7), vm.arrivals.value.map { it.etaMinutes })
            assertNull(vm.error.value)
            assertNotNull(vm.lastUpdated.value)

            // The network dies while the user is away: the poll fails but the
            // last known arrivals stay, no error state over good data.
            repo.arrivalsError = RuntimeException("Unable to resolve host \"oseth.com.gr\"")
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()
            assertNull(vm.error.value)
            assertFalse(vm.loading.value)
            assertEquals(listOf(3, 7), vm.arrivals.value.map { it.etaMinutes })

            // The network heals: the next tick refreshes on its own (and ends
            // the loop, so runTest can finish).
            repo.arrivalsError = null
            repo.pollContinuously = false
            repo.arrivals = listOf(arrival(2))
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()
            assertNull(vm.error.value)
            assertEquals(listOf(2), vm.arrivals.value.map { it.etaMinutes })
        }

    @Test
    fun refreshNow_restartsPollWhenDataOld() =
        runTest(mainDispatcher.scheduler) {
            // Screen-off wake: the data on screen is old, and the next
            // tick would be up to 15 s away. refreshNow must restart the
            // poll immediately.
            var clock = 0L
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivals = listOf(arrival(1))
                    pollContinuously = true
                }
            val store = ViewModelStore()
            val vm = ArrivalsViewModel(repo, FakeFavoritesRepository(), stop, nowRealtime = { clock })
            store.put("arrivals", vm)
            // runCurrent, not advanceUntilIdle: the fake polls forever.
            runCurrent()
            assertEquals(listOf(1), vm.arrivals.value.map { it.etaMinutes })
            val subscriptionsBefore = repo.arrivalsSubscriptions

            clock = 30_000L // data is 30 s old
            repo.arrivals = listOf(arrival(9))
            vm.refreshNow()
            runCurrent()

            assertEquals(subscriptionsBefore + 1, repo.arrivalsSubscriptions)
            assertEquals(listOf(9), vm.arrivals.value.map { it.etaMinutes })
            // Clear the forever-poll so it cannot leak into later tests.
            // a pending delay would spin any later advanceUntilIdle.
            store.clear()
        }

    @Test
    fun refreshNow_skipsWhenDataFresh() =
        runTest(mainDispatcher.scheduler) {
            // A quick app switch must not double-fetch: data landed moments
            // ago, the poll's own next tick is fine.
            var clock = 0L
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivals = listOf(arrival(1))
                    pollContinuously = true
                }
            val store = ViewModelStore()
            val vm = ArrivalsViewModel(repo, FakeFavoritesRepository(), stop, nowRealtime = { clock })
            store.put("arrivals", vm)
            // runCurrent, not advanceUntilIdle: the fake polls forever.
            runCurrent()
            assertEquals(listOf(1), vm.arrivals.value.map { it.etaMinutes })
            val subscriptionsBefore = repo.arrivalsSubscriptions

            repo.arrivals = listOf(arrival(9))
            vm.refreshNow()
            runCurrent()

            assertEquals(subscriptionsBefore, repo.arrivalsSubscriptions)
            assertEquals(listOf(1), vm.arrivals.value.map { it.etaMinutes })
            // The poll's own tick delivers the new data anyway.
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(9), vm.arrivals.value.map { it.etaMinutes })
            // Same cleanup as refreshNow_restartsPollWhenDataOld.
            store.clear()
        }

    @Test
    fun pollFailure_afterEmptyEmission_keepsNoArrivalsState() =
        runTest(mainDispatcher.scheduler) {
            // Even a legitimately empty list ("no buses at night") is live data
            // once emitted. A later poll failure must not replace it with an
            // error screen either.
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivals = emptyList()
                    pollContinuously = true
                }
            val vm = viewModel(repo)
            runCurrent()
            assertTrue(vm.arrivals.value.isEmpty())
            assertNull(vm.error.value)
            assertNotNull(vm.lastUpdated.value)

            repo.arrivalsError = RuntimeException("poll broke")
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()
            assertNull(vm.error.value)

            repo.arrivalsError = null
            repo.pollContinuously = false
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()
            assertNull(vm.error.value)
        }

    @Test
    fun retry_afterFailure_recovers() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    stopRoutesError =
                        RuntimeException(
                            "πρώτη φορά",
                        )
                }
            val vm = viewModel(repo)
            advanceUntilIdle()
            assertEquals("πρώτη φορά", vm.error.value)

            repo.stopRoutesError = null
            repo.arrivals = listOf(arrival(2))
            vm.retry()
            advanceUntilIdle()

            assertNull(vm.error.value)
            assertEquals(listOf(2), vm.arrivals.value.map { it.etaMinutes })
        }

    @Test
    fun toggleFavorite_delegatesToRepository() =
        runTest(mainDispatcher.scheduler) {
            val favorites = FakeFavoritesRepository()
            val vm = viewModel(favorites = favorites)
            vm.toggleFavorite(stop)
            advanceUntilIdle()

            assertEquals(listOf(stop), favorites.toggled)
        }

    @Test
    fun favoriteStops_filteredToThisProvider() =
        runTest(mainDispatcher.scheduler) {
            val oasa =
                Stop(
                    provider = Provider.OASA,
                    id = "60010",
                    name = "ΝΑΥΑΡΙΝΟΥ",
                    lat = 37.98,
                    lon = 23.73,
                )
            val larissa =
                Stop(
                    provider = Provider.CITYBUS,
                    id = "0116",
                    name = "ΑΓΟΡΑ",
                    lat = 39.64,
                    lon = 22.42,
                )
            val favorites = FakeFavoritesRepository(listOf(oasa, larissa))
            val vm = viewModel(favorites = favorites)

            val shown = vm.favoriteStops.first { it.isNotEmpty() }
            assertEquals(listOf(oasa), shown)
        }

    @Test
    fun supportsTimetable_reflectsRepository() {
        val repo = FakeTransitRepository().apply { supportsStopTimetableValue = true }
        assertTrue(viewModel(repo).supportsTimetable)
        assertFalse(viewModel().supportsTimetable)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 15_000L
    }
    // ------------------------------------------------ tap-to-route (selectBus)

    @Test
    fun sameVehicle_tripIdIsThePrimaryIdentity() {
        // CityBus/OSETh: the trip id identifies the run across polls.
        val a = arrival(1).copy(tripId = "T1", vehicleId = "V1")
        val b = arrival(2).copy(tripId = "T1", vehicleId = "V1")
        assertTrue(a.isSameVehicle(b))
        // Same bus number, different trip: not the same run (CityBus
        // trip ids switch per trip).
        val c = arrival(3).copy(tripId = "T2", vehicleId = "V1")
        assertFalse(a.isSameVehicle(c))
    }

    @Test
    fun sameVehicle_oasaFallsBackToBusNumber() {
        // OASA's tripId is always null. The bus number is the identity.
        val a = arrival(1, routeCode = "R1").copy(vehicleId = "1188")
        val b = arrival(4, routeCode = "R1").copy(vehicleId = "1188")
        assertTrue(a.isSameVehicle(b))
        val c = arrival(5, routeCode = "R2").copy(vehicleId = "1189")
        assertFalse(a.isSameVehicle(c))
        // Rows without any id cannot be re-identified: answer false so
        // the selection does not stick on a stale card forever.
        val d = arrival(6).copy(vehicleId = null, tripId = null)
        val e = arrival(7).copy(vehicleId = null, tripId = null)
        assertFalse(d.isSameVehicle(e))
    }

    @Test
    fun selectBus_busLeavesThePoll_dismissesCardAndRoute() =
        runTest(mainDispatcher.scheduler) {
            // The reported bug: a selected bus crosses the stop, disappears
            // from the list and the map, but its card (and route polyline)
            // stayed pinned with a stale ETA. The emission that drops the
            // bus must clear the selection on the spot.
            val bus = arrival(1).copy(tripId = "T1", vehicleId = "V1")
            val other = arrival(5).copy(tripId = "T2", vehicleId = "V2")
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivals = listOf(bus)
                    pollContinuously = true
                    geometry = listOf(GeoPoint(37.98, 23.72), GeoPoint(37.99, 23.75))
                }
            val store = ViewModelStore()
            val vm = ArrivalsViewModel(repo, FakeFavoritesRepository(), stop)
            store.put("arrivals", vm)
            runCurrent()
            vm.selectBus(bus)
            runCurrent()
            assertEquals(bus, vm.selectedArrival.value)
            assertTrue(vm.routeGeometry.value.isNotEmpty())

            // The next poll no longer reports the selected bus.
            repo.arrivals = listOf(other)
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()

            // Card and polyline go together, on the emission that dropped
            // the bus. No stale "now" left on the map.
            assertNull(vm.selectedArrival.value)
            assertTrue(vm.routeGeometry.value.isEmpty())
            // The other bus's rows still poll. The selection just cleared.
            assertEquals(listOf(other), vm.arrivals.value)
            store.clear()
        }

    @Test
    fun selectBus_busStillOnThePoll_keepsSelection() =
        runTest(mainDispatcher.scheduler) {
            // A bus that stays listed is still the selected one: the next
            // emission (new Arrival instance, same trip) must not clear it.
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "01", "ΚΕΝΤΡΟ"))
                    arrivals = listOf(arrival(1).copy(tripId = "T1", vehicleId = "V1"))
                    pollContinuously = true
                    geometry = listOf(GeoPoint(37.98, 23.72), GeoPoint(37.99, 23.75))
                }
            val store = ViewModelStore()
            val vm = ArrivalsViewModel(repo, FakeFavoritesRepository(), stop)
            store.put("arrivals", vm)
            runCurrent()
            vm.selectBus(arrival(1).copy(tripId = "T1", vehicleId = "V1"))
            runCurrent()
            assertNotNull(vm.selectedArrival.value)

            repo.arrivals = listOf(arrival(2).copy(tripId = "T1", vehicleId = "V1"))
            mainDispatcher.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
            runCurrent()

            assertNotNull(vm.selectedArrival.value)
            assertEquals("T1", vm.selectedArrival.value?.tripId)
            // The selection is the FRESH instance: the card counts down
            // with the list instead of freezing the tapped ETA.
            assertEquals(2, vm.selectedArrival.value?.etaMinutes)
            // Route kept from the per-screen cache too.
            assertTrue(vm.routeGeometry.value.isNotEmpty())
            store.clear()
        }

    @Test
    fun selectBus_fetchesGeometryOncePerDirection_thenServesFromMemory() =
        runTest(mainDispatcher.scheduler) {
            val line =
                listOf(
                    GeoPoint(37.98, 23.72),
                    GeoPoint(37.99, 23.75),
                    GeoPoint(38.0, 23.78),
                )
            val repo = FakeTransitRepository().apply { geometry = line }
            val vm = viewModel(repo)
            runCurrent() // initial load (the routes fetch) done

            vm.selectBus(arrival(2, routeCode = "R1"))
            advanceUntilIdle()
            assertEquals(line, vm.routeGeometry.value)
            assertEquals(1, repo.geometryRequests.size)
            // The default variantFor builds from the arrival's own fields.
            // the adapter's quirks stay out of the UI.
            assertEquals("R1", repo.geometryRequests.single().id)
            assertEquals("01", repo.geometryRequests.single().lineShortName)
            assertNull(repo.geometryRequests.single().shapeId)

            // Another bus of the same direction: served from the per-screen
            // cache (no re-read of the 30-day disk cache either).
            vm.selectBus(arrival(4, routeCode = "R1"))
            advanceUntilIdle()
            assertEquals(1, repo.geometryRequests.size)
            assertEquals(line, vm.routeGeometry.value)

            // A different direction fetches once more.
            vm.selectBus(arrival(3, routeCode = "R2"))
            advanceUntilIdle()
            assertEquals(2, repo.geometryRequests.size)

            // Closing the card clears the polyline. Re-selecting the cached
            // direction returns it without a fetch.
            vm.selectBus(null)
            assertTrue(vm.routeGeometry.value.isEmpty())
            vm.selectBus(arrival(5, routeCode = "R1"))
            advanceUntilIdle()
            assertEquals(2, repo.geometryRequests.size)
            assertEquals(line, vm.routeGeometry.value)
        }

    @Test
    fun selectBus_failedFetch_clearsAndRetriesOnNextSelection() =
        runTest(mainDispatcher.scheduler) {
            val line = listOf(GeoPoint(37.98, 23.72), GeoPoint(37.99, 23.75))
            val repo =
                FakeTransitRepository().apply {
                    geometry = line
                    geometryError = RuntimeException("no route info")
                }
            val vm = viewModel(repo)
            runCurrent()
            vm.selectBus(arrival(2))
            advanceUntilIdle()
            // A failure is not cached: no line, and no error state either
            // (the bus card is the feedback on an unofficial API).
            assertTrue(vm.routeGeometry.value.isEmpty())
            assertEquals(1, repo.geometryRequests.size)

            repo.geometryError = null
            vm.selectBus(arrival(3))
            advanceUntilIdle()
            assertEquals(2, repo.geometryRequests.size)
            assertEquals(line, vm.routeGeometry.value)
        }

    @Test
    fun selectBus_scheduledOrUnknownRow_neverFetches() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val vm = viewModel(repo)
            runCurrent()
            // Offline schedule estimates carry no route id.
            vm.selectBus(arrival(2).copy(routeCode = "", isScheduled = true))
            advanceUntilIdle()
            // CityBus-style row without a line code: variantFor needs it
            // for the points endpoint call.
            vm.selectBus(arrival(2).copy(lineShortName = ""))
            advanceUntilIdle()
            vm.selectBus(null)
            advanceUntilIdle()
            assertTrue(vm.routeGeometry.value.isEmpty())
            assertTrue(repo.geometryRequests.isEmpty())
        }

    @Test
    fun selectBus_switchingWhileFetchInFlight_cancelsOldFetch() =
        runTest(mainDispatcher.scheduler) {
            val a = listOf(GeoPoint(37.98, 23.72), GeoPoint(37.99, 23.75))
            val b = listOf(GeoPoint(38.0, 23.8), GeoPoint(38.02, 23.82))
            val repo =
                FakeTransitRepository().apply {
                    geometryByVariant = mapOf("R1" to a, "R2" to b)
                    geometryGate = CompletableDeferred()
                }
            val vm = viewModel(repo)
            runCurrent()
            vm.selectBus(arrival(2, routeCode = "R1"))
            runCurrent() // R1 suspended on the gate
            vm.selectBus(arrival(3, routeCode = "R2"))
            runCurrent() // cancels R1; R2 suspends on the gate too

            repo.geometryGate?.complete(Unit)
            advanceUntilIdle()

            // The cancelled R1 fetch must not land after R2 and overwrite
            // the polyline with the wrong direction.
            assertEquals(listOf("R1", "R2"), repo.geometryRequests.map { it.id })
            assertEquals(b, vm.routeGeometry.value)
        }

    @Test
    fun selectBus_oseThShapeId_keepsWeekdayAndWeekendApart() =
        runTest(mainDispatcher.scheduler) {
            val weekday = listOf(GeoPoint(40.6, 22.9), GeoPoint(40.7, 23.0))
            val weekend = listOf(GeoPoint(40.6, 22.9), GeoPoint(40.8, 23.1))
            val repo =
                FakeTransitRepository().apply {
                    geometryByVariant = mapOf("r1-s1" to weekday, "r1-s2" to weekend)
                }
            val vm = viewModel(repo)
            runCurrent()
            // Same route id, different shape (weekday vs ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ):
            // two shapes, two fetches, and the cache must not mix them.
            vm.selectBus(arrival(2, routeCode = "r1", shapeId = "s1"))
            advanceUntilIdle()
            assertEquals(weekday, vm.routeGeometry.value)
            vm.selectBus(arrival(3, routeCode = "r1", shapeId = "s2"))
            advanceUntilIdle()
            assertEquals(weekend, vm.routeGeometry.value)
            assertEquals(2, repo.geometryRequests.size)
        }

    // ------------------------------------------------ tier-1 warm-up (stop)

    @Test
    fun arrivalsScreenOpens_warmsTodayWeekdayOnly() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository().apply { supportsStopTimetableValue = true }
            viewModel(repo)
            runCurrent()
            // Exactly one timetable call, for the current weekday, the key the
            // offline degrade-to-schedule fallback reads. The full week comes
            // from the timetable screen's own warm-up.
            assertEquals(listOf(LocalDate.now().dayOfWeek), repo.stopTimetableCalls)
        }

    @Test
    fun arrivalsScreenOpens_withoutScheduleSupport_neverFetchesTimetable() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository() // supportsStopTimetable = false (OASA-like)
            viewModel(repo)
            runCurrent()
            assertTrue(repo.stopTimetableCalls.isEmpty())
        }

    @Test
    fun arrivalsScreenOpens_routesFetchFailed_skipsWarmUp() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    supportsStopTimetableValue = true
                    stopRoutesError = RuntimeException("boom")
                }
            viewModel(repo)
            runCurrent()
            // A failed routes fetch is the API-reachable proxy: no routes, no
            // timetable warm-up (the API is unreachable anyway).
            assertTrue(repo.stopTimetableCalls.isEmpty())
        }
}
