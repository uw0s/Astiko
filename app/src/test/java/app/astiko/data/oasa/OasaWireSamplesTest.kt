package app.astiko.data.oasa

import app.astiko.data.loadSample
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-faithful tests: real OASA responses captured against the live API
 * (see ../resources/samples/oasa/). These pin the DTO field names to what
 * the server actually sends. The hand-built JSON tests only prove the
 * adapter logic, not the shapes.
 */
class OasaWireSamplesTest {
    private fun repo(lang: String = "el") =
        OasaRepository(
            FakeOasaApi().apply {
                closestStops = loadSample("oasa", "samples_closest_stops.json")
                masterLines = loadSample("oasa", "samples_master_lines.json")
                lines = loadSample("oasa", "samples_lines.json")
                routesForLine = { loadSample("oasa", "samples_routes_for_line.json") }
                routesForStop = loadSample("oasa", "samples_routes_for_stop.json")
                stopArrivals = loadSample("oasa", "samples_stop_arrivals.json")
                busLocation = { loadSample("oasa", "samples_bus_location.json") }
                stopsForRoute = loadSample("oasa", "samples_stops.json")
                routeDetails = loadSample("oasa", "samples_route_details_stops.json")
            },
            testJson,
            { lang },
        )

    @Test
    fun closestStops_realSample() =
        runBlocking {
            val stops = repo().getStopsNear(37.9837048, 23.7349122, 10) // stop 60010's own coords

            assertTrue(stops.isNotEmpty())
            // Nearest to the capture point is first. Real transliterations apply.
            assertEquals("60010", stops[0].id)
            assertEquals("ΝΑΥΑΡΙΝΟΥ", stops[0].name)
            assertEquals("ΧΑΡ.ΤΡΙΚΟΥΠΗ", stops[0].street) // StopStreetEng is null on the wire
            assertEquals(0.0, stops[0].distanceKm!!, 1e-6)
            // Sorted by distance, badges enriched from the routes-for-stop sample.
            assertTrue(stops.zipWithNext().all { (a, b) -> a.distanceKm!! <= b.distanceKm!! })
            assertTrue(stops[0].servingLines.contains("3"))
        }

    @Test
    fun closestStops_english_transliteratedNames() =
        runBlocking {
            val stops = repo(lang = "en").getStopsNear(37.9837048, 23.7349122, 5)
            assertEquals("NAYARINOY", stops[0].name)
        }

    @Test
    fun masterLines_realSample() =
        runBlocking {
            val lines = repo().getLines()

            val line021 = lines.firstOrNull { it.shortName == "021" }
            assertEquals("1574", line021?.id) // line_code, not the public number
            assertEquals("ΚΑΝΙΓΓΟΣ - ΓΚΥΖΗ", line021?.longName)
            // Numerically sorted (ml_id "021" < "022" < "024" < ...).
            assertTrue(lines.zipWithNext().all { (a, b) -> a.shortName <= b.shortName })
        }

    @Test
    fun masterLines_english_fallsBackToGreekWhenEngNull() =
        runBlocking {
            // ml_descr_eng is null in the sample, so the Greek description must survive.
            val line021 = repo(lang = "en").getLines().firstOrNull { it.shortName == "021" }
            assertEquals("ΚΑΝΙΓΓΟΣ - ΓΚΥΖΗ", line021?.longName)
        }

    @Test
    fun lineVariants_realSample_allLineEntriesCollected() =
        runBlocking {
            // webGetLines has THREE entries for line 021 (one per direction
            // variant). The adapter must collect the routes of all of them.
            val line = Line(Provider.OASA, "1574", "021", "ΚΑΝΙΓΓΟΣ - ΓΚΥΖΗ")
            val variants = repo().getLineVariants(line)

            assertEquals(setOf("5512", "5535", "5513"), variants.map { it.id }.toSet())
            assertEquals(3, variants.size) // 3 entries × 3 routes, deduped by route code
            assertTrue(variants[0].label.startsWith("ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ"))
        }

    @Test
    fun stopArrivals_realSample() =
        runBlocking {
            val arrivals = repo().observeArrivals("60010", emptyList()).first()

            // Six buses, sorted by minutes. veh_code survives as vehicleId.
            assertEquals(
                listOf("5346", "5369", "2052", "3609", "2052", "2052"),
                arrivals.map { it.routeCode },
            )
            assertEquals(listOf(1, 3, 7, 15, 19, 34), arrivals.map { it.etaMinutes })
            assertEquals("61219", arrivals[0].vehicleId)
            // The bus-location sample is a different capture (route 5512, VEH_NO
            // 44533...) so no vehicle ids match, and positions stay null. The join
            // itself is covered by the hand-built tests.
            assertNull(arrivals[0].vehicle)
        }

    @Test
    fun busLocation_realSample() =
        runBlocking {
            val variant = LineVariant(Provider.OASA, "1574", "5512", "ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ", "021")
            val vehicles = repo().observeVehicles(variant).first()

            assertTrue(vehicles.isNotEmpty())
            assertEquals("44533", vehicles[0].vehicleId)
            assertEquals(37.937961, vehicles[0].lat, 1e-6)
            assertEquals(23.633801, vehicles[0].lon, 1e-6)
            assertEquals(113f, vehicles[0].heading!!, 1e-6f)
        }

    @Test
    fun variantStops_realSample() =
        runBlocking {
            val variant = LineVariant(Provider.OASA, "1574", "5512", "ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ", "021")
            val stops = repo().getVariantStops(variant)

            assertTrue(stops.isNotEmpty())
            assertEquals("10183", stops[0].id)
            assertEquals("ΠΕΙΡΑΙΑΣ", stops[0].name)
            assertEquals(37.9384348, stops[0].lat, 1e-6)
        }

    @Test
    fun routeGeometry_realSample() =
        runBlocking {
            val variant = LineVariant(Provider.OASA, "1574", "5512", "ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ", "021")
            val points = repo().getRouteGeometry(variant)

            assertEquals(6, points.size)
            assertEquals(GeoPoint(37.93848, 23.63165), points[0])
        }

    @Test
    fun stopRoutes_realSample() =
        runBlocking {
            val lines = repo().getStopRoutes("60010")

            assertTrue(lines.isNotEmpty())
            assertEquals("1990", lines[0].id) // RouteCode
            assertEquals("3", lines[0].shortName) // LineID
            assertEquals("ΝΕΟ ΨΥΧΙΚΟ - ΑΝΩ ΠΑΤΗΣΙΑ - Ν. ΦΙΛΑΔΕΛΦΕΙΑ", lines[0].destination)
            assertEquals("Ν. ΦΙΛΑΔΕΛΦΕΙΑ - ΑΝΩ ΠΑΤΗΣΙΑ - ΝΕΟ ΨΥΧΙΚΟ", lines[0].longName)
        }
}
