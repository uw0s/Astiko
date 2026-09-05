package app.astiko.data.citybus

import app.astiko.data.jsonArr
import app.astiko.data.jsonObj
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.VehiclePosition
import app.astiko.data.testJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.DayOfWeek

/** Hand-rolled CityBusApi: records calls, feeds wire-shaped JSON, can throw HTTP errors. */
class FakeCityBusApi : CityBusApi {
    var stops: JsonElement = jsonArr()
    var lines: JsonElement = jsonArr()
    var routes: JsonElement = jsonArr()
    var linePoints: JsonElement = jsonArr()
    var routeSequence: JsonElement = jsonArr()
    var stopLive: JsonElement = jsonArr()
    var stopTrips: JsonElement = jsonArr()

    var stopLiveError: HttpException? = null
    var stopTripsError: HttpException? = null

    /** Non-HTTP failures (IOException, CancellationException...) for the
     *  catalog endpoints. Exercises the "a failed or cancelled fetch must
     *  not poison the catalog" contract. */
    var stopsError: Throwable? = null
    var routesError: Throwable? = null
    var linesError: Throwable? = null

    /** Failures for the per-route endpoints. Exercises the "failures
     *  propagate to the screens' error state" contract, never a silent
     *  empty result. */
    var routeSequenceError: Throwable? = null
    var linePointsError: Throwable? = null

    /** When set, [stops] suspends on it. Lets a test hold the catalog
     *  fetch open and fire concurrent callers at the shared-fetch join. */
    var stopsGate: CompletableDeferred<Unit>? = null

    val stopsCalls = mutableListOf<String>() // lang per call
    val stopTripsCalls = mutableListOf<Pair<String, Int>>() // stopCode, day

    override suspend fun stops(
        lang: String,
        agency: String,
    ): JsonElement {
        // Count before the error check: a failed call is still a call.
        // The "must not poison the catalog" tests assert retry counts.
        stopsCalls += lang
        stopsError?.let { throw it }
        stopsGate?.await()
        return stops
    }

    override suspend fun lines(
        lang: String,
        agency: String,
    ): JsonElement {
        linesError?.let { throw it }
        return lines
    }

    override suspend fun linePoints(
        agency: String,
        lineCode: String,
    ): JsonElement {
        linePointsError?.let { throw it }
        return linePoints
    }

    override suspend fun routes(
        lang: String,
        agency: String,
    ): JsonElement {
        routesError?.let { throw it }
        return routes
    }

    override suspend fun routeSequence(
        lang: String,
        agency: String,
        routeCode: String,
    ): JsonElement {
        routeSequenceError?.let { throw it }
        return routeSequence
    }

    override suspend fun stopLive(
        lang: String,
        agency: String,
        stopCode: String,
    ): JsonElement {
        stopLiveError?.let { throw it }
        return stopLive
    }

    override suspend fun stopTrips(
        lang: String,
        agency: String,
        stopCode: String,
        day: Int,
    ): JsonElement {
        stopTripsError?.let { throw it }
        stopTripsCalls += stopCode to day
        return stopTrips
    }
}

private fun httpError(code: Int): HttpException = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

class CityBusRepositoryTest {
    private val provider = Provider.CITYBUS

    private fun repo(
        api: FakeCityBusApi,
        lang: String = "el",
    ) = CityBusRepository(
        api,
        testJson,
        agency = "102",
        langProvider = { lang },
        provider = provider,
    )

    // ---------- getStopsNear (client-side haversine) ----------

