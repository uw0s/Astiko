package app.astiko.ui

import app.astiko.R
import app.astiko.data.model.City
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CityLabels mappings are exhaustive `when` blocks, so the compiler
 * already guarantees one entry per city. These tests pin the mapping to
 * DISTINCT non-zero resource ids, so a copy-paste slip between cities
 * (or a string resource deleted by mistake) can't go unnoticed.
 */
class CityLabelsTest {
    @Test
    fun cityNameRes_mapsEveryCityToItsOwnResource() {
        val ids = City.entries.map { cityNameRes(it) }
        assertTrue(ids.all { it != 0 })
        assertEquals(26, ids.toSet().size)
        assertEquals(R.string.city_athens, cityNameRes(City.ATHENS))
        assertEquals(R.string.city_thessaloniki, cityNameRes(City.THESSALONIKI))
    }

    @Test
    fun operatorNameRes_mapsEveryCityToItsOwnResource() {
        val ids = City.entries.map { operatorNameRes(it) }
        assertTrue(ids.all { it != 0 })
        assertEquals(26, ids.toSet().size)
        assertEquals(R.string.op_oasa, operatorNameRes(City.ATHENS))
        assertEquals(R.string.op_oseth, operatorNameRes(City.THESSALONIKI))
        assertEquals(R.string.op_larissa, operatorNameRes(City.LARISSA))
    }

    @Test
    fun sourceSubtitleRes_mapsEveryCityToItsOwnResource() {
        val ids = City.entries.map { sourceSubtitleRes(it) }
        assertTrue(ids.all { it != 0 })
        assertEquals(26, ids.toSet().size)
        assertEquals(R.string.src_oasa, sourceSubtitleRes(City.ATHENS))
        assertEquals(R.string.src_larissa, sourceSubtitleRes(City.LARISSA))
    }

    @Test
    fun cityNameResources_areDistinctPerCity() {
        // Each city has its own resource id. The mappings point at
        // different resources per city (a duplicate id would mean two
        // cities render the same label). This only asserts the plumbing.
        // the real translations live in values-en/strings.xml.
        assertNotEquals(R.string.city_athens, R.string.city_larissa)
        assertTrue(R.string.city_athens != 0)
    }
}
