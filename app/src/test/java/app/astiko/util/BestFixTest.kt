package app.astiko.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * bestFix, the pure last-known selection seam. The production path used
 * to take the first fix in a fixed GPS, network order, so an hour-old
 * GPS fix beat a fresh network fix. The rules:
 * 1. Fixes older than maxAgeMs are rejected entirely.
 * 2. The newest fresh fix wins.
 * 3. Ties (equal timestamps) fall to accuracy. Lower is better,
 *    unreported/negative accuracy ranks last.
 */
class BestFixTest {
    private fun fix(
        provider: String,
        timeMs: Long,
        lat: Double = 0.0,
        lon: Double = 0.0,
        accuracy: Float? = null,
    ) = LocationFix(provider, timeMs, lat, lon, accuracy)

    private val now = 1_000_000L
    private val maxAge = 15 * 60 * 1000L // 15 min

    @Test
    fun `picks the newest fresh fix`() {
        val fresh = fix("network", timeMs = now - 60_000)
        val fresher = fix("gps", timeMs = now - 5_000)
        assertEquals(fresher, bestFix(listOf(fresh, fresher), now, maxAge))
    }

    @Test
    fun `rejects stale fixes entirely - fresh network beats ancient gps`() {
        val staleGps = fix("gps", timeMs = now - 3 * 60 * 60 * 1000) // 3 h old
        val freshNetwork = fix("network", timeMs = now - 10_000)
        // A GPS fix used to win by provider order. The rule
        // rejects it as too old and serves the fresh network fix.
        assertEquals(freshNetwork, bestFix(listOf(staleGps, freshNetwork), now, maxAge))
    }

    @Test
    fun `no fresh fix returns null`() {
        assertNull(bestFix(listOf(fix("gps", timeMs = now - maxAge - 1)), now, maxAge))
        assertNull(bestFix(emptyList(), now, maxAge))
    }

    @Test
    fun `exactly at the age limit is still fresh`() {
        val edge = fix("gps", timeMs = now - maxAge)
        assertEquals(edge, bestFix(listOf(edge), now, maxAge))
    }

    @Test
    fun `equal timestamps - better accuracy wins`() {
        val t = now - 60_000
        val coarse = fix("network", t, accuracy = 500f)
        val precise = fix("gps", t, accuracy = 10f)
        assertEquals(precise, bestFix(listOf(coarse, precise), now, maxAge))
    }

    @Test
    fun `equal timestamps - unreported accuracy ranks last`() {
        val t = now - 60_000
        val unknown = fix("network", t, accuracy = null)
        val reported = fix("gps", t, accuracy = 100f)
        assertEquals(reported, bestFix(listOf(unknown, reported), now, maxAge))
    }

    @Test
    fun `negative accuracy is treated as unreported`() {
        val t = now - 60_000
        val negative = fix("network", t, accuracy = -1f)
        val reported = fix("gps", t, accuracy = 50f)
        assertEquals(reported, bestFix(listOf(negative, reported), now, maxAge))
    }
}
