package app.astiko.data.oseth

import app.astiko.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class OseThWktTest {
    @Test
    fun twoPoints_parseInLatLonOrder() {
        val points = parseWkt("LINESTRING (23.7341 37.9757, 23.7300 37.9800)")
        assertEquals(
            listOf(GeoPoint(37.9757, 23.7341), GeoPoint(37.9800, 23.7300)),
            points,
        )
    }

    @Test
    fun singlePoint() {
        assertEquals(listOf(GeoPoint(37.9757, 23.7341)), parseWkt("LINESTRING (23.7341 37.9757)"))
    }

    @Test
    fun emptyString_returnsEmpty() {
        assertEquals(emptyList<GeoPoint>(), parseWkt(""))
    }

    @Test
    fun malformedPairs_areSkipped() {
        // Second pair has no latitude, so it is dropped. The first pair is kept.
        val points = parseWkt("LINESTRING (23.7341 37.9757, 23.7300)")
        assertEquals(listOf(GeoPoint(37.9757, 23.7341)), points)
    }

    @Test
    fun whitespaceVariations() {
        val points = parseWkt("LINESTRING (  23.7341   37.9757  ,  23.73 37.98 )")
        assertEquals(
            listOf(GeoPoint(37.9757, 23.7341), GeoPoint(37.98, 23.73)),
            points,
        )
    }
}
