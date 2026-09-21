package app.astiko.ui

import android.location.Location
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.util.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The search screen's state machine: a blank query stays idle without
 * fetching, retyping collapses to one search after the pause, an error
 * surfaces with retry, and clearing returns to the prompt. The debounce
 * runs on virtual time, so no real waiting in the tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopSearchViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stop(
        id: String,
        name: String,
    ) = Stop(provider = Provider.CITYBUS, id = id, name = name, lat = 0.0, lon = 0.0)

    private fun stopAt(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
    ) = Stop(provider = Provider.CITYBUS, id = id, name = name, lat = lat, lon = lon)

    private fun viewModel(
        repo: FakeTransitRepository = FakeTransitRepository(),
        location: TestLocationTracker = TestLocationTracker(),
        permission: Boolean = false,
    ) = StopSearchViewModel(
        repository = repo,
        favoritesRepository = FakeFavoritesRepository(),
        locationProvider = location,
        hasLocationPermission = { permission },
    )

    @Test
    fun blankQueryStaysIdleWithoutFetching() =
        runTest {
            val repo = FakeTransitRepository()
            val vm = viewModel(repo)

            vm.setQuery("   ")
            advanceUntilIdle()

            assertEquals(StopSearchViewModel.SearchState.Idle, vm.state.value)
            assertEquals(0, repo.stopCatalogCalls)
        }

    @Test
    fun querySearchesAfterDebounce() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("αγορά")
            advanceUntilIdle()

            assertEquals(1, repo.stopCatalogCalls)
            assertEquals(
                StopSearchViewModel.SearchState.Ready(listOf(stop("1", "ΑΓΟΡΑ"))),
                vm.state.value,
            )
        }

    @Test
    fun fastRetypeFetchesCatalogOnce() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("α")
            advanceTimeBy(100)
            runCurrent()
            vm.setQuery("αγ")
            advanceTimeBy(100)
            runCurrent()
            vm.setQuery("αγορ")
            advanceUntilIdle()

            // The pause resets with every keystroke: the burst collapses
            // to one catalog fetch, not three.
            assertEquals(1, repo.stopCatalogCalls)
        }

    @Test
    fun errorSurfacesAndRetryResearches() =
        runTest {
            val repo =
                FakeTransitRepository().apply {
                    stopCatalogError = IOException("boom")
                }
            val vm = viewModel(repo)

            vm.setQuery("αγορ")
            advanceUntilIdle()
            val error = vm.state.value as StopSearchViewModel.SearchState.Error
            assertEquals("boom", error.message)

            // The failure passes. Retry re-searches the same query.
            repo.stopCatalogError = null
            repo.stopCatalog = listOf(stop("1", "ΑΓΟΡΑ"))
            vm.retry()
            advanceUntilIdle()

            assertEquals(2, repo.stopCatalogCalls)
            assertEquals(
                StopSearchViewModel.SearchState.Ready(listOf(stop("1", "ΑΓΟΡΑ"))),
                vm.state.value,
            )
        }

    @Test
    fun clearingQueryReturnsToIdle() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("αγορά")
            advanceUntilIdle()
            assertTrue(vm.state.value is StopSearchViewModel.SearchState.Ready)

            vm.setQuery("")
            advanceUntilIdle()
            assertEquals(StopSearchViewModel.SearchState.Idle, vm.state.value)
        }

    @Test
    fun withoutPermission_resultsKeepNoDistanceAndNoFixIsRead() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val location = TestLocationTracker(fix = fix(40.6329, 22.9398))
            val vm = viewModel(repo = repo, location = location, permission = false)

            vm.setQuery("αγορά")
            advanceUntilIdle()

            val ready = vm.state.value as StopSearchViewModel.SearchState.Ready
            assertEquals(null, ready.stops.single().distanceKm)
            // No permission means no location read and no tracking.
            assertEquals(0, location.lastKnownReads)
            assertFalse(location.trackingStarted)
        }

    @Test
    fun withPermissionNoFix_resultsKeepNoDistance() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm =
                viewModel(
                    repo = repo,
                    location = TestLocationTracker(fix = null),
                    permission = true,
                )

            vm.setQuery("αγορά")
            advanceUntilIdle()

            val ready = vm.state.value as StopSearchViewModel.SearchState.Ready
            assertEquals(null, ready.stops.single().distanceKm)
        }

    @Test
    fun withPermissionAndFix_resultsCarryDistanceWithoutReordering() =
        runTest {
            val near = stopAt("1", "ΑΓΟΡΑ", 40.6329, 22.9398)
            val far = stopAt("2", "ΑΓΟΡΑ", 40.6419, 22.9398) // ~1 km north
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(far, near) }
            // The repository owns the ranking, the screen must keep it.
            val ranked = repo.searchStops("αγορά").map { it.id }
            val vm =
                viewModel(
                    repo = repo,
                    location = TestLocationTracker(fix = fix(40.6329, 22.9398)),
                    permission = true,
                )

            vm.setQuery("αγορά")
            advanceUntilIdle()

            val ready = vm.state.value as StopSearchViewModel.SearchState.Ready
            assertEquals(ranked, ready.stops.map { it.id })
            val distances = ready.stops.associate { it.id to it.distanceKm!! }
            assertEquals(0.0, distances.getValue("1"), 0.001)
            assertEquals(1.0, distances.getValue("2"), 0.05)
        }

    @Test
    fun resetClearsQueryStateAndScroll() =
        runTest {
            val repo = FakeTransitRepository().apply { stopCatalog = listOf(stop("1", "ΑΓΟΡΑ")) }
            val vm = viewModel(repo)

            vm.setQuery("αγορά")
            advanceUntilIdle()
            vm.listState.requestScrollToItem(37)
            assertTrue(vm.state.value is StopSearchViewModel.SearchState.Ready)

            // Next open of the search entry: previous session is discarded.
            vm.reset()
            advanceUntilIdle()

            assertEquals("", vm.query.value)
            assertEquals(StopSearchViewModel.SearchState.Idle, vm.state.value)
            assertEquals(0, vm.listState.firstVisibleItemIndex)
            // The reset query is idle, so nothing was re-searched.
            assertEquals(1, repo.stopCatalogCalls)
        }
}

/**
 * A [Location] that reports real coordinates, the mockable android.jar
 * returns 0.0 from the getters.
 */
private class FixAt(
    latDeg: Double,
    lonDeg: Double,
) : Location("gps") {
    private val latDeg = latDeg
    private val lonDeg = lonDeg

    override fun getLatitude(): Double = latDeg

    override fun getLongitude(): Double = lonDeg
}

private fun fix(
    lat: Double,
    lon: Double,
) = FixAt(lat, lon)

/**
 * Counts the reads and the tracking start, so a test can prove the search
 * never reaches for location behind the permission gate.
 */
private class TestLocationTracker(
    var fix: Location? = null,
) : LocationTracker {
    var lastKnownReads = 0
    var trackingStarted = false

    override suspend fun currentLocationOrNull(timeoutMs: Long): Location? = fix

    override fun lastKnownOrNull(): Location? {
        lastKnownReads += 1
        return fix
    }

    override fun startTracking(
        minTimeMs: Long,
        minDistanceM: Float,
        onFix: (Location) -> Unit,
    ): AutoCloseable {
        trackingStarted = true
        return AutoCloseable {}
    }
}
