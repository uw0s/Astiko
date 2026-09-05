package app.astiko.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The whole app's error-handling contract sits on this util: a cancelled
 * coroutine must keep cancelling, never report the cancellation as a
 * failure (a stale refresh must not overwrite the state a newer fetch
 * produced). A regression to plain runCatching would silently reintroduce
 * the bug.
 */
class RunCatchingTest {
    @Test
    fun success_returnsTheValue() =
        runBlocking {
            val result = runCatchingNotCancelled { 42 }
            assertTrue(result.isSuccess)
            assertEquals(42, result.getOrNull())
        }

    @Test
    fun failure_returnsTheFailure() =
        runBlocking {
            val boom = IOException("network down")
            val result = runCatchingNotCancelled { throw boom }
            assertTrue(result.isFailure)
            assertEquals(boom, result.exceptionOrNull())
        }

    @Test
    fun cancellation_isRethrown_notReportedAsFailure() =
        runBlocking {
            val cancelled = CancellationException("job was cancelled")
            val thrown =
                try {
                    runCatchingNotCancelled { throw cancelled }
                    null
                } catch (e: CancellationException) {
                    e
                }
            // The cancellation escapes the catch-all, so a caller's
            // `.onFailure { }` must never see it.
            assertEquals(cancelled, thrown)
        }

    @Test
    fun nestedCancellation_isRethrown() =
        runBlocking {
            val cancelled = CancellationException("nested")
            val thrown =
                try {
                    runCatchingNotCancelled {
                        runCatchingNotCancelled { throw cancelled }
                            .getOrThrow()
                    }
                    null
                } catch (e: CancellationException) {
                    e
                }
            assertEquals(cancelled, thrown)
        }
}
