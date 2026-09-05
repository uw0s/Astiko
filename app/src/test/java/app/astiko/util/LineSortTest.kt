package app.astiko.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineSortTest {
    @Test
    fun numericPrefixFirst() {
        // "01N" sorts right after "01" (prefix compared numerically),
        // and "10" beats "2" only numerically, not lexicographically.
        val sorted =
            listOf("Χ95", "10", "01N", "01", "02")
                .sortedWith(Comparator { a, b -> compareLineShortNames(a, b) })
        assertEquals(listOf("01", "01N", "02", "10", "Χ95"), sorted)
    }

    @Test
    fun sameNumber_ordersBySuffix() {
        assertTrue(compareLineShortNames("01", "01N") < 0)
        assertTrue(compareLineShortNames("01N", "01") > 0)
        assertEquals(0, compareLineShortNames("01", "01"))
    }

    @Test
    fun noNumericPrefix_sortsLast() {
        assertTrue(compareLineShortNames("01", "Χ95") < 0)
        // Letter-only names compare alphabetically among themselves.
        assertTrue(compareLineShortNames("Μ2", "Χ95") < 0)
    }

    @Test
    fun zeroPaddedAndPlain_compareBySuffix() {
        // "02" and "2" are the same route number. The tie is broken by the
        // raw string ("0" < "2"), so "02" sorts before "2".
        assertTrue(compareLineShortNames("02", "2") < 0)
        assertTrue(compareLineShortNames("2", "02") > 0)
    }
}
