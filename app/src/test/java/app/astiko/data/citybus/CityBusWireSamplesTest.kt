package app.astiko.data.citybus

import app.astiko.data.loadSample
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Wire-faithful tests: real CityBus (Larissa) responses captured against
 * the live API (see ../resources/samples/citybus/). The samples were
 * captured from the English endpoints, so names are Latin. The point is
 * the shapes and the quirks, not the strings.
 */
class CityBusWireSamplesTest {
    private val provider = Provider.CITYBUS

    private fun repo(lang: String = "en") =
        CityBusRepository(
            FakeCityBusApi().apply {
                stops = loadSample("citybus", "samples_stops.json")
                routes = loadSample("citybus", "samples_routes.json")
                lines = loadSample("citybus", "samples_lines.json")
                stopLive = loadSample("citybus", "samples_live.json")
                stopTrips = loadSample("citybus", "samples_trips.json")
                routeSequence = loadSample("citybus", "samples_route_sequence.json")
                linePoints = loadSample("citybus", "samples_line_points.json")
            },
            testJson,
            agency = "102",
            langProvider = { lang },
            provider = provider,
        )

    @Test
    fun stopsNear_realSample() =
        runBlocking {
            val stops = repo().getStopsNear(39.6444094, 22.4105631, 20) // stop 0356's own coords

            assertEquals(12, stops.size)
            assertEquals("0356", stops[0].id)
            assertEquals("14 PRIMARY SCHOOL", stops[0].name)
            assertEquals(0.0, stops[0].distanceKm!!, 1e-9)
            assertEquals(listOf("03", "05", "07"), stops[0].servingLines)
            assertTrue(stops.zipWithNext().all { (a, b) -> a.distanceKm!! <= b.distanceKm!! })
        }

    @Test
    fun stopRoutes_realSample() =
        runBlocking {
            val lines = repo().getStopRoutes("0304")

            // Route 013 is the only one of stop 0304's routeCodes present in
            // the routes sample. The join drops the rest.
            assertEquals(1, lines.size)
            assertEquals("013", lines[0].id)
            assertEquals("03", lines[0].shortName)
            assertEquals("ALKAZAR - AVEROF", lines[0].longName)
            assertEquals("ALKAZAR - PRAKTIKER", lines[0].destination)

            // Stop 0356's routeCodes (014, 016, ...) are not in the sample's 12
            // routes. A defensive empty result, not a crash.
            assertEquals(0, repo().getStopRoutes("0356").size)
        }

    @Test
    fun lines_realSample_lineWithoutRoutesHidden() =
        runBlocking {
            val lines = repo().getLines()

            // 12 lines on the wire. "12" has no routes (suspended), so it
            // is hidden.
            assertEquals(11, lines.size)
            assertFalse(lines.any { it.shortName == "12" })
            assertEquals("01", lines[0].shortName)
            assertEquals("NEA SMYRNI - NEAPOLI", lines[0].longName)
        }

    @Test
    fun lineVariants_realSample() =
        runBlocking {
            val line = Line(provider, "01", "01", "NEA SMYRNI - NEAPOLI")
            val variants = repo().getLineVariants(line)

            // Line 01 has 8 direction routes on the wire.
            assertEquals(8, variants.size)
            assertEquals("001", variants[0].id)
            assertEquals("NEA SMYRNI - NEAPOLI", variants[0].label)
            assertEquals("01", variants[0].lineId)
        }

    @Test
    fun variantStops_realSample_sequenceCodesJoinCatalog() =
        runBlocking {
            val variant = LineVariant(provider, "01", "001", "NEA SMYRNI - NEAPOLI", "01")

            // The route-sequence sample lists stops 0101..0112, but the stop
            // catalog sample covers other codes. Nothing joins, so the result
            // is empty. This pins the join semantics (no crash on unknown codes).
            assertEquals(0, repo().getVariantStops(variant).size)
        }

    @Test
    fun routeGeometry_realSample_stringCoordinates() =
        runBlocking {
            val variant = LineVariant(provider, "01", "001", "NEA SMYRNI - NEAPOLI", "01")
            val points = repo().getRouteGeometry(variant)

            assertEquals(8, points.size) // route 001 has 8 points on the wire
            assertEquals(39.65943888004347, points[0].lat, 1e-12)
            assertEquals(22.439119212303723, points[0].lon, 1e-12)
        }

    @Test
    fun arrivals_realSample_gpsAndZeroZeroVehicles() =
        runBlocking {
            val arrivals = repo().observeArrivals("0116", emptyList()).first()

            // Two buses on the wire: one with GPS, one with "0"/"0" (no fix).
            assertEquals(2, arrivals.size)
            assertEquals(listOf(10, 25), arrivals.map { it.etaMinutes })

            val withGps = arrivals[0]
            assertEquals("03", withGps.lineShortName)
            assertEquals("BIOKARPET - ALKAZAR", withGps.destination)
            assertEquals("20260802_016_0000001_11_30", withGps.tripId) // per-TRIP code
            assertEquals(39.630403, withGps.vehicle!!.lat, 1e-9)
            assertEquals(22.419633, withGps.vehicle.lon, 1e-9)

            // The real "0"/"0" quirk: arrival survives, position does not.
            assertEquals(25, arrivals[1].etaMinutes)
            assertNull(arrivals[1].vehicle)
        }

    @Test
    fun stopTimetable_realSample_mondayTrips() =
        runBlocking {
            val api = FakeCityBusApi()
            val r =
                CityBusRepository(
                    api.apply { stopTrips = loadSample("citybus", "samples_trips.json") },
                    testJson,
                    "102",
                    { "en" },
                    provider,
                )

            val entries = r.getStopTimetable("0116", DayOfWeek.MONDAY)

            // 15 trips, all captured for day 1 = Monday. The API day param must
            // receive Monday's 1 (not DayOfWeek's 1. Same here, but the
            // Sunday-to-0 mapping is covered by the hand-built tests).
            assertEquals(listOf("0116" to 1), api.stopTripsCalls)
            assertEquals(15, entries.size)
            assertEquals("06:01", entries[0].departureTime)
            assertEquals("08", entries[0].lineShortName)
            assertEquals("ODEIO - NIKAIA", entries[0].destination)
            assertEquals("58743686", entries[0].tripId)
        }
}
