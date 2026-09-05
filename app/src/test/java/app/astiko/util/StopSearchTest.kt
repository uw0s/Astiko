package app.astiko.util

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranked stop search is pure ([rankStopSearch]), so the matching
 * rules are unit-testable without Compose or a provider. These pin the
 * rules that matter in practice: accent/case-insensitive matching ("αγια"
 * finds "ΑΓΙΑ", "agias" is not matched because the data is Greek), name
 * prefix before name contains before code prefix, alphabetical ties, and
 * the result cap.
 */
class StopSearchTest {
    private fun stop(
        id: String,
        name: String,
    ) = Stop(provider = Provider.CITYBUS, id = id, name = name, lat = 0.0, lon = 0.0)

    private fun search(
        query: String,
        vararg stops: Stop,
        limit: Int = 30,
    ) = rankStopSearch(stops.toList(), query, limit).map { it.id }

    // --- Matching ---------------------------------------------------------

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(search("", stop("1", "ΚΕΝΤΡΟ")).isEmpty())
        assertTrue(search("   ", stop("1", "ΚΕΝΤΡΟ")).isEmpty())
    }

    @Test
    fun matchingIsCaseAndAccentInsensitive() {
        // "συνταγμα" finds "ΣΥΝΤΑΓΜΑ" (no accents in query, accents in name).
        assertEquals(listOf("1"), search("συνταγμα", stop("1", "ΣΥΝΤΑΓΜΑ")))
        // Uppercase query, lowercase name.
        assertEquals(listOf("2"), search("ΣΟΦΙΑ", stop("2", "αγίας σοφίας")))
    }

    @Test
    fun greekQueryDoesNotMatchLatinTransliteration() {
        // The English mode serves transliterations (KLEISOBHS), but a Greek
        // query must not match them: the search runs on the localized name.
        assertTrue(search("κλεισοβης", stop("1", "KLEISOBHS")).isEmpty())
    }

    @Test
    fun stopCodeMatchesAsLastResort() {
        // Name contains nothing for "011", but the code prefix matches.
        assertEquals(listOf("0116"), search("011", stop("0116", "ΚΕΝΤΡΟ")))
    }

    // --- Ranking ----------------------------------------------------------

    @Test
    fun prefixRanksBeforeContains() {
        val result =
            search(
                "αγια",
                stop("prefix", "ΑΓΙΑΣ ΣΟΦΙΑΣ"),
                stop("contains", "ΠΛ. ΑΓΙΑΣ ΣΟΦΙΑΣ"),
            )
        assertEquals(listOf("prefix", "contains"), result)
    }

    @Test
    fun containsRanksBeforeCode() {
        val result =
            search(
                "σοφια",
                stop("σοφια-2", "ΚΕΝΤΡΙΚΟ"),
                stop("contains", "ΠΛ. ΑΓΙΑΣ ΣΟΦΙΑΣ"),
            )
        // The name match (rank 1) beats the code-prefix match (rank 2).
        assertEquals(listOf("contains", "σοφια-2"), result)
    }

    @Test
    fun equalRankTiesBreakAlphabetically() {
        val result =
            search(
                "δημαρχ",
                stop("b", "ΔΗΜΑΡΧΕΙΟ"),
                stop("a", "ΔΗΜΑΡΧΕΙΟ"),
            )
        // Same rank and same name: the id is the final key, so the order
        // is deterministic instead of input order.
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun limitCapsResults() {
        val stops = List(50) { stop(it.toString(), "ΚΕΝΤΡΟ $it") }
        assertEquals(30, search("κεντρο", *stops.toTypedArray()).size)
        assertEquals(5, search("κεντρο", *stops.toTypedArray(), limit = 5).size)
    }

    @Test
    fun nonMatchingQueryReturnsNothing() {
        assertTrue(search("ζωοδοχος", stop("1", "ΣΥΝΤΑΓΜΑ"), stop("2", "ΟΜΟΝΟΙΑ")).isEmpty())
    }

    // --- Normalization -----------------------------------------------------

    @Test
    fun normalizeStripsCombiningMarksAndLowercases() {
        assertEquals("αγιας σοφιας", normalizeSearchText("ΑΓΙΑΣ ΣΟΦΙΑΣ"))
        assertEquals("κεντρο", normalizeSearchText("ΚΕΝΤΡΟ"))
    }
}