    @Test
    fun stopsNear_computesDistancesAndSorts() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                                "lineCodes" to jsonArr("01", "02"),
                            ),
                            jsonObj(
                                "code" to "0117",
                                "name" to "ΜΑΚΡΙΑ",
                                "latitude" to 39.6400,
                                "longitude" to 22.4200,
                            ),
                            jsonObj("code" to "BAD", "name" to "ΧΩΡΙΣ ΘΕΣΗ"), // no coords, dropped
                        )
                }
            val stops = repo(api).getStopsNear(39.6387, 22.4161, 10)

            assertEquals(listOf("0116", "0117"), stops.map { it.id })
            assertEquals(0.0, stops[0].distanceKm!!, 1e-6)
            assertEquals(listOf("01", "02"), stops[0].servingLines)
            assertTrue(stops[1].distanceKm!! > stops[0].distanceKm!!)
        }

    @Test
    fun stopsNear_catalogIsCachedPerLanguage() =
        runBlocking {
            var lang = "el"
            val api =
                FakeCityBusApi().apply {
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                }
            val r = CityBusRepository(api, testJson, "102", { lang }, provider)

            val first = r.getStopsNear(39.6387, 22.4161, 10)
            assertEquals("ΑΓΟΡΑ", first[0].name)

            // Locale change mid-process: same code, different (translated) name.
            lang = "en"
            api.stops =
                jsonArr(
                    jsonObj(
                        "code" to "0116",
                        "name" to "MARKET",
                        "latitude" to 39.6387,
                        "longitude" to 22.4161,
                    ),
                )
            val second = r.getStopsNear(39.6387, 22.4161, 10)
            assertEquals("MARKET", second[0].name)

            // Switching back to el must not re-fetch. Both catalogs stay cached.
            lang = "el"
            api.stops =
                jsonArr(
                    jsonObj(
                        "code" to "0116",
                        "name" to "ΛΑΘΟΣ",
                        "latitude" to 39.6387,
                        "longitude" to 22.4161,
                    ),
                )
            val third = r.getStopsNear(39.6387, 22.4161, 10)
            assertEquals("ΑΓΟΡΑ", third[0].name)

            assertEquals(listOf("el", "en"), api.stopsCalls)
        }

    // ---------- searchStops (local ranked filter over the catalog) ----------

    @Test
    fun stopSearch_ranksPrefixBeforeContainsAndCode() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΚΕΝΤΡΟ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                                "lineCodes" to jsonArr("01"),
                            ),
                            jsonObj(
                                "code" to "0200",
                                "name" to "ΠΛ. ΚΕΝΤΡΟΥ",
                                "latitude" to 39.64,
                                "longitude" to 22.42,
                            ),
                            jsonObj(
                                "code" to "1000",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.65,
                                "longitude" to 22.43,
                            ),
                            jsonObj("code" to "BAD", "name" to "ΧΩΡΙΣ ΘΕΣΗ"), // dropped
                        )
                }
            val stops = repo(api).searchStops("κεντρο")

            // Prefix (ΚΕΝΤΡΟ) before contains (ΠΛ. ΚΕΝΤΡΟΥ). ΑΓΟΡΑ doesn't
            // match. The coordinate-less stop is dropped.
            assertEquals(listOf("0116", "0200"), stops.map { it.id })
            assertEquals(listOf("01"), stops[0].servingLines)
        }

    @Test
    fun stopSearch_accentAndCaseInsensitive() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0001",
                                "name" to "ΣΥΝΤΑΓΜΑ",
                                "latitude" to 39.63,
                                "longitude" to 22.41,
                            ),
                        )
                }
            // No accents, lowercase query vs accented uppercase name.
            assertEquals("0001", repo(api).searchStops("συνταγμα").single().id)
        }

    @Test
    fun stopSearch_matchesStopCode() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΚΕΝΤΡΙΚΟ",
                                "latitude" to 39.63,
                                "longitude" to 22.41,
                            ),
                        )
                }
            assertEquals("0116", repo(api).searchStops("011").single().id)
        }

    @Test
    fun stopSearch_blankQueryReturnsNothingWithoutFetchingCatalog() =
        runBlocking {
            val api = FakeCityBusApi()
            assertEquals(emptyList<Stop>(), repo(api).searchStops("   "))
            // The catalog is never touched for a blank query.
            assertEquals(emptyList<String>(), api.stopsCalls)
        }

    @Test
    fun stopSearch_supportsSearchIsTrue() {
        assertEquals(true, repo(FakeCityBusApi()).supportsStopSearch)
    }

    // ---------- catalog resilience (failed/cancelled fetches) ----------

    @Test
    fun failedCatalogFetch_propagatesAndIsNotCached_retriesOnNextAccess() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stopsError = IOException("transient")
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                }
            val repository = repo(api)

            // A transient failure propagates (the screens' error state and
            // the decorator's stale-cache fallback depend on it) but must
            // not be cached. The next call retries instead of serving the
            // empty catalog for the process lifetime.
            try {
                repository.getStopsNear(39.6387, 22.4161)
                assertTrue("expected IOException", false)
            } catch (e: IOException) {
                // expected
            }

            api.stopsError = null
            assertEquals("0116", repository.getStopsNear(39.6387, 22.4161)[0].id)
            assertEquals(2, api.stopsCalls.size)
        }

    @Test
    fun cancelledCatalogFetch_propagatesAndDoesNotPoisonTheCatalog() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stopsError = CancellationException("cancelled")
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                }
            val repository = repo(api)

            // A cancelled fetch must keep cancelling and must not become a
            // cached "empty catalog".
            try {
                repository.getStopsNear(39.6387, 22.4161)
                assertTrue("expected CancellationException", false)
            } catch (e: CancellationException) {
                // expected
            }

            api.stopsError = null
            assertEquals("0116", repository.getStopsNear(39.6387, 22.4161)[0].id)
        }

    @Test
    fun failedLinesFetch_isNotCachedAndRetries() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    linesError = IOException("transient")
                    lines =
                        jsonArr(
                            jsonObj(
                                "code" to "01",
                                "name" to "ΝΕΑ ΣΜΥΡΝΗ",
                                "routes" to
                                    jsonArr(
                                        jsonObj("code" to "R1", "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ"),
                                    ),
                            ),
                        )
                }
            val repository = repo(api)

            // getLines propagates (same rule as getVariantStops. The screens'
            // error state and the decorator's stale-cache fallback depend on
            // it) and must not cache the failure.
            try {
                repository.getLines()
                assertTrue("expected IOException", false)
            } catch (e: IOException) {
                // expected
            }
            try {
                repository.getLineVariants(Line(provider, "01", "01", ""))
                assertTrue("expected IOException", false)
            } catch (e: IOException) {
                // expected
            }

            api.linesError = null
            assertEquals("01", repository.getLines()[0].shortName)
        }

    @Test
    fun stopsNear_concurrentCallers_shareOneCatalogFetch() =
        runBlocking {
            // Five concurrent cold-start calls (stops tab refresh + lines tab +
            // direction sheet + prefetch...): the shared in-flight fetch must
            // collapse them into one catalog fetch. A mutex held across the
            // network would serialize every caller behind the fetch (15 s each
            // when the API stalls).
            val gate = CompletableDeferred<Unit>()
            val api =
                FakeCityBusApi().apply {
                    stopsGate = gate
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                }
            val repository = repo(api)

            // All five callers reach the join while the fetch is held open on
            // the gate (the callers run on the test thread. The fetch runs on
            // the repository's catalog scope).
            val results = (1..5).map { async { repository.getStopsNear(39.6387, 22.4161, 10) } }
            gate.complete(Unit)
            val all = results.awaitAll()

            assertEquals(5, all.size)
            assertEquals(1, api.stopsCalls.size)
            assertEquals("0116", all[0][0].id)
        }

    // ---------- getStopRoutes / getLines / getLineVariants ----------

    @Test
    fun stopRoutes_joinsStopToRouteToEmbeddedLine() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stops = jsonArr(jsonObj("code" to "0116", "routeCodes" to jsonArr("R1", "R9")))
                    routes =
                        jsonArr(
                            jsonObj(
                                "code" to "R1",
                                "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ",
                                "lines" to jsonArr(jsonObj("code" to "01", "name" to "ΝΕΑ ΣΜΥΡΝΗ")),
                            ),
                            // R9 is not in the routes list, so it is dropped.
                        )
                }
            val lines = repo(api).getStopRoutes("0116")

            assertEquals(1, lines.size)
            assertEquals("R1", lines[0].id) // Line.id = route code (arrivals join key)
            assertEquals("01", lines[0].shortName)
            assertEquals("ΝΕΑ ΣΜΥΡΝΗ", lines[0].longName)
            assertEquals("ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ", lines[0].destination)
        }

    @Test
    fun stopRoutes_unknownStop_returnsEmpty() =
        runBlocking {
            val api = FakeCityBusApi().apply { stops = jsonArr(jsonObj("code" to "0116")) }
            assertEquals(emptyList<Line>(), repo(api).getStopRoutes("9999"))
        }

    @Test
    fun lines_withoutRoutes_areHidden() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "code" to "01",
                                "name" to "ΝΕΑ ΣΜΥΡΝΗ",
                                "routes" to
                                    jsonArr(jsonObj("code" to "R1", "name" to "ΠΡΟΣ ΚΕΝΤΡΟ")),
                            ),
                            jsonObj("code" to "12", "name" to "ΓΑΙΟΠΟΛΙΣ", "routes" to jsonArr()),
                            // suspended line, a dead end, hidden
                            jsonObj(
                                "code" to "02",
                                "name" to "ΑΜΠΕΛΟΚΗΠΟΙ",
                                "routes" to jsonArr(jsonObj("code" to "R2")),
                            ),
                        )
                }
            val lines = repo(api).getLines()
            assertEquals(listOf("01", "02"), lines.map { it.shortName })
        }

    @Test
    fun lineVariants_routesBecomeDirectionVariants() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "code" to "01",
                                "name" to "ΝΕΑ ΣΜΥΡΝΗ",
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "code" to "R1",
                                            "name" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                                            "direction" to 1,
                                        ),
                                        jsonObj(
                                            "code" to "R2",
                                            "name" to "ΠΡΟΣ ΤΕΡΜΑ",
                                            "direction" to 2,
                                        ),
                                        jsonObj("code" to "R1", "name" to "ΠΡΟΣ ΚΕΝΤΡΟ"),
                                        // duplicate route, deduped
                                    ),
                            ),
                        )
                }
            val line = Line(provider, "01", "01", "ΝΕΑ ΣΜΥΡΝΗ")
            val variants = repo(api).getLineVariants(line)

            assertEquals(listOf("R1", "R2"), variants.map { it.id })
            assertEquals("01", variants[0].lineId)
            assertEquals("ΠΡΟΣ ΚΕΝΤΡΟ", variants[0].label)
        }

    // ---------- getVariantStops / getRouteGeometry ----------

    @Test
    fun variantStops_networkFailure_propagates() {
        // Failures must reach the screens' error state, never a silent
        // "no stops on this route" empty board.
        val api = FakeCityBusApi().apply { routeSequenceError = IOException("transient") }
        val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getVariantStops(variant) }
        }
    }

    @Test
    fun variantStops_decodeFailure_propagates() {
        val api = FakeCityBusApi().apply { routeSequence = jsonObj("junk" to 1) } // not an array
        val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
        assertThrows(SerializationException::class.java) {
            runBlocking { repo(api).getVariantStops(variant) }
        }
    }

    @Test
    fun routeGeometry_networkFailure_propagates() {
        val api = FakeCityBusApi().apply { linePointsError = IOException("transient") }
        val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getRouteGeometry(variant) }
        }
    }

    @Test
    fun variantStops_joinsSequenceWithStopCatalog_sortedBySequence() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    routeSequence =
                        jsonArr(
                            jsonObj("sequence" to 2, "code" to "0117"),
                            jsonObj("sequence" to 1, "code" to "0116"),
                            jsonObj("sequence" to 3, "code" to "UNKNOWN"),
                            // not in the stop list, dropped
                            jsonObj("sequence" to 4, "code" to "0118"),
                        )
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                            jsonObj(
                                "code" to "0117",
                                "name" to "ΠΛΑΤΕΙΑ",
                                "latitude" to 39.6400,
                                "longitude" to 22.4200,
                            ),
                            jsonObj("code" to "0118", "name" to "ΧΩΡΙΣ ΜΗΚΟΣ", "latitude" to 39.64),
                            // no longitude, dropped
                        )
                }
            val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
            val stops = repo(api).getVariantStops(variant)

            assertEquals(listOf("0116", "0117"), stops.map { it.id })
            assertEquals("ΑΓΟΡΑ", stops[0].name)
        }

    @Test
    fun routeGeometry_picksOnlyThisVariant_parsesStringCoords() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    linePoints =
                        jsonArr(
                            jsonObj(
                                "routeCode" to "R1",
                                "routePoints" to
                                    jsonArr(
                                        jsonObj(
                                            "sequence" to 2,
                                            "latitude" to "39.6400",
                                            "longitude" to "22.4200",
                                        ),
                                        jsonObj(
                                            "sequence" to 1,
                                            "latitude" to "39.6387",
                                            "longitude" to "22.4161",
                                        ),
                                        jsonObj(
                                            "sequence" to 3,
                                            "latitude" to "junk",
                                            "longitude" to "22.4200",
                                        ), // not a number, dropped
                                    ),
                            ),
                            jsonObj(
                                "routeCode" to "R2",
                                "routePoints" to
                                    jsonArr(
                                        jsonObj(
                                            "sequence" to 1,
                                            "latitude" to "1.0",
                                            "longitude" to "1.0",
                                        ), // other direction
                                    ),
                            ),
                        )
                }
            val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
            val points = repo(api).getRouteGeometry(variant)

            assertEquals(listOf(GeoPoint(39.6387, 22.4161), GeoPoint(39.64, 22.42)), points)
        }

    // ---------- observeArrivals (404 means empty, "0"/"0" = no GPS) ----------

    @Test
    fun arrivals_http404_mapsToEmpty_notError() =
        runBlocking {
            val api = FakeCityBusApi().apply { stopLiveError = httpError(404) }
            assertTrue(repo(api).observeArrivals("0116", emptyList()).first().isEmpty())
        }

    @Test
    fun arrivals_otherHttpErrors_propagate() {
        val api = FakeCityBusApi().apply { stopLiveError = httpError(500) }
        assertThrows(HttpException::class.java) {
            runBlocking { repo(api).observeArrivals("0116", emptyList()).first() }
        }
    }

    @Test
    fun arrivals_withGps_carriesVehicle() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stopLive =
                        jsonObj(
                            "vehicles" to
                                jsonArr(
                                    jsonObj(
                                        "vehicleCode" to "VC1",
                                        "lineCode" to "01",
                                        "lineName" to "ΝΕΑ ΣΜΥΡΝΗ",
                                        "routeCode" to "R1",
                                        "routeName" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                                        "departureMins" to 4,
                                        "latitude" to "39.6387",
                                        "longitude" to "22.4161",
                                    ),
                                ),
                        )
                }
            val lines =
                listOf(Line(provider, "R1", "01", "ΝΕΑ ΣΜΥΡΝΗ", destination = "ΠΡΟΣ ΚΕΝΤΡΟ"))
            val arrivals = repo(api).observeArrivals("0116", lines).first()

            assertEquals(1, arrivals.size)
            val a = arrivals[0]
            assertEquals(4, a.etaMinutes)
            assertEquals("VC1", a.tripId) // vehicleCode identifies the TRIP
            assertEquals("01", a.lineShortName)
            assertEquals("ΠΡΟΣ ΚΕΝΤΡΟ", a.destination)
            assertEquals("VC1", a.vehicle!!.vehicleId)
            assertEquals(39.6387, a.vehicle.lat, 1e-6)
        }

    @Test
    fun arrivals_zeroZeroGps_keepsArrival_dropsVehicle() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stopLive =
                        jsonObj(
                            "vehicles" to
                                jsonArr(
                                    jsonObj(
                                        "vehicleCode" to "VC1",
                                        "lineCode" to "01",
                                        "departureMins" to 7,
                                        "latitude" to "0",
                                        "longitude" to "0", // no GPS fix
                                    ),
                                ),
                        )
                }
            val arrivals = repo(api).observeArrivals("0116", emptyList()).first()

            // The bus is coming. The arrival survives, only the position is gone.
            assertEquals(1, arrivals.size)
            assertEquals(7, arrivals[0].etaMinutes)
            assertNull(arrivals[0].vehicle)
        }

    @Test
    fun arrivals_dropsVehiclesWithoutPosition() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stopLive =
                        jsonObj(
                            "vehicles" to
                                jsonArr(
                                    jsonObj("vehicleCode" to "VC1", "departureMins" to 2),
                                    // no coords at all
                                ),
                        )
                }
            val arrivals = repo(api).observeArrivals("0116", emptyList()).first()
            assertEquals(1, arrivals.size)
            assertNull(arrivals[0].vehicle)
        }

    @Test
    fun arrivals_loopTripBothPasses_dedupeKeepsEarliestEta() =
        runBlocking {
            // Loop routes (Xanthi 02-12, Serres 002/004/023...) visit a stop
            // twice, and stops/live can report both passes of one trip in a
            // single response. The arrivals list keys rows by tripId, so a
            // duplicate would crash the LazyColumn ("Key was already used").
            // The adapter must keep only the next pass, the earliest ETA.
            val api =
                FakeCityBusApi().apply {
                    stopLive =
                        jsonObj(
                            "vehicles" to
                                jsonArr(
                                    jsonObj(
                                        "vehicleCode" to "VC1",
                                        "lineCode" to "02",
                                        "departureMins" to 9,
                                    ), // outbound pass
                                    jsonObj(
                                        "vehicleCode" to "VC2",
                                        "lineCode" to "05",
                                        "departureMins" to 3,
                                    ),
                                    jsonObj(
                                        "vehicleCode" to "VC1",
                                        "lineCode" to "02",
                                        "departureMins" to 27,
                                    ), // return-leg pass
                                ),
                        )
                }
            val arrivals = repo(api).observeArrivals("0116", emptyList()).first()

            assertEquals(listOf("VC2", "VC1"), arrivals.map { it.tripId })
            assertEquals(listOf(3, 9), arrivals.map { it.etaMinutes })
        }

    @Test
    fun arrivals_nullTripIds_neverDeduplicated() =
        runBlocking {
            // Distinct rows without a trip id must all survive. The dedupe
            // only ever collapses duplicate non-null trip ids.
            val api =
                FakeCityBusApi().apply {
                    stopLive =
                        jsonObj(
                            "vehicles" to
                                jsonArr(
                                    jsonObj("lineCode" to "01", "departureMins" to 2),
                                    jsonObj("lineCode" to "02", "departureMins" to 5),
                                ),
                        )
                }
            val arrivals = repo(api).observeArrivals("0116", emptyList()).first()

            assertEquals(2, arrivals.size)
            assertNull(arrivals[0].tripId)
            assertNull(arrivals[1].tripId)
        }

    // ---------- getStopTimetable (0=Sunday day numbering) ----------

    @Test
    fun stopTimetable_mapsDayOfWeekToApiNumbering() =
        runBlocking {
            val api = FakeCityBusApi()
            val r = repo(api)
            r.getStopTimetable("0116", DayOfWeek.SUNDAY) // 7 % 7 = 0
            r.getStopTimetable("0116", DayOfWeek.MONDAY) // 1 % 7 = 1
            r.getStopTimetable("0116", DayOfWeek.SATURDAY) // 6 % 7 = 6
            assertEquals(listOf("0116" to 0, "0116" to 1, "0116" to 6), api.stopTripsCalls)
        }

    @Test
    fun stopTimetable_parsesTrips_intoEntries() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    stopTrips =
                        jsonArr(
                            jsonObj(
                                "id" to 2,
                                "tripTime" to "14:30",
                                "lineCode" to "01",
                                "lineName" to "ΝΕΑ ΣΜΥΡΝΗ",
                                "routeCode" to "R1",
                                "routeName" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                            ),
                            jsonObj(
                                "id" to 1,
                                "tripTime" to "05:30",
                                "lineCode" to "01",
                                "lineName" to "ΝΕΑ ΣΜΥΡΝΗ",
                                "routeCode" to "R1",
                                "routeName" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                            ),
                            jsonObj("id" to 3), // no tripTime, dropped
                        )
                }
            val entries = repo(api).getStopTimetable("0116", DayOfWeek.MONDAY)

            assertEquals(listOf("05:30", "14:30"), entries.map { it.departureTime })
            assertEquals("01", entries[0].lineShortName)
            assertEquals("2", entries[1].tripId)
        }

    @Test
    fun stopTimetable_404_noServiceThatDay_mapsToEmpty() =
        runBlocking {
            val api = FakeCityBusApi().apply { stopTripsError = httpError(404) }
            assertTrue(repo(api).getStopTimetable("0116", DayOfWeek.SUNDAY).isEmpty())
        }

    @Test
    fun stopTimetable_decodeFailure_propagates() {
        // A decode failure must not become a cached 24 h "no trips" board.
        // only the 404 = no-service convention maps to empty.
        val api = FakeCityBusApi().apply { stopTrips = jsonObj("junk" to 1) }
        assertThrows(SerializationException::class.java) {
            runBlocking { repo(api).getStopTimetable("0116", DayOfWeek.MONDAY) }
        }
    }

    @Test
    fun stopTimetable_otherErrors_propagate() {
        val api = FakeCityBusApi().apply { stopTripsError = httpError(500) }
        assertThrows(HttpException::class.java) {
            runBlocking { repo(api).getStopTimetable("0116", DayOfWeek.MONDAY) }
        }
    }

    // ---------- getLineTimetable (derived from the origin stop) ----------

    @Test
    fun lineTimetable_originFromRawSequence_stopsDroppedByTheJoinStillResolve() =
        runBlocking {
            // The origin is the raw sequence's lowest entry, resolved by code:
            // stop 0116 has no coordinates, so getVariantStops' join drops it.
            // An origin taken from the joined list would silently shift to
            // 0117, the second survivor. The raw-sequence lookup needs no
            // lat/lon.
            val api =
                FakeCityBusApi().apply {
                    routeSequence =
                        jsonArr(
                            jsonObj("sequence" to 1, "code" to "0116"),
                            jsonObj("sequence" to 2, "code" to "0117"),
                        )
                    stops =
                        jsonArr(
                            jsonObj("code" to "0116", "name" to "ΑΓΟΡΑ"),
                            // no coords, dropped by the join
                            jsonObj(
                                "code" to "0117",
                                "name" to "ΠΛΑΤΕΙΑ",
                                "latitude" to 39.64,
                                "longitude" to 22.42,
                            ),
                        )
                    stopTrips =
                        jsonArr(
                            jsonObj(
                                "id" to 1,
                                "tripTime" to "05:30",
                                "lineCode" to "01",
                                "routeCode" to "R1",
                            ),
                        )
                }
            val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
            val entries = repo(api).getLineTimetable(variant, DayOfWeek.MONDAY)

            assertEquals(listOf("05:30"), entries.map { it.departureTime })
            assertEquals(listOf("0116" to 1), api.stopTripsCalls) // the true origin, not 0117
        }

    @Test
    fun lineTimetable_originMissingFromCatalog_returnsEmptyNotSecondStop() =
        runBlocking {
            // The true origin isn't in the stop catalog at all: an empty
            // board is the right answer, not a silent shift to the second
            // stop's schedule (that would serve the wrong departures).
            val api =
                FakeCityBusApi().apply {
                    routeSequence =
                        jsonArr(
                            jsonObj("sequence" to 1, "code" to "UNKNOWN"),
                            jsonObj("sequence" to 2, "code" to "0116"),
                        )
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                }
            val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")

            assertTrue(repo(api).getLineTimetable(variant, DayOfWeek.MONDAY).isEmpty())
            assertTrue(api.stopTripsCalls.isEmpty()) // never fetched a wrong stop's schedule
        }

    @Test
    fun lineTimetable_derivedFromOriginStop_filteredByRouteCode() =
        runBlocking {
            val api =
                FakeCityBusApi().apply {
                    routeSequence = jsonArr(jsonObj("sequence" to 1, "code" to "0116"))
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΓΟΡΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                    stopTrips =
                        jsonArr(
                            jsonObj(
                                "id" to 1,
                                "tripTime" to "05:30",
                                "lineCode" to "01",
                                "routeCode" to "R1",
                                "routeName" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                            ),
                            jsonObj(
                                "id" to 2,
                                "tripTime" to "06:00",
                                "lineCode" to "01",
                                "routeCode" to "OTHER",
                                "routeName" to "ΑΛΛΟ ΔΡΟΜΟΛΟΓΙΟ",
                            ),
                        )
                }
            val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
            val entries = repo(api).getLineTimetable(variant, DayOfWeek.MONDAY)

            // Only THIS route's trips at the origin stop survive the filter.
            assertEquals(listOf("05:30"), entries.map { it.departureTime })
            assertEquals(listOf("0116" to 1), api.stopTripsCalls)
        }

    @Test
    fun lineTimetable_routeWithoutService_fallsBackToSameDirectionSibling() =
        runBlocking {
            // Larissa 01's weekday route doesn't run Saturdays. The market
            // (ΛΑΙΚΗ ΣΑΒΒΑΤΟΥ) route of the same direction does. The line
            // timetable must answer "does the line run that day", so an empty
            // own-route board falls back to the same-direction sibling's trips
            // at this origin, never to the reverse direction's visits.
            val api =
                FakeCityBusApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "code" to "01",
                                "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ",
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "code" to "R1",
                                            "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ",
                                            "direction" to 1,
                                        ),
                                        jsonObj(
                                            "code" to "R2",
                                            "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ (ΛΑΙΚΗ ΣΑΒΒΑΤΟΥ)",
                                            "direction" to 1,
                                        ),
                                        jsonObj(
                                            "code" to "R3",
                                            "name" to "ΝΕΑΠΟΛΗ - ΝΕΑ ΣΜΥΡΝΗ",
                                            "direction" to 2,
                                        ),
                                    ),
                            ),
                        )
                    routeSequence = jsonArr(jsonObj("sequence" to 1, "code" to "0116"))
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΦΕΤΗΡΙΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                    stopTrips =
                        jsonArr(
                            // The reverse direction terminates at this stop. It must stay out.
                            jsonObj(
                                "id" to 1,
                                "tripTime" to "06:00",
                                "lineCode" to "01",
                                "routeCode" to "R3",
                                "routeName" to "ΝΕΑΠΟΛΗ - ΝΕΑ ΣΜΥΡΝΗ",
                            ),
                            jsonObj(
                                "id" to 2,
                                "tripTime" to "06:30",
                                "lineCode" to "01",
                                "routeCode" to "R2",
                                "routeName" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ (ΛΑΙΚΗ ΣΑΒΒΑΤΟΥ)",
                            ),
                        )
                }
            val weekdayVariant =
                LineVariant(
                    provider = provider,
                    lineId = "01",
                    id = "R1",
                    label = "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ",
                    lineShortName = "01",
                )
            val entries = repo(api).getLineTimetable(weekdayVariant, DayOfWeek.SATURDAY)

            // The same-direction market route's departure, and not the reverse trip.
            assertEquals(listOf("06:30"), entries.map { it.departureTime })
            assertEquals("ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ (ΛΑΙΚΗ ΣΑΒΒΑΤΟΥ)", entries[0].destination)
        }

    @Test
    fun lineTimetable_noSameDirectionSibling_returnsEmpty() =
        runBlocking {
            // A line whose routes have no direction field still yields an
            // empty board, never a cross-direction mix.
            val api =
                FakeCityBusApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "code" to "01",
                                "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ",
                                "routes" to
                                    jsonArr(
                                        jsonObj("code" to "R1", "name" to "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ"),
                                        jsonObj("code" to "R2", "name" to "ΝΕΑΠΟΛΗ - ΝΕΑ ΣΜΥΡΝΗ"),
                                    ),
                            ),
                        )
                    routeSequence = jsonArr(jsonObj("sequence" to 1, "code" to "0116"))
                    stops =
                        jsonArr(
                            jsonObj(
                                "code" to "0116",
                                "name" to "ΑΦΕΤΗΡΙΑ",
                                "latitude" to 39.6387,
                                "longitude" to 22.4161,
                            ),
                        )
                    stopTrips =
                        jsonArr(
                            jsonObj(
                                "id" to 1,
                                "tripTime" to "06:00",
                                "lineCode" to "01",
                                "routeCode" to "R2",
                                "routeName" to "ΝΕΑΠΟΛΗ - ΝΕΑ ΣΜΥΡΝΗ",
                            ),
                        )
                }
            val variant =
                LineVariant(
                    provider = provider,
                    lineId = "01",
                    id = "R1",
                    label = "ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ",
                    lineShortName = "01",
                )
            assertTrue(repo(api).getLineTimetable(variant, DayOfWeek.SATURDAY).isEmpty())
        }

    // ---------- observeVehicles (no per-route endpoint on this platform) ----------

    @Test
    fun observeVehicles_alwaysEmpty() =
        runBlocking {
            val variant = LineVariant(provider, "01", "R1", "ΠΡΟΣ ΚΕΝΤΡΟ", "01")
            assertEquals(
                emptyList<VehiclePosition>(),
                repo(FakeCityBusApi()).observeVehicles(variant).first(),
            )
        }

    @Test
    fun observeArrivals_decodeFailure_propagates() {
        // A changed API shape must surface as a poll failure, never as a
        // false "Καμία άφιξη" empty emission.
        val api = FakeCityBusApi().apply { stopLive = jsonObj("vehicles" to "bogus") }
        val thrown =
            runBlocking {
                runCatching {
                    repo(
                        api,
                    ).observeArrivals("0116", emptyList()).first()
                }.exceptionOrNull()
            }
        assertTrue(thrown is SerializationException)
    }
}
