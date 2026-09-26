package app.astiko.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bounded fan-out the adapters and the ViewModels use for their
 * per-item calls: at most [concurrency] in flight, results in input
 * order, and cancellation reaching the blocks that are still running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapBoundedTest {
    @Test
    fun `maps every item and keeps the input order`() =
        runTest {
            val doubled = (1..9).mapBounded(4) { it * 2 }

            assertEquals((1..9).map { it * 2 }, doubled)
        }

    @Test
    fun `runs at most the requested number in flight`() =
        runTest {
            var inFlight = 0
            var peak = 0

            (1..9).mapBounded(3) {
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(10)
                inFlight--
            }

            // The first batch fills the bound, then each next batch waits
            // for the previous one to settle.
            assertEquals(3, peak)
        }

    @Test
    fun `one in flight at a time stays sequential`() =
        runTest {
            val order = mutableListOf<Int>()

            (1..4).mapBounded(1) {
                order += it
                delay(5)
            }

            assertEquals(listOf(1, 2, 3, 4), order)
        }

    @Test
    fun `cancellation reaches the blocks that are running`() =
        runTest {
            var started = 0
            var cancelled = 0

            val job =
                launch {
                    (1..4).mapBounded(2) {
                        started++
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                }
            advanceUntilIdle()
            assertEquals(2, started)

            job.cancelAndJoin()

            // Both in-flight blocks were cancelled with the caller, and the
            // caller is really finished (runTest would fail on a leaked child).
            assertEquals(2, cancelled)
        }
}
