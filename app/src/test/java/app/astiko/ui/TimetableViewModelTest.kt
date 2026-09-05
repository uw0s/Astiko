package app.astiko.ui

import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Timetable day-switch race: switching days while the previous day's fetch
 * is still in flight used to let the stale response overwrite the screen
 * (the fetch was never cancelled, and runCatching swallowed the
 * cancellation). The selected day must always win.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModelTest {
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

    private fun entry(
        line: String,
        time: String,
    ) = TimetableEntry(line, line, "ΚΕΝΤΡΟ", time)

    private fun viewModel(repo: FakeTransitRepository) = TimetableViewModel(repo, TimetableTarget.StopTarget(stop))

    @Test
    fun daySwitch_cancelsInFlightFetch_staleDayNeverOverwrites() =
        runTest(mainDispatcher.scheduler) {
            val today = LocalDate.now().dayOfWeek
            val tomorrow = today.plus(1)
            val gate = CompletableDeferred<Unit>()
            val repo =
                FakeTransitRepository().apply {
                    stopTimetableResult = listOf(entry("01", "10:00"))
                    stopTimetableGate = gate // hold the initial fetch in flight
                }
            val vm = viewModel(repo)
            runCurrent()
            assertTrue(vm.loading.value) // today's fetch suspended on the gate

            // The user switches to tomorrow while today's fetch is still in flight.
            repo.stopTimetableResult = listOf(entry("02", "12:00"))
            repo.stopTimetableGate = null // the new day's fetch must not wait
            vm.selectDay(tomorrow)
            runCurrent()
            assertEquals(tomorrow, vm.selectedDay.value)
            assertEquals(listOf("02"), vm.entries.value.map { it.lineShortName })

            // Today's response finally arrives. It must not overwrite the
            // selected day. The stale fetch was cancelled at the gate.
            gate.complete(Unit)
            runCurrent()
            assertEquals(tomorrow, vm.selectedDay.value)
            assertEquals(listOf("02"), vm.entries.value.map { it.lineShortName })
        }

    @Test
    fun switchBackToCachedDay_orphanedFetchFailure_doesNotOverwriteIt() =
        runTest(mainDispatcher.scheduler) {
            // Day A is cached. The user taps B (fetch starts), then A again
            // (cache hit, the early return must cancel B's fetch). B's fetch
            // then fails: the failure must never land over A's content.
            val today = LocalDate.now().dayOfWeek
            val tomorrow = today.plus(1)
            val gate = CompletableDeferred<Unit>()
            val repo =
                FakeTransitRepository().apply {
                    stopTimetableResult = listOf(entry("01", "10:00"))
                }
            val vm = viewModel(repo)
            runCurrent()
            assertEquals(listOf("01"), vm.entries.value.map { it.lineShortName }) // today cached

            // Tap tomorrow: its fetch suspends on the gate, then fails.
            repo.stopTimetableGate = gate
            repo.stopTimetableError = IOException("network down")
            vm.selectDay(tomorrow)
            runCurrent()
            assertEquals(listOf("01"), vm.entries.value.map { it.lineShortName }) // still today

            // Tap today again (cache hit). The abandoned fetch is cancelled
            // at the top of load(), so its failure must not surface.
            repo.stopTimetableGate = null
            vm.selectDay(today)
            runCurrent()
            assertEquals(listOf("01"), vm.entries.value.map { it.lineShortName })
            assertNull(vm.error.value)

            // Release the abandoned fetch. Even if it somehow survived the
            // cancellation, the failure stays silenced (day guard).
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("01"), vm.entries.value.map { it.lineShortName })
            assertNull(vm.error.value)
        }
    // ------------------------------------------------ tier-2 warm-up (full week)

    @Test
    fun firstSuccessfulFetch_warmsAllSevenWeekdays() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository().apply { supportsStopTimetableValue = true }
            viewModel(repo)
            runCurrent()
            // The selected day's own fetch + the warm-up batch cover every weekday.
            assertEquals(DayOfWeek.entries.toSet(), repo.stopTimetableCalls.toSet())
        }

    @Test
    fun withoutScheduleSupport_warmsNothing() =
        runTest(mainDispatcher.scheduler) {
            val repo = FakeTransitRepository() // supportsStopTimetable = false
            viewModel(repo)
            runCurrent()
            // Only the selected day's own fetch, no 7-day warm-up batch.
            assertEquals(listOf(LocalDate.now().dayOfWeek), repo.stopTimetableCalls)
        }

    @Test
    fun warmUpRunsOnlyOnce_daySwitchesDoNotRefireIt() =
        runTest(mainDispatcher.scheduler) {
            val today = LocalDate.now().dayOfWeek
            val repo = FakeTransitRepository().apply { supportsStopTimetableValue = true }
            val vm = viewModel(repo)
            runCurrent()
            val afterWarm = repo.stopTimetableCalls.size
            assertTrue("warm-up must fetch all 7 days, got $afterWarm", afterWarm >= 7)

            vm.selectDay(today.plus(1))
            runCurrent()
            // Exactly one more fetch (the newly selected day). The 7-day batch
            // must not rerun on every day switch.
            assertEquals(afterWarm + 1, repo.stopTimetableCalls.size)
        }

    @Test
    fun lineTarget_firstSuccessfulFetch_warmsAllSevenWeekdays() =
        runTest(mainDispatcher.scheduler) {
            val variant =
                LineVariant(
                    Provider.OASA,
                    lineId = "1",
                    id = "1",
                    label = "ΚΕΝΤΡΟ",
                    lineShortName = "01",
                )
            val repo = FakeTransitRepository().apply { supportsLineTimetableValue = true }
            TimetableViewModel(repo, TimetableTarget.LineVariantTarget(variant))
            runCurrent()
            assertEquals(DayOfWeek.entries.toSet(), repo.lineTimetableCalls.toSet())
        }

    @Test
    fun lineTarget_withoutSupport_warmsNothing() =
        runTest(mainDispatcher.scheduler) {
            val variant =
                LineVariant(
                    Provider.OASA,
                    lineId = "1",
                    id = "1",
                    label = "ΚΕΝΤΡΟ",
                    lineShortName = "01",
                )
            val repo = FakeTransitRepository() // supportsLineTimetable = false
            TimetableViewModel(repo, TimetableTarget.LineVariantTarget(variant))
            runCurrent()
            assertEquals(listOf(LocalDate.now().dayOfWeek), repo.lineTimetableCalls)
        }
}
