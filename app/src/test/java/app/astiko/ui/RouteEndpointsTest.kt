package app.astiko.ui

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteEndpointsTest {
    private fun stop(id: String) = Stop(provider = Provider.OASA, id = id, name = "ΣΤΑΣΗ $id", lat = 0.0, lon = 0.0)

    // routeEndpoints -------------------------------------------------------

    @Test
    fun endpoints_emptyList_hasNoMarkers() {
        val (start, end) = routeEndpoints(emptyList())
        assertNull(start)
        assertNull(end)
    }

    @Test
    fun endpoints_singleStop_startOnly() {
        val (start, end) = routeEndpoints(listOf(stop("1")))
        assertEquals("1", start?.id)
        assertNull(end)
    }

    @Test
    fun endpoints_orderedList_firstAndLast() {
        val (start, end) = routeEndpoints(listOf(stop("1"), stop("2"), stop("3")))
        assertEquals("1", start?.id)
        assertEquals("3", end?.id)
    }

    @Test
    fun endpoints_loopRoute_startOnly() {
        // Circular line: the origin stop is also the last stop, so only one
        // marker, not two overlapping blobs.
        val (start, end) = routeEndpoints(listOf(stop("1"), stop("2"), stop("3"), stop("1")))
        assertEquals("1", start?.id)
        assertNull(end)
    }

    @Test
    fun endpoints_repeatedMidRouteId_keepsTerminus() {
        // A route may visit a stop twice without looping. The last stop
        // is still the terminus.
        val (start, end) = routeEndpoints(listOf(stop("1"), stop("2"), stop("3"), stop("2")))
        assertEquals("1", start?.id)
        assertEquals("2", end?.id)
    }

    // routeEndRole ---------------------------------------------------------

    @Test
    fun role_firstStop_isStart() {
        assertEquals(RouteEndRole.START, routeEndRole(listOf(stop("1"), stop("2"), stop("3")), "1"))
    }

    @Test
    fun role_lastStop_isEnd() {
        assertEquals(RouteEndRole.END, routeEndRole(listOf(stop("1"), stop("2"), stop("3")), "3"))
    }

    @Test
    fun role_middleStop_isPlainStop() {
        assertEquals(RouteEndRole.STOP, routeEndRole(listOf(stop("1"), stop("2"), stop("3")), "2"))
    }

    @Test
    fun role_unknownOrEmptyList_isPlainStop() {
        assertEquals(RouteEndRole.STOP, routeEndRole(emptyList(), "1"))
        assertEquals(RouteEndRole.STOP, routeEndRole(listOf(stop("1")), "99"))
    }

    @Test
    fun role_loopRoute_isStartNotEnd() {
        // The terminus marker is skipped for loops, so the selection
        // highlight must stay green, START not END.
        val stops = listOf(stop("1"), stop("2"), stop("1"))
        assertEquals(RouteEndRole.START, routeEndRole(stops, "1"))
    }

    @Test
    fun role_stopIdRepeatedAtEnd_isEnd() {
        // A route can visit the terminus stop twice. The marker sits on
        // the last occurrence and selection follows the id, so the
        // highlight matches the marker color.
        val stops = listOf(stop("1"), stop("2"), stop("3"), stop("2"))
        assertEquals(RouteEndRole.END, routeEndRole(stops, "2"))
    }

    @Test
    fun role_geoJsonNamesMatchMapExpressionKeys() {
        // The style's Expression.match keys are the geoJson strings.
        // renaming one without the other silently breaks the colors.
        assertEquals("start", RouteEndRole.START.geoJson)
        assertEquals("end", RouteEndRole.END.geoJson)
        assertEquals("stop", RouteEndRole.STOP.geoJson)
    }

    @Test
    fun role_badgeColors_matchMapMarkers() {
        // Badges and map dots must share the exact ARGB values. The map
        // derives its style hex from these same constants.
        assertEquals(ROUTE_START_ARGB, RouteEndRole.START.badgeArgb())
        assertEquals(ROUTE_END_ARGB, RouteEndRole.END.badgeArgb())
        assertNull(RouteEndRole.STOP.badgeArgb())
    }

    // scrollTargetIndex ----------------------------------------------------

    @Test
    fun scrollTarget_indexOfSingleOccurrence() {
        assertEquals(2, scrollTargetIndex(listOf(stop("1"), stop("2"), stop("3")), "3"))
    }

    @Test
    fun scrollTarget_loopRoute_firstOccurrenceWins() {
        // Circular line: the origin stop is visited twice, so the list
        // must land on the OUTBOUND visit (index 0), not the return leg.
        val stops = listOf(stop("1"), stop("2"), stop("3"), stop("1"))
        assertEquals(0, scrollTargetIndex(stops, "1"))
    }

    @Test
    fun scrollTarget_repeatedMidRouteStop_firstOccurrenceWins() {
        // A route may visit a mid-route stop twice (outbound + return).
        val stops = listOf(stop("1"), stop("2"), stop("3"), stop("2"))
        assertEquals(1, scrollTargetIndex(stops, "2"))
    }

    @Test
    fun scrollTarget_unknownStop_isMinusOne() {
        // A stale selection (stop no longer on the route after a
        // reload/direction switch) must not scroll anywhere.
        assertEquals(-1, scrollTargetIndex(listOf(stop("1"), stop("2")), "99"))
    }

    @Test
    fun scrollTarget_emptyList_isMinusOne() {
        assertEquals(-1, scrollTargetIndex(emptyList(), "1"))
    }
}
