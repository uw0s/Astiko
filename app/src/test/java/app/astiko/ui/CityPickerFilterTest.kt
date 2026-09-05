package app.astiko.ui

import app.astiko.data.model.City
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CityPickerScreen's search is pure ([filterCities]), so the three-way
 * matching (localized display name, Greek label, Latin transliteration)
 * is unit-testable without Compose. These tests pin the rules that matter
 * in practice: a blank query shows the full list, matching is
 * case-insensitive and accent-insensitive ("βερ" finds Βέροια), and a
 * Latin query finds a Greek-named city on any UI language. English display
 * names are injected as a map instead of resources (plain JUnit has no
 * Android context).
 */
class CityPickerFilterTest {
    /** English display names, what a values-en device renders. Only the
     * cities exercised by the tests are listed. The rest get a sentinel
     * that can never match, since the filter probes every city's name. */
    private val enNames =
        mapOf(
            City.ATHENS to "Athens",
            City.THESSALONIKI to "Thessaloniki",
            City.VEROIA to "Veroia",
            City.ARTA to "Arta",
            City.CHIOS to "Chios",
            City.XANTHI to "Xanthi",
        )
    private val enName: (City) -> String = { enNames[it] ?: "x" }

    private val elName: (City) -> String = { it.label }

    private fun find(
        query: String,
        name: (City) -> String = enName,
    ) = filterCities(query, localizedName = name)

    // --- Blank query ------------------------------------------------------

    @Test
    fun blankQuery_returnsAllCitiesInPickerOrder() {
        assertEquals(City.pickerOrder, find(""))
    }

    @Test
    fun whitespaceQuery_returnsAllCities() {
        assertEquals(City.pickerOrder, find("   "))
    }

    // --- Greek label matching ----------------------------------------------

    @Test
    fun greekQuery_matchesGreekLabel() {
        // "βερ" is the start of Βέροια (Greek label is the source of truth).
        assertEquals(listOf(City.VEROIA), find("βερ"))
    }

    @Test
    fun unaccentedGreekQuery_matchesAccentedLabel() {
        // Greek typing often drops the tonos: "βεροια" and "αρτα" (the
        // unaccented forms of Βέροια / Άρτα) must still find their cities.
        assertEquals(listOf(City.VEROIA), find("βεροια"))
        assertEquals(listOf(City.ARTA), find("αρτα"))
    }

    @Test
    fun greekQuery_isCaseInsensitive() {
        assertEquals(listOf(City.ARTA), find("ΆΡΤΑ"))
    }

    @Test
    fun greekQuery_matchesAnywhereInTheName() {
        assertTrue(City.ARTA in find("ρτα"))
    }

    // --- Localized (English) name matching ----------------------------------

    @Test
    fun latinQuery_matchesEnglishDisplayNameOnEnglishUi() {
        // "ver" -> "Veroia" on a values-en device.
        assertEquals(listOf(City.VEROIA), find("ver"))
    }

    // --- Latin transliteration matching -------------------------------------

    @Test
    fun latinQuery_findsGreekCityOnGreekUi() {
        // A Latin query must still find Βέροια when the UI renders Greek
        // names. This is the transliteration table's only job.
        assertEquals(listOf(City.VEROIA), find("veroia", name = elName))
    }

    @Test
    fun latinAliasMatchesWithoutAccentOnGreekUi() {
        // "arta" (no accent in Latin) finds Άρτα.
        assertEquals(listOf(City.ARTA), find("arta", name = elName))
    }

    // --- No match -----------------------------------------------------------

    @Test
    fun noMatch_returnsEmptyList() {
        assertTrue(find("zzz-nothing").isEmpty())
    }

    // --- Latin transliteration table -----------------------------------------

    @Test
    fun everyCityHasANonBlankLatinAlias() {
        // A blank alias would silently make a city unreachable by Latin
        // search on a Greek UI. 26 entries, none blank.
        val aliases = City.entries.map { latinSearchAlias(it) }
        assertTrue(aliases.all { it.isNotBlank() })
        assertEquals(26, aliases.size)
    }
}
