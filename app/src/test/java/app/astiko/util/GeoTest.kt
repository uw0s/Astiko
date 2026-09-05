package app.astiko.util

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    // Reference points (Athens Syntagma / Thessaloniki / Larissa).
    private val syntagma =
        Stop(provider = Provider.OASA, id = "1", name = "ΣΥΝΤΑΓΜΑ", lat = 37.9757, lon = 23.7341)
    private val thessaloniki =
        Stop(
            provider = Provider.OSETh,
            id = "2",
            name = "ΘΕΣΣΑΛΟΝΙΚΗ",
            lat = 40.6329,
            lon = 22.9398,
        )
    private val larissa =
        Stop(provider = Provider.CITYBUS, id = "3", name = "ΛΑΡΙΣΑ", lat = 39.6387, lon = 22.4161)

    @Test
    fun haversine_samePoint_isZero() {
        assertEquals(0.0, haversineKm(37.9757, 23.7341, 37.9757, 23.7341), 0.0001)
    }

    @Test
    fun haversine_knownCityDistances() {
        // Straight-line distances, computed independently (great-circle).
        assertEquals(
            303.3,
            haversineKm(syntagma.lat, syntagma.lon, thessaloniki.lat, thessaloniki.lon),
            1.0,
        )
        assertEquals(217.3, haversineKm(syntagma.lat, syntagma.lon, larissa.lat, larissa.lon), 1.0)
    }

    @Test
    fun haversine_symmetric() {
        val d1 = haversineKm(syntagma.lat, syntagma.lon, larissa.lat, larissa.lon)
        val d2 = haversineKm(larissa.lat, larissa.lon, syntagma.lat, syntagma.lon)
        assertEquals(d1, d2, 1e-9)
    }

    @Test
    fun bearing_cardinalDirections() {
        // North: same lon, higher lat. South: same lon, lower lat.
        assertEquals(0.0, bearingDegrees(37.9757, 23.7341, 37.9857, 23.7341), 0.5)
        assertEquals(180.0, bearingDegrees(37.9857, 23.7341, 37.9757, 23.7341), 0.5)
        // East / west along the same latitude.
        assertEquals(90.0, bearingDegrees(37.9757, 23.7341, 37.9757, 23.7441), 0.5)
        assertEquals(270.0, bearingDegrees(37.9757, 23.7441, 37.9757, 23.7341), 0.5)
    }

    @Test
    fun isAcrossRoad_closeOppositeStops_isTrue() {
        // Two stops ~30 m apart, north/south of each other, a street pair.
        val north = syntagma.copy(lat = syntagma.lat + 0.00027, id = "north")
        assertTrue(isAcrossRoad(syntagma, north))
    }

    @Test
    fun isAcrossRoad_tooFarApart_isFalse() {
        val far = syntagma.copy(lat = syntagma.lat + 0.003, id = "far") // ~330 m
        assertFalse(isAcrossRoad(syntagma, far))
    }

    @Test
    fun cardinalGreek_sectors() {
        assertEquals("Β", cardinalGreek(0.0))
        assertEquals("ΒΑ", cardinalGreek(45.0))
        assertEquals("Α", cardinalGreek(90.0))
        assertEquals("ΝΑ", cardinalGreek(135.0))
        assertEquals("Ν", cardinalGreek(180.0))
        assertEquals("ΝΔ", cardinalGreek(225.0))
        assertEquals("Δ", cardinalGreek(270.0))
        assertEquals("ΒΔ", cardinalGreek(315.0))
        // Rounding: just under a sector boundary stays in the previous sector.
        assertEquals("Β", cardinalGreek(359.0))
    }

    // Four stops along one street, ~85 m apart (lon steps 0.001°).
    private fun streetStops(): List<Stop> =
        (0 until 4).map { i ->
            Stop(
                provider = Provider.OASA,
                id = "street-$i",
                name = "STOP$i",
                lat = 37.9760,
                lon = 23.7300 + 0.001 * i,
            )
        }

    @Test
    fun busBetweenStops_midSegment() {
        // Midway between stops 1 and 2.
        assertEquals(1 to 2, busBetweenStops(streetStops(), 37.9760, 23.7315))
    }

    @Test
    fun busBetweenStops_justPastStop_staysOnThatLeg() {
        // ~20 m past stop 1, still on the 1-2 leg.
        assertEquals(1 to 2, busBetweenStops(streetStops(), 37.9760, 23.7312))
    }

    @Test
    fun busBetweenStops_beforeFirstStop_clampsToFirstLeg() {
        assertEquals(0 to 1, busBetweenStops(streetStops(), 37.9760, 23.7290))
    }

    @Test
    fun busBetweenStops_afterLastStop_clampsToLastLeg() {
        assertEquals(2 to 3, busBetweenStops(streetStops(), 37.9760, 23.7340))
    }

    @Test
    fun busBetweenStops_offRoute_picksNearestLeg() {
        // ~200 m off the street, level with the 1-2 leg's midpoint.
        assertEquals(1 to 2, busBetweenStops(streetStops(), 37.9742, 23.7315))
    }

    @Test
    fun busBetweenStops_loopRoute_closingLeg() {
        // Real loop: the closing leg runs on a different street, so the
        // repeated origin sits ~330 m north of the outbound line.
        val loop =
            listOf(
                streetStops()[0],
                streetStops()[1],
                streetStops()[2],
                streetStops()[0].copy(lat = 37.9790, id = "street-0-again"),
            )
        assertEquals(2 to 3, busBetweenStops(loop, 37.9775, 23.7310))
    }

    @Test
    fun busBetweenStops_fewerThanTwoStops_isNull() {
        assertNull(busBetweenStops(emptyList(), 37.9760, 23.7300))
        assertNull(busBetweenStops(streetStops().take(1), 37.9760, 23.7300))
    }
}
