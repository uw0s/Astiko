package app.astiko.ui

import android.location.Location
import androidx.lifecycle.viewModelScope
import app.astiko.data.model.City
import app.astiko.data.model.Line
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The nearby state machine, especially the location-unavailable
 * contract: with no GPS fix the screen must show the hint and must not
 * fetch city-center stops pretending to be nearby.
 *
 * The android.jar Location stub returns defaults (no real coords), so
 * tests assert fetch CALLS, not coordinates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Mutable time source: tests set [now] to walk past the TTL. */
    private class TestClock(
        var now: Long = 0L,
    ) {
        operator fun invoke(): Long = now
    }

    private fun viewModel(
        repo: FakeTransitRepository = FakeTransitRepository(),
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        location: FakeLocationProvider = FakeLocationProvider(),
        permission: Boolean = true,
        clock: TestClock = TestClock(),
    ) = StopsViewModel(
        city = City.ATHENS, // has coordinates, so the VM can resolve it
        repository = repo,
        favoritesRepository = favorites,
        locationProvider = location,
        hasLocationPermission = { permission },
        nowMs = { clock.now },
    )

    private fun fakeFix() = Location("gps")

    @Test
    fun noFix_permissionGranted_showsLocationUnavailableWithoutFetching() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = null))

            vm.refreshNearby()
            advanceUntilIdle()

            assertEquals(
                StopsViewModel.NearbyState.Ready(emptyList(), locationUnavailable = true),
                vm.nearby.value,
            )
            assertEquals(0, repo.nearbyCalls)
        }

    @Test
    fun withFix_fetchesNearbyAndReportsAvailable() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    nearbyResult = listOf(stop())
                }
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = fakeFix()))

            vm.refreshNearby()
            advanceUntilIdle()

            assertEquals(
                StopsViewModel.NearbyState.Ready(listOf(stop()), locationUnavailable = false),
                vm.nearby.value,
            )
            assertEquals(1, repo.nearbyCalls)
        }

    @Test
    fun noFix_afterPreviousFix_keepsListAliveFromSessionCache() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val location = FakeLocationProvider(fix = fakeFix())
            val vm = viewModel(repo = repo, location = location)

            vm.refreshNearby()
            advanceUntilIdle()
            assertEquals(1, repo.nearbyCalls)

            // GPS lost mid-session: the ViewModel's lastLocation still answers,
            // so the list stays. The hint only shows when there was never a fix.
            // force: this test is about the location fallback, not the TTL gate.
            location.fix = null
            vm.refreshNearby(force = true)
            advanceUntilIdle()

            assertEquals(
                StopsViewModel.NearbyState.Ready(emptyList(), locationUnavailable = false),
                vm.nearby.value,
            )
            assertEquals(2, repo.nearbyCalls)
        }

    @Test
    fun reEntry_withinTtl_keepsListWithoutRefetch() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val clock = TestClock()
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = fakeFix()), clock = clock)

            vm.refreshNearby()
            advanceUntilIdle()
            assertEquals(1, repo.nearbyCalls)

            // 30 s later, inside the 60 s TTL: re-entry must be a
            // no-op, no flash, no API call.
            clock.now = 30_000
            vm.refreshNearby()
            advanceUntilIdle()

            assertEquals(1, repo.nearbyCalls)
            assertEquals(
                StopsViewModel.NearbyState.Ready(emptyList(), locationUnavailable = false),
                vm.nearby.value,
            )
        }

    @Test
    fun reEntry_afterTtl_refreshesSilently_keepsRowsVisibleMidFetch() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val clock = TestClock()
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = fakeFix()), clock = clock)

            vm.refreshNearby()
            advanceUntilIdle()
            assertEquals(1, repo.nearbyCalls)

            // Past the TTL. The refetch must not flash Loading. The old
            // rows stay visible while the new ones are in flight.
            clock.now = 61_000
            val gate = CompletableDeferred<Unit>()
            repo.nearbyGate = gate
            repo.nearbyResult = listOf(stop())
            vm.refreshNearby()
            runCurrent()

            assertEquals(2, repo.nearbyCalls)
            assertEquals(
                StopsViewModel.NearbyState.Ready(emptyList(), locationUnavailable = false),
                vm.nearby.value,
            )

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                StopsViewModel.NearbyState.Ready(listOf(stop()), locationUnavailable = false),
                vm.nearby.value,
            )
        }

    @Test
    fun reEntry_afterTtl_refreshFailure_keepsOldRows() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository().apply { nearbyResult = listOf(stop()) }
            val clock = TestClock()
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = fakeFix()), clock = clock)

            vm.refreshNearby()
            advanceUntilIdle()
            assertEquals(1, repo.nearbyCalls)

            // A silent refresh that fails must keep the previous rows, not
            // switch to the Error state.
            clock.now = 61_000
            repo.nearbyError = IOException("offline")
            vm.refreshNearby()
            advanceUntilIdle()

            assertEquals(2, repo.nearbyCalls)
            assertEquals(
                StopsViewModel.NearbyState.Ready(listOf(stop()), locationUnavailable = false),
                vm.nearby.value,
            )
        }

    @Test
    fun manualRefresh_force_bypassesTtl_showsLoading() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val clock = TestClock()
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = fakeFix()), clock = clock)

            vm.refreshNearby()
            advanceUntilIdle()
            assertEquals(1, repo.nearbyCalls)

            // The refresh button refetches inside the TTL and shows the
            // spinner.
            clock.now = 30_000
            val gate = CompletableDeferred<Unit>()
            repo.nearbyGate = gate
            vm.refreshNearby(force = true)
            runCurrent()

            assertEquals(2, repo.nearbyCalls)
            assertEquals(StopsViewModel.NearbyState.Loading, vm.nearby.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                StopsViewModel.NearbyState.Ready(emptyList(), locationUnavailable = false),
                vm.nearby.value,
            )
        }

    @Test
    fun noPermission_showsNeedsPermissionWithoutFetching() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository()
            val vm = viewModel(repo = repo, permission = false)

            vm.refreshNearby()
            advanceUntilIdle()

            assertEquals(StopsViewModel.NearbyState.NeedsPermission, vm.nearby.value)
            assertEquals(0, repo.nearbyCalls)
        }

    @Test
    fun fetchFailure_showsErrorState() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    nearbyError = IOException("no network")
                }
            val vm = viewModel(repo = repo, location = FakeLocationProvider(fix = fakeFix()))

            vm.refreshNearby()
            advanceUntilIdle()

            val state = vm.nearby.value
            assertTrue(state is StopsViewModel.NearbyState.Error)
            assertEquals("no network", (state as StopsViewModel.NearbyState.Error).message)
        }

    @Test
    fun favoriteEnrichment_failedFetch_isNotPersisted() =
        runTest(mainDispatcher.scheduler) {
            // A failed enrichment (offline) must not write the
            // badge-less copy back to the store. That write re-emits the
            // favorites flow and loops (failed fetch, write, emit, fetch...)
            // while the screen is open. The row still renders badge-less, but
            // nothing is persisted.
            val favorites = FakeFavoritesRepository(initial = listOf(stop()))
            val repo = FakeTransitRepository().apply { stopRoutesError = IOException("offline") }
            val vm = viewModel(repo = repo, favorites = favorites)

            advanceUntilIdle()

            assertEquals(listOf(stop().copy(distanceKm = null)), vm.favoriteRows.value)
            assertTrue(favorites.replaced.isEmpty())
        }

    @Test
    fun favoriteEnrichment_successfulFetch_isPersistedOnce() =
        runTest(mainDispatcher.scheduler) {
            val favorites = FakeFavoritesRepository(initial = listOf(stop()))
            val repo =
                FakeTransitRepository().apply {
                    stopRoutes = listOf(Line(Provider.OASA, "R1", "03K", "ΑΣ ΙΚΕΑ"))
                }
            val vm = viewModel(repo = repo, favorites = favorites)

            advanceUntilIdle()

            assertEquals(listOf("03K"), vm.favoriteRows.value[0].servingLines)
            assertEquals(1, favorites.replaced.size)
        }

    @Test
    fun permissionGrantedResult_startsTrackingAndRefreshes() =
        runTest(mainDispatcher.scheduler) {
            val location = FakeLocationProvider()
            val vm = viewModel(location = location)

            vm.onLocationPermissionResult(true)
            runCurrent() // not advanceUntilIdle: the 15 s location poll loops forever

            assertTrue(location.trackingStarted)
            assertEquals(
                StopsViewModel.NearbyState.Ready(emptyList(), locationUnavailable = true),
                vm.nearby.value,
            )

            // Cleanup: cancel the VM scope so the 15 s poll loop stops. A
            // running poll loop keeps the test scheduler busy forever (the
            // same trap CityViewModelTest's AUTO-mode tests document).
            vm.viewModelScope.cancel()
            runCurrent()
        }

    private fun stop() =
        Stop(
            provider = Provider.OASA,
            id = "60010",
            name = "ΝΑΥΑΡΙΝΟΥ",
            lat = 37.9837,
            lon = 23.7349,
        )
}
