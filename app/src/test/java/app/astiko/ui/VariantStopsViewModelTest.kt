package app.astiko.ui

import app.astiko.data.model.GeoPoint
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.VehiclePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The direction-switcher contract on the stops-of-a-route screen:
 * `activeVariant` starts on the entry direction, `switchVariant` flips it
 * in place (one back-stack entry) and reloads stops/geometry/vehicles for
 * the new direction. Same-route switches are no-ops. Siblings come from
 * `getLineVariants` on a parent line reconstructed from the variant. A
 * failed sibling load keeps the switcher hidden without breaking the
 * shown direction. Plus the tier-1 line warm-up: opening a line's stop
 * list caches today's line timetable, exactly one call, only when the
 * provider supports line timetables and the stops loaded. The full week
 * comes from the timetable screen's own warm-up (TimetableViewModelTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VariantStopsViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // OSETh-style siblings: outbound/inbound share lineId "01" with
    // different route ids. The weekend run reuses the outbound route id
    // with a DIFFERENT shapeId (weekday/weekend shape pair).
    private val outbound =
        LineVariant(Provider.OSETh, "01", "r1", "s1", "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.", "01")
    private val inbound =
        LineVariant(Provider.OSETh, "01", "r2", "s2", "Κ.Τ.Ε.Λ. - Τ.Σ. ΕΥΚΑΡΠΙΑΣ", "01")
    private val weekend =
        LineVariant(
            Provider.OSETh,
            "01",
            "r1",
            "s3",
            "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ. - ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ",
            "01",
        )

    private fun stop(
        id: String,
        name: String,
    ) = Stop(provider = Provider.OSETh, id = id, name = name, lat = 40.0, lon = 22.9)

    private fun vehicle(id: String) = VehiclePosition(vehicleId = id, lat = 40.0, lon = 22.9)

    private fun viewModel(
        repository: FakeTransitRepository = FakeTransitRepository(),
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        variant: LineVariant = outbound,
    ) = VariantStopsViewModel(repository, favorites, variant)

    // ---------- direction switcher ----------

    @Test
    fun load_startsWithEntryVariant_andLoadsItsStops() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    variantStopsByVariant =
                        mapOf(
                            outbound.id to
                                listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ"), stop("2", "ΣΚΛΑΒΕΝΙΤΗΣ")),
                        )
                    geometry = listOf(GeoPoint(40.0, 22.9))
                    vehicles = listOf(vehicle("v1"))
                }
            val vm = viewModel(repo)
            advanceUntilIdle()

            assertEquals(outbound, vm.activeVariant.value)
            assertEquals(listOf("ΤΣ ΕΥΚΑΡΠΙΑΣ", "ΣΚΛΑΒΕΝΙΤΗΣ"), vm.stops.value.map { it.name })
            assertEquals(listOf(GeoPoint(40.0, 22.9)), vm.geometry.value)
            assertEquals(listOf(vehicle("v1")), vm.vehicles.value)
            assertFalse(vm.loading.value)
            assertNull(vm.error.value)
            // Exactly one stops fetch, for the entry direction.
            assertEquals(listOf(outbound), repo.requestedVariants)
        }

    @Test
    fun switchVariant_flipsInPlace_andReloadsForTheNewDirection() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    variantStopsByVariant =
                        mapOf(
                            outbound.id to listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ")),
                            inbound.id to listOf(stop("3", "ΚΤΕΛ")),
                        )
                }
            val vm = viewModel(repo)
            advanceUntilIdle()
            assertEquals(listOf("ΤΣ ΕΥΚΑΡΠΙΑΣ"), vm.stops.value.map { it.name })

            vm.switchVariant(inbound)
            advanceUntilIdle()

            assertEquals(inbound, vm.activeVariant.value)
            assertEquals(listOf("ΚΤΕΛ"), vm.stops.value.map { it.name })
            assertEquals(listOf(outbound, inbound), repo.requestedVariants)
            assertNull(vm.error.value)
        }

    @Test
    fun switchVariant_sameRoute_isNoOp() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    variantStopsByVariant = mapOf(outbound.id to listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ")))
                }
            val vm = viewModel(repo)
            advanceUntilIdle()

            vm.switchVariant(outbound) // identical variant
            advanceUntilIdle()

            assertEquals(outbound, vm.activeVariant.value)
            // No second fetch. The guard is the isSameRoute identity.
            assertEquals(1, repo.requestedVariants.size)
        }

    @Test
    fun switchVariant_weekdayToWeekendShape_isADifferentDirection() =
        runTest(mainDispatcher.scheduler) {
            // Same route id "r1", different shapeId, the OSETh weekday/weekend
            // pair. Must count as a switch (different stop lists).
            val repo =
                FakeTransitRepository().apply {
                    variantStopsByVariant =
                        mapOf(
                            outbound.id to listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ")),
                            weekend.id to listOf(stop("5", "ΠΑΡΚΟ ΣΜΥΡΝΗΣ")),
                        )
                }
            val vm = viewModel(repo)
            advanceUntilIdle()
            assertTrue(outbound.isSameRoute(weekend).not())

            vm.switchVariant(weekend)
            advanceUntilIdle()

            assertEquals(weekend, vm.activeVariant.value)
            assertEquals(listOf("ΠΑΡΚΟ ΣΜΥΡΝΗΣ"), vm.stops.value.map { it.name })
        }

    @Test
    fun variants_loadSiblings_viaParentLineReconstructedFromTheVariant() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    lineVariants = listOf(outbound, inbound, weekend)
                    variantStopsByVariant = mapOf(outbound.id to listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ")))
                }
            val vm = viewModel(repo, variant = outbound)
            advanceUntilIdle()

            assertEquals(listOf(outbound, inbound, weekend), vm.variants.value)
            // The parent line must carry the variant's provider, line id and
            // public number. Adapters resolve siblings against these.
            assertEquals(Provider.OSETh, repo.requestedLine?.provider)
            assertEquals("01", repo.requestedLine?.id)
            assertEquals("01", repo.requestedLine?.shortName)
        }

    @Test
    fun variantsLoadFailure_keepsTheSwitcherHidden_andTheDirectionWorking() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    lineVariantsError = RuntimeException("δίκτυο κάτω")
                    variantStopsByVariant = mapOf(outbound.id to listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ")))
                }
            val vm = viewModel(repo)
            advanceUntilIdle()

            assertTrue(vm.variants.value.isEmpty()) // switcher hidden
            assertEquals(listOf("ΤΣ ΕΥΚΑΡΠΙΑΣ"), vm.stops.value.map { it.name })
            // current direction alive
            assertNull(vm.error.value)
        }

    @Test
    fun loadFailure_setsError_retryHeals() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    variantStopsError = RuntimeException("δίκτυο κάτω")
                    variantStopsByVariant = mapOf(outbound.id to listOf(stop("1", "ΤΣ ΕΥΚΑΡΠΙΑΣ")))
                }
            val vm = viewModel(repo)
            advanceUntilIdle()
            assertEquals("δίκτυο κάτω", vm.error.value)
            assertFalse(vm.loading.value)

            repo.variantStopsError = null
            vm.retry()
            advanceUntilIdle()

            assertNull(vm.error.value)
            assertEquals(listOf("ΤΣ ΕΥΚΑΡΠΙΑΣ"), vm.stops.value.map { it.name })
        }

    @Test
    fun favoriteLineToggle_delegatesToFavoritesRepository() =
        runTest(mainDispatcher.scheduler) {
            val favorites = FakeFavoritesRepository()
            val vm = viewModel(favorites = favorites)
            advanceUntilIdle()

            vm.toggleFavoriteLine(outbound)
            advanceUntilIdle()
            assertTrue(favorites.favoriteLines.value.any { it.isSameRoute(outbound) })

            vm.toggleFavoriteLine(outbound)
            advanceUntilIdle()
            assertFalse(favorites.favoriteLines.value.any { it.isSameRoute(outbound) })
        }

    @Test
    fun supportsTimetable_reflectsRepositoryCapability() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository().apply { supportsLineTimetableValue = true }
            val vm = viewModel(repo)
            assertTrue(vm.supportsTimetable)
        }

    // ---------- tier-1 line timetable warm-up ----------

    @Test
    fun lineStopListOpened_warmsTodayWeekdayOnly() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository().apply { supportsLineTimetableValue = true }
            viewModel(repo)
            runCurrent()
            assertEquals(listOf(LocalDate.now().dayOfWeek), repo.lineTimetableCalls)
        }

    @Test
    fun lineStopListOpened_withoutScheduleSupport_neverWarms() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository() // supportsLineTimetable = false (OASA-like)
            viewModel(repo)
            runCurrent()
            assertTrue(repo.lineTimetableCalls.isEmpty())
        }

    @Test
    fun lineStopListOpened_stopsFetchFailed_skipsWarmUp() =
        runTest(mainDispatcher.scheduler) {
            val repo =
                FakeTransitRepository().apply {
                    supportsLineTimetableValue = true
                    variantStopsError = RuntimeException("boom")
                }
            viewModel(repo)
            runCurrent()
            // The stops fetch is the API-reachable proxy: no stops, no warm-up.
            assertTrue(repo.lineTimetableCalls.isEmpty())
        }
}
