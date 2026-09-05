package app.astiko.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityTest {
    @Test
    fun allCities_present() {
        assertEquals(26, City.entries.size)
    }

    @Test
    fun pickerOrder_largestPopulationFirst() {
        val order = City.pickerOrder
        assertEquals(26, order.size)
        assertEquals(City.ATHENS, order[0]) // 3.1M, always first
        assertEquals(City.THESSALONIKI, order[1]) // 802k, always second
        assertTrue(order.zipWithNext().all { (a, b) -> a.population >= b.population })
        // Same set as the declaration order, just sorted.
        assertEquals(City.entries.toSet(), order.toSet())
    }

    @Test
    fun labels_areUnique() {
        assertEquals(
            26,
            City.entries
                .map { it.label }
                .toSet()
                .size,
        )
    }

    @Test
    fun citybusCities_mapToDistinctProviders() {
        val citybusCities = City.entries.filter { it.provider.name.startsWith("CITYBUS") }
        assertEquals(24, citybusCities.size)
        assertEquals(24, citybusCities.map { it.provider }.toSet().size)
        assertTrue(citybusCities.all { it.provider != Provider.CITYBUS || it == City.LARISSA })
    }

    @Test
    fun everyCity_hasCoordinates() {
        City.entries.forEach { city ->
            assertTrue("${city.name} lat", city.lat in 34.0..42.0)
            assertTrue("${city.name} lon", city.lon in 19.0..27.0)
        }
    }

    // ---------- nearestCity ----------

    @Test
    fun nearestCity_athensArea() {
        // Syntagma: 0 km from ATHENS' center, ~300 km from the next.
        assertEquals(City.ATHENS, City.nearestCity(37.9757, 23.7341))
        // Elefsina (west of Athens) is 8.5 km across the bay from Salamina.
        // the island city wins, not Athens (18 km).
        assertEquals(City.SALAMINA, City.nearestCity(38.0411, 23.5422))
    }

    @Test
    fun nearestCity_thessalonikiArea() {
        assertEquals(City.THESSALONIKI, City.nearestCity(40.6329, 22.9398))
        // Kalamaria: still Thessaloniki's basin.
        assertEquals(City.THESSALONIKI, City.nearestCity(40.5825, 22.9512))
    }

    @Test
    fun nearestCity_larissaArea() {
        assertEquals(City.LARISSA, City.nearestCity(39.6387, 22.4161))
    }

    @Test
    fun nearestCity_otherCitybusCities() {
        assertEquals(City.PATRA, City.nearestCity(38.2459, 21.7358))
        assertEquals(City.XANTHI, City.nearestCity(41.1369, 24.8868))
        assertEquals(City.KOZANI, City.nearestCity(40.3021, 21.7882))
    }
}
