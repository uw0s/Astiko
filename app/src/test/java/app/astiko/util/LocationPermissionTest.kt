package app.astiko.util

import android.location.Location
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the permission helper applies to a request result, and the
 * polled-fix fallback that comes with tracking. Both are pure enough to pin
 * here: the rules decide whether a screen shows a permission card or works
 * with an approximate fix, and the poll is the workaround for fixes that
 * update the cached location without reaching a listener.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPermissionTest {
    @Test
    fun `a grant is allowed and never permanent`() {
        val result =
            locationPermissionResult(
                granted = true,
                hasPermission = false,
                canAskAgain = true,
            )

        assertEquals(LocationPermissionResult(allowed = true, permanentlyDenied = false), result)
    }

    @Test
    fun `an approximate only grant counts as allowed`() {
        // Android 12+ "Approximate location": COARSE granted, FINE denied,
        // and the dialog will not offer itself again.
        val result =
            locationPermissionResult(
                granted = false,
                hasPermission = true,
                canAskAgain = false,
            )

        assertTrue(result.allowed)
        assertFalse(result.permanentlyDenied)
    }

    @Test
    fun `a denial with the dialog still available is not permanent`() {
        val result =
            locationPermissionResult(
                granted = false,
                hasPermission = false,
                canAskAgain = true,
            )

        assertFalse(result.allowed)
        assertFalse(result.permanentlyDenied)
    }

    @Test
    fun `a denial with no dialog left is permanent`() {
        val result =
            locationPermissionResult(
                granted = false,
                hasPermission = false,
                canAskAgain = false,
            )

        assertFalse(result.allowed)
        assertTrue(result.permanentlyDenied)
    }

    @Test
    fun `the polled fallback ticks at the interval and stops on close`() =
        runTest {
            val tracker = FakeTracker()

            val handle = tracker.startTrackingWithPoll(this, onFix = {})
            assertTrue(tracker.trackingStarted)

            advanceTimeBy(14_000)
            assertEquals("nothing before the interval", 0, tracker.lastKnownCalls)

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals("one tick after the interval", 1, tracker.lastKnownCalls)

            handle.close()
            assertTrue(tracker.closed)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("the poll is gone", 1, tracker.lastKnownCalls)
        }
}

/** A tracker with no fixes of its own: the poll is the only caller. */
private class FakeTracker : LocationTracker {
    var trackingStarted = false
    var closed = false
    var lastKnownCalls = 0

    override suspend fun currentLocationOrNull(timeoutMs: Long): Location? = null

    override fun lastKnownOrNull(): Location? {
        lastKnownCalls++
        return null
    }

    override fun startTracking(
        minTimeMs: Long,
        minDistanceM: Float,
        onFix: (Location) -> Unit,
    ): AutoCloseable {
        trackingStarted = true
        return AutoCloseable { closed = true }
    }
}
