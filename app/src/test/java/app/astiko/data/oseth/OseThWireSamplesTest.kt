package app.astiko.data.oseth

import app.astiko.data.loadSample
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Wire-faithful tests: real OSETh responses captured against the live API
 * (see ../resources/samples/oseth/). The samples include the full
 * {"data": ...} envelope, so the adapter's envelope handling is exercised
 * with real bytes.
 */
class OseThWireSamplesTest {
    private fun wireApi() =
        FakeOseThApi().apply {
            nearby = loadSample("oseth", "samples_nearby.json")
            routes = loadSample("oseth", "samples_routes.json")
            stopInfo = loadSample("oseth", "samples_stop_info.json")
            routeInfo = loadSample("oseth", "samples_route_info.json")
            stopTimetableResponse = loadSample("oseth", "samples_stop_timetable.json")
            routeTimetableResponse = loadSample("oseth", "samples_route_timetable.json")
        }

    @Test
    fun nearby_realSample() =
        runBlocking {
            val stops =
                OseThRepository(
                    wireApi(),
                    testJson,
                    { "el" },
                ).getStopsNear(40.63965, 22.94516, 10)

            assertTrue(stops.isNotEmpty())
            assertEquals("1482", stops[0].id)
            assertEquals("ΔΙΟΙΚΗΤΗΡΙΟ", stops[0].name)
            assertEquals(40.63965, stops[0].lat, 1e-9)
            assertEquals(0.077, stops[0].distanceKm!!, 1e-9) // 77 m on the wire to km
            assertEquals(listOf("22"), stops[0].servingLines)
            assertTrue(
                stops.zipWithNext().all { (a, b) ->
                    (a.distanceKm ?: 0.0) <=
                        (b.distanceKm ?: 0.0)
                },
            )
        }

    @Test
    fun routesAndVariants_realSample() =
        runBlocking {
            val api = wireApi()
            val repo = OseThRepository(api, testJson, { "el" })

            val lines = repo.getLines()
            val line01 = lines.firstOrNull { it.shortName == "01" }
            assertEquals("01_7429_1_3", line01?.id)
            assertTrue(lines.zipWithNext().all { (a, b) -> a.shortName <= b.shortName })

            val variants = repo.getLineVariants(line01!!)
            assertEquals(3, variants.size)
            // Real headsigns with their shape ids (5304/5300 weekday, 5284 weekend).
            assertEquals("01_7429_1_3", variants[0].id)
            assertEquals("5304", variants[0].shapeId)
            assertEquals("5284", variants[2].shapeId)
            assertTrue(variants[0].label.contains("Τ.Σ. ΕΥΚΑΡΠΙΑΣ"))
        }

    @Test
    fun stopInfoAndRoutes_realSample() =
        runBlocking {
            val lines = OseThRepository(wireApi(), testJson, { "el" }).getStopRoutes("36108")

            assertTrue(lines.isNotEmpty())
            assertEquals("87Q_7011_2_3", lines[0].id)
            assertEquals("87Q", lines[0].shortName)
            assertTrue(lines.any { it.shortName == "87M" })
        }

    @Test
    fun routeInfo_realSample_stopsGeometryVehicles() =
        runBlocking {
            val repo = OseThRepository(wireApi(), testJson, { "el" })
            val variant =
                LineVariant(
                    Provider.OSETh,
                    "01_7429_1_3",
                    "01_7429_1_3",
                    "5300",
                    "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.",
                    "01",
                )

            val stops = repo.getVariantStops(variant)
            assertEquals(listOf("11048", "11046", "11047"), stops.map { it.id })
            assertEquals("Τ.Σ. ΕΥΚΑΡΠΙΑΣ - ΣΚΛΑΒΕΝΙΤΗΣ", stops[0].name)
            assertEquals(40.67944, stops[0].lat, 1e-9)

            // WKT geometry from the real shape.
            val points = repo.getRouteGeometry(variant)
            assertTrue(points.size > 50)
            assertEquals(40.67949, points[0].lat, 1e-6)
            assertEquals(22.95748, points[0].lon, 1e-6)

            // The sample's vehicle list is empty (captured off-service). The
            // adapter must emit an empty list, not fail.
            assertEquals(0, repo.observeVehicles(variant).first().size)
        }

    @Test
    fun stopTimetable_realSample_liveTripsOnly() =
        runBlocking {
            val repo = OseThRepository(wireApi(), testJson, { "el" })

            // Three trips on the wire. Only one is monitored (the other two
            // are the remaining day's schedule and must not leak into arrivals).
            val arrivals = repo.observeArrivals("36108", emptyList()).first()
            assertEquals(1, arrivals.size)
            assertEquals(listOf(1), arrivals.map { it.etaMinutes })
            assertEquals("37243674", arrivals[0].tripId)
            assertEquals("ΒΑΣΙΛΙΚΑ - ΑΓ.ΑΝΤΩΝΗΣ - ΡΥΣΙΟ - ΙΚΕΑ", arrivals[0].destination)
            assertEquals("87N", arrivals[0].lineShortName)
            assertEquals("19:41", arrivals[0].scheduledTime)
            // "HH:mm:ss" to "HH:mm" (model contract)
            // The arriving bus's live position, straight from the trip.
            assertEquals("4030", arrivals[0].vehicle!!.vehicleId)
            assertEquals(40.48400879, arrivals[0].vehicle!!.lat, 1e-9)
            assertEquals(268.6000061f, arrivals[0].vehicle!!.heading!!, 1e-4f)

            // Same endpoint, timetable view: the full remaining-day schedule.
            val entries = repo.getStopTimetable("36108", DayOfWeek.MONDAY)
            assertEquals(3, entries.size)
            assertEquals("19:41", entries[0].departureTime) // HH:mm:ss to HH:mm
        }

    @Test
    fun routeTimetable_realSample() =
        runBlocking {
            val api = wireApi()
            val repo = OseThRepository(api, testJson, { "el" })
            val variant =
                LineVariant(
                    Provider.OSETh,
                    "01_7429_1_3",
                    "01_7429_1_3",
                    "5300",
                    "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.",
                    "01",
                )

            val entries = repo.getLineTimetable(variant, DayOfWeek.MONDAY)

            // Five scheduled departures, including non-monitored ones (the
            // timetable keeps everything. Only arrivals filter on `monitored`).
            assertEquals(5, entries.size)
            assertEquals("08:10", entries[0].departureTime)
            assertEquals("01", entries[0].lineShortName)
            assertEquals("5300", api.lastRouteTimetableShapeId)
        }
}
