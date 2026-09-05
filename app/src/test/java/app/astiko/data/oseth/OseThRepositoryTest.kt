package app.astiko.data.oseth

import app.astiko.data.jsonArr
import app.astiko.data.jsonObj
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.testJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.DayOfWeek

/** Hand-rolled OseThApi: records args, feeds wire-shaped JSON (already unwrapped). */
class FakeOseThApi : OseThApi {
    var nearby: JsonElement? = null
    var stopInfo: JsonElement? = null
    var routes: JsonElement? = null
    var routesCalls = 0
    var routesError: Throwable? = null
    var routeInfo: JsonElement? = null
    var routeInfoCalls = 0
    var routeInfoError: Throwable? = null
    var stopTimetableResponse: JsonElement? = null
    var routeTimetableResponse: JsonElement? = null

    var nearbyLang: String? = null
    var nearbyLanguage: String? = null
    var lastRoutesLanguage: String? = null
    var lastStopTimetableDate: String? = null
    var lastStopTimetableLang: String? = null
    var lastRouteTimetableShapeId: String? = null
    var lastRouteTimetableDate: String? = null

    /** Per-shape route-timetable responses. The sibling-shape fallback
     *  tests feed a different payload per shapeId. */
    val routeTimetableByShape = mutableMapOf<String, JsonElement>()
    val routeTimetableShapeCalls = mutableListOf<String>()

    override suspend fun nearbyStops(
        lang: String,
        page: Int,
        size: Int,
        lon: Double,
        lat: Double,
        language: String,
    ): JsonElement {
        nearbyLang = lang
        nearbyLanguage = language
        return nearby
            ?: jsonObj("data" to jsonObj("stops" to jsonArr()), "error" to "", "status_code" to 200)
    }

    override suspend fun stopInfo(
        lang: String,
        stopId: String,
        language: String,
    ): JsonElement = stopInfo ?: jsonObj("data" to jsonObj(), "error" to "", "status_code" to 200)

    /** Full stop-catalog pages (the server caps size at 1000, so
     *  getStopCatalog paginates. The fake serves one payload per page). */
    val stopsPages = mutableMapOf<Int, JsonElement>()
    var stopsError: Throwable? = null
    val stopCalls = mutableListOf<Int>() // page per call
    var lastStopsLanguage: String? = null

    override suspend fun stops(
        lang: String,
        page: Int,
        size: Int,
        language: String,
    ): JsonElement {
        stopCalls += page
        lastStopsLanguage = lang
        stopsError?.let { throw it }
        return stopsPages[page]
            ?: jsonObj("data" to jsonObj("stops" to jsonArr()), "error" to "", "status_code" to 200)
    }

    override suspend fun stopTimetable(
        lang: String,
        stopId: String,
        language: String,
        date: String,
    ): JsonElement {
        lastStopTimetableLang = lang
        lastStopTimetableDate = date
        return stopTimetableResponse
            ?: jsonObj("data" to jsonObj("trips" to jsonArr()), "error" to "", "status_code" to 200)
    }

    override suspend fun routes(
        lang: String,
        page: Int,
        size: Int,
        language: String,
    ): JsonElement {
        routesCalls++
        lastRoutesLanguage = language
        routesError?.let { throw it }
        return routes
            ?: jsonObj(
                "data" to jsonObj("routes" to jsonArr(), "total" to 0),
                "error" to "",
                "status_code" to 200,
            )
    }

    override suspend fun routeInfo(
        lang: String,
        routeId: String,
        shapeId: String,
        language: String,
    ): JsonElement {
        routeInfoCalls++
        routeInfoError?.let { throw it }
        return routeInfo
            ?: jsonObj("data" to jsonObj("stops" to jsonArr()), "error" to "", "status_code" to 200)
    }

    override suspend fun routeTimetable(
        lang: String,
        routeId: String,
        date: String,
        shapeId: String,
        language: String,
    ): JsonElement {
        lastRouteTimetableShapeId = shapeId
        lastRouteTimetableDate = date
        routeTimetableShapeCalls += shapeId
        return routeTimetableByShape[shapeId] ?: routeTimetableResponse
            ?: jsonObj("data" to jsonObj("trips" to jsonArr()), "error" to "", "status_code" to 200)
    }
}

/** Wrap test payloads in the {"data": ...} envelope every endpoint uses. */
private fun envelope(data: JsonElement) =
    jsonObj(
        "data" to data,
        "error" to "",
        "status_code" to 200,
    )

class OseThRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(
        api: FakeOseThApi,
        lang: String = "el",
    ) = OseThRepository(api, testJson, { lang })

    private val variant =
        LineVariant(
            provider = Provider.OSETh,
            lineId = "01_7429_1_3",
            id = "01_7429_2_3",
            shapeId = "sh2",
            label = "ΝΕΑ ΕΛΒΕΤΙΑ",
            lineShortName = "01",
        )

    @Test
    fun stopRoutes_missingEnvelope_propagates() {
        val api = FakeOseThApi().apply { stopInfo = jsonObj("error" to "nope") }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getStopRoutes("S1") }
        }
    }

    @Test
    fun stopRoutes_genuineEmpty_returnsEmpty() =
        runBlocking {
            // A verified {"routes": []} (stop with no scheduled service) stays
            // an empty list. Only malformed envelopes propagate.
            val api = FakeOseThApi().apply { stopInfo = envelope(jsonObj("routes" to jsonArr())) }
            assertEquals(emptyList<Line>(), repo(api).getStopRoutes("S1"))
        }

    // ---------- catalog failures propagate ----------

    @Test
    fun getLines_transportFailure_propagates() {
        val api = FakeOseThApi().apply { routesError = IOException("transient") }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getLines() }
        }
    }

    @Test
    fun getLines_malformedEnvelope_propagates() {
        val api = FakeOseThApi().apply { routes = jsonObj("error" to "nope") }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getLines() }
        }
    }

    @Test
    fun getLines_decodeFailure_propagates() {
        val api = FakeOseThApi().apply { routes = envelope(jsonObj("routes" to "bogus")) }
        assertThrows(SerializationException::class.java) {
            runBlocking { repo(api).getLines() }
        }
    }

    @Test
    fun getLineVariants_catalogFailure_propagates() {
        // A dead /route catalog must fail the direction sheet with a
        // retryable error, not "this line has no directions".
        val api = FakeOseThApi().apply { routesError = IOException("transient") }
        val line = Line(Provider.OSETh, "01_7429_1_3", "01", "")
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getLineVariants(line) }
        }
    }

    // ---------- getStopsNear ----------

    @Test
    fun stopsNear_unwrapsEnvelope_distanceMetersToKm() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    nearby =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "S1",
                                            "name" to "ΑΓΟΡΑ",
                                            "latitude" to 40.61,
                                            "longitude" to 22.96,
                                            "distance" to 120.5,
                                            "routes" to
                                                jsonArr(
                                                    jsonObj("shortName" to "01"),
                                                    jsonObj("shortName" to "01"),
                                                    jsonObj("shortName" to "2"),
                                                ),
                                        ),
                                        jsonObj(
                                            "id" to "S2",
                                            "name" to "ΧΩΡΙΣ ΜΗΚΟΣ",
                                            "latitude" to 40.62,
                                        ), // no longitude, dropped
                                    ),
                                "total" to 2,
                            ),
                        )
                }
            val stops = repo(api).getStopsNear(40.6, 22.95, 10)

            assertEquals(listOf("S1"), stops.map { it.id })
            assertEquals(0.1205, stops[0].distanceKm!!, 1e-9) // meters to km
            assertEquals(listOf("01", "2"), stops[0].servingLines) // distinct
        }

    @Test
    fun stopsNear_missingEnvelope_propagates() {
        // An error envelope without "data" is a malformed/error response,
        // a retryable error, never a fake "no stops nearby" (catalog and
        // per-call failures must not present as successful empties).
        val api =
            FakeOseThApi().apply {
                nearby =
                    jsonObj("error" to "nope", "status_code" to 400)
            }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getStopsNear(40.6, 22.95, 10) }
        }
    }

    @Test
    fun stopsNear_genuineEmptyStops_returnsEmpty() =
        runBlocking {
            // {"data": {"stops": []}} is the verified semantic-empty (no
            // stops near the point). It must stay an empty list, not an error.
            val api = FakeOseThApi() // default fixture = empty stops
            assertEquals(emptyList<Stop>(), repo(api).getStopsNear(40.6, 22.95, 10))
        }

    @Test
    fun stopsNear_languageFollowsAppLocale() =
        runBlocking {
            val api = FakeOseThApi()
            repo(api, lang = "en").getStopsNear(40.6, 22.95, 10)
            assertEquals("en", api.nearbyLang)
            assertEquals("en", api.nearbyLanguage) // path prefix AND query param
        }

    // ---------- getStopCatalog (the search raw material) ----------

    @Test
    fun getStopCatalog_mapsRoutesToBadges() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    stopsPages[1] =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "S1",
                                            "code" to "S1",
                                            "name" to "ΠΛ. ΑΓΙΑΣ ΣΟΦΙΑΣ",
                                            "latitude" to 40.63,
                                            "longitude" to 22.94,
                                            "routes" to
                                                jsonArr(
                                                    jsonObj("shortName" to "01"),
                                                    jsonObj("shortName" to "01"),
                                                    jsonObj("shortName" to "2"),
                                                ),
                                        ),
                                        jsonObj(
                                            "id" to "S2",
                                            "name" to "ΧΩΡΙΣ ΣΥΝΤΕΤΑΓΜΕΝΕΣ",
                                            "latitude" to null,
                                            "longitude" to null,
                                        ), // dropped
                                    ),
                                "total" to 2,
                            ),
                        )
                }
            val stops = repo(api).getStopCatalog()

            assertEquals(listOf("S1"), stops.map { it.id })
            assertEquals(listOf("01", "2"), stops[0].servingLines) // distinct
        }

    @Test
    fun getStopCatalog_paginatesTheFullCatalog() =
        runBlocking {
            // The server caps size at 1000: page 1 exactly full, page 2
            // short. The search must walk both pages to find page-2 stops.
            val api =
                FakeOseThApi().apply {
                    val page1 = buildJsonArray { repeat(1000) { add(buildJsonObject { put("id", "P1-$it") }) } }
                    stopsPages[1] = envelope(jsonObj("stops" to page1, "total" to 1001))
                    stopsPages[2] =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "P2-0",
                                            "name" to "ΑΓΙΑΣ ΣΟΦΙΑΣ",
                                            "latitude" to 40.63,
                                            "longitude" to 22.94,
                                        ),
                                    ),
                                "total" to 1001,
                            ),
                        )
                }
            val stops = repo(api).getStopCatalog()

            assertEquals(listOf("P2-0"), stops.map { it.id })
            assertEquals(listOf(1, 2), api.stopCalls)
        }

    @Test
    fun getStopCatalog_missingEnvelope_propagates() {
        // A missing envelope is a malformed/error response, a retryable
        // error, never a fake "no matches".
        val api =
            FakeOseThApi().apply {
                stopsPages[1] = jsonObj("error" to "nope", "status_code" to 400)
            }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getStopCatalog() }
        }
    }

    @Test
    fun getStopCatalog_genuineEmpty_returnsEmpty() =
        runBlocking {
            // An empty catalog is the semantic-empty: an empty list, not
            // an error.
            val api = FakeOseThApi() // default fixture = empty stops
            assertEquals(emptyList<Stop>(), repo(api).getStopCatalog())
        }

    @Test
    fun searchStops_defaultRanksOverTheCatalog() =
        runBlocking {
            // searchStops is the interface default (rank over the catalog).
            // pin its wiring here: a non-matching query is empty, a
            // matching one ranks.
            val api =
                FakeOseThApi().apply {
                    stopsPages[1] =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "S1",
                                            "name" to "ΣΥΝΤΑΓΜΑ",
                                            "latitude" to 37.97,
                                            "longitude" to 23.73,
                                        ),
                                    ),
                                "total" to 1,
                            ),
                        )
                }
            assertEquals(listOf("S1"), repo(api).searchStops("συνταγμα").map { it.id })
            assertEquals(emptyList<Stop>(), repo(api).searchStops("ζζζ"))
        }

    @Test
    fun searchStops_blankQueryDoesNotFetchCatalog() =
        runBlocking {
            val api = FakeOseThApi()
            assertEquals(emptyList<Stop>(), repo(api).searchStops("   "))
            assertEquals(emptyList<Int>(), api.stopCalls)
        }

    @Test
    fun getStopCatalog_languageFollowsAppLocale() =
        runBlocking {
            val api = FakeOseThApi()
            repo(api, lang = "en").getStopCatalog()
            assertEquals("en", api.lastStopsLanguage)
        }

    // ---------- getStopRoutes / getLines ----------

    @Test
    fun stopRoutes_bothDirections_keepDistinctLongNames() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    stopInfo =
                        envelope(
                            jsonObj(
                                "id" to "S1",
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "01_7429_1_3",
                                            "shortName" to "01",
                                            "longName" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                        ),
                                        jsonObj(
                                            "id" to "01_7429_2_3",
                                            "shortName" to "01",
                                            "longName" to "ΝΕΑ ΕΛΒΕΤΙΑ",
                                        ),
                                    ),
                            ),
                        )
                }
            val lines = repo(api).getStopRoutes("S1")
            assertEquals(listOf("01_7429_1_3", "01_7429_2_3"), lines.map { it.id })
            assertEquals(listOf("ΚΑΤΩ ΤΟΥΜΠΑ", "ΝΕΑ ΕΛΒΕΤΙΑ"), lines.map { it.longName })
        }

    @Test
    fun lines_sortedByShortName() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj("id" to "01_7429_1_3", "shortName" to "01"),
                                        jsonObj("id" to "10_1", "shortName" to "10"),
                                        jsonObj("id" to "02_1", "shortName" to "02"),
                                    ),
                            ),
                        )
                }
            assertEquals(listOf("01", "02", "10"), repo(api).getLines().map { it.shortName })
        }

    @Test
    fun concurrentGetLines_shareOneRoutesFetch() =
        runBlocking {
            // Same shared-fetch contract as CityBus: five concurrent cold-start
            // callers must share one /route fetch. A mutex held across the
            // network would serialize every caller behind the catalog fetch.
            val api =
                FakeOseThApi().apply {
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj("id" to "01_7429_1_3", "shortName" to "01"),
                                    ),
                            ),
                        )
                }
            val r = repo(api)

            val results = (1..5).map { async { r.getLines() } }.awaitAll()
            assertEquals(5, results.size)
            assertEquals(1, api.routesCalls)
        }

    // ---------- getLineVariants (direction id fallback quirk) ----------

    @Test
    fun lineVariants_fallsBackToShortNameWhenDirectionIdMissing() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    // /route lists one entry per line (01_7429_1_3). The stop's
                    // line entries carry direction ids (01_7429_2_3) not in it.
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "01_7429_1_3",
                                            "shortName" to "01",
                                            "longName" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                            "tripHeadsigns" to
                                                jsonArr(
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "shapeId" to "sh1",
                                                        "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                                    ),
                                                    jsonObj(
                                                        "routeId" to "01_7429_2_3",
                                                        "shapeId" to "sh2",
                                                        "headsign" to "ΝΕΑ ΕΛΒΕΤΙΑ",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }
            val line = Line(Provider.OSETh, "01_7429_2_3", "01", "ΚΑΤΩ ΤΟΥΜΠΑ")
            // direction-specific id
            val variants = repo(api).getLineVariants(line)

            assertEquals(2, variants.size)
            assertEquals("01_7429_2_3", variants[1].id)
            assertEquals("sh2", variants[1].shapeId)
            assertEquals("ΝΕΑ ΕΛΒΕΤΙΑ", variants[1].label)
        }

    // ---------- getVariantStops / getRouteGeometry ----------

    @Test
    fun variantStops_routeInfoFailure_propagates() {
        // A failed routeInfo fetch must surface as an error, never a
        // silent "no stops on this route" board.
        val api = FakeOseThApi().apply { routeInfoError = IOException("transient") }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getVariantStops(variant) }
        }
    }

    @Test
    fun routeGeometry_routeInfoFailure_propagates() {
        val api = FakeOseThApi().apply { routeInfoError = IOException("transient") }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getRouteGeometry(variant) }
        }
    }

    @Test
    fun variantStops_orderedBySequence() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    routeInfo =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "S2",
                                            "name" to "ΠΛΑΤΕΙΑ",
                                            "sequence" to 2,
                                            "latitude" to 40.62,
                                            "longitude" to 22.97,
                                        ),
                                        jsonObj(
                                            "id" to "S1",
                                            "name" to "ΑΓΟΡΑ",
                                            "sequence" to 1,
                                            "latitude" to 40.61,
                                            "longitude" to 22.96,
                                        ),
                                        jsonObj(
                                            "id" to "S3",
                                            "name" to "ΧΩΡΙΣ ΜΗΚΟΣ",
                                            "sequence" to 3,
                                            "latitude" to 40.63,
                                        ), // no longitude, dropped
                                    ),
                            ),
                        )
                }
            val stops = repo(api).getVariantStops(variant)
            assertEquals(listOf("S1", "S2"), stops.map { it.id })
        }

    @Test
    fun routeGeometry_parsesWktShape() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    routeInfo =
                        envelope(
                            jsonObj(
                                "shape" to
                                    jsonObj(
                                        "id" to "sh2",
                                        "lineString" to "LINESTRING (22.96 40.61, 22.97 40.62)",
                                    ),
                            ),
                        )
                }
            assertEquals(
                listOf(GeoPoint(40.61, 22.96), GeoPoint(40.62, 22.97)),
                repo(api).getRouteGeometry(variant),
            )
        }

    @Test
    fun routeInfo_fetchedOncePerVariant_stopsAndGeometryShareTheResponse() =
        runBlocking {
            // getVariantStops and getRouteGeometry are separate interface calls
            // that hit the same endpoint. The in-memory cache must dedupe them
            // (the offline prefetch halved its routeInfo calls because of this).
            val api =
                FakeOseThApi().apply {
                    routeInfo =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "S1",
                                            "name" to "ΑΓΟΡΑ",
                                            "sequence" to 1,
                                            "latitude" to 40.61,
                                            "longitude" to 22.96,
                                        ),
                                    ),
                                "shape" to
                                    jsonObj(
                                        "id" to "sh2",
                                        "lineString" to "LINESTRING (22.96 40.61, 22.97 40.62)",
                                    ),
                            ),
                        )
                }
            val repo = repo(api)

            assertEquals(1, repo.getVariantStops(variant).size)
            assertEquals(2, repo.getRouteGeometry(variant).size)
            assertEquals(1, api.routeInfoCalls)

            // Repeat visits stay on the cache too.
            assertEquals(1, repo.getVariantStops(variant).size)
            assertEquals(2, repo.getRouteGeometry(variant).size)
            assertEquals(1, api.routeInfoCalls)
        }

    // ---------- observeArrivals (the monitored quirk) ----------

    @Test
    fun arrivals_onlyMonitoredTrips_joinVehicleAndLines() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    stopTimetableResponse =
                        envelope(
                            jsonObj(
                                "trips" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "t1",
                                            "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                            "monitored" to true,
                                            "arrivalInMinutes" to 2,
                                            "arrivalTime" to "12:34",
                                            "route" to
                                                jsonObj(
                                                    "id" to "01_7429_1_3",
                                                    "shortName" to "01",
                                                    "longName" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                                ),
                                            "vehicle" to
                                                jsonObj(
                                                    "id" to "V1",
                                                    "latitude" to 40.61,
                                                    "longitude" to 22.96,
                                                    "bearing" to 90.0,
                                                ),
                                        ),
                                        // The rest of the day's schedule is not live and must not leak into arrivals.
                                        jsonObj(
                                            "id" to "t2",
                                            "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                            "monitored" to false,
                                            "departureInMinutes" to 15,
                                        ),
                                        // Monitored but no minutes, so no ETA. Dropped.
                                        jsonObj("id" to "t3", "monitored" to true),
                                    ),
                            ),
                        )
                }
            val lines = listOf(Line(Provider.OSETh, "01_7429_1_3", "01", "ΚΑΤΩ ΤΟΥΜΠΑ"))
            val arrivals = repo(api).observeArrivals("S1", lines).first()

            assertEquals(1, arrivals.size)
            val a = arrivals[0]
            assertEquals(2, a.etaMinutes)
            assertEquals("12:34", a.scheduledTime)
            assertEquals("t1", a.tripId)
            assertEquals("01", a.lineShortName)
            assertEquals("ΚΑΤΩ ΤΟΥΜΠΑ", a.destination)
            assertEquals("V1", a.vehicle!!.vehicleId)
            assertEquals(90f, a.vehicle.heading!!, 1e-6f)
        }

    @Test
    fun arrivals_zeroZeroCoordinates_dropVehicleKeepArrival() =
        runBlocking {
            // latitude/longitude 0.0 = no GPS fix (same convention as CityBus):
            // the arrival survives (the bus is coming), only the position is
            // dropped, and the row then shows the no-location icon.
            val api =
                FakeOseThApi().apply {
                    stopTimetableResponse =
                        envelope(
                            jsonObj(
                                "trips" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "t1",
                                            "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                            "monitored" to true,
                                            "arrivalInMinutes" to 2,
                                            "route" to
                                                jsonObj(
                                                    "id" to "01_7429_1_3",
                                                    "shortName" to "01",
                                                    "longName" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                                ),
                                            "vehicle" to
                                                jsonObj(
                                                    "id" to "V1",
                                                    "latitude" to 0.0,
                                                    "longitude" to 0.0,
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }
            val lines = listOf(Line(Provider.OSETh, "01_7429_1_3", "01", "ΚΑΤΩ ΤΟΥΜΠΑ"))
            val arrivals = repo(api).observeArrivals("S1", lines).first()

            assertEquals(1, arrivals.size)
            assertEquals("t1", arrivals[0].tripId)
            assertNull(arrivals[0].vehicle) // never a bogus marker at (0, 0)
        }

    @Test
    fun vehicles_zeroZeroCoordinates_dropped() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    routeInfo =
                        envelope(
                            jsonObj(
                                "vehicles" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "V1",
                                            "latitude" to 0.0,
                                            "longitude" to 0.0,
                                        ), // no GPS fix
                                        jsonObj(
                                            "id" to "V2",
                                            "latitude" to 40.61,
                                            "longitude" to 22.96,
                                            "bearing" to 90.0,
                                        ),
                                    ),
                            ),
                        )
                }
            val vehicles = repo(api).observeVehicles(variant).first()

            assertEquals(listOf("V2"), vehicles.map { it.vehicleId })
        }

    @Test
    fun arrivals_dateParamIsFormatted_ddMMyyyy() =
        runBlocking {
            val api = FakeOseThApi()
            repo(api).observeArrivals("S1", emptyList()).first()
            assertTrue(
                api.lastStopTimetableDate!!.matches(
                    Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}"),
                ),
            )
        }

    // ---------- getStopTimetable (the opposite of arrivals) ----------

    @Test
    fun stopTimetable_keepsEveryTrip_includingNonMonitored() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    stopTimetableResponse =
                        envelope(
                            jsonObj(
                                "trips" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "t2",
                                            "departureTime" to "14:30:00",
                                            "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                            "monitored" to false,
                                            "route" to
                                                jsonObj(
                                                    "shortName" to "01",
                                                    "longName" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                                ),
                                        ),
                                        jsonObj(
                                            "id" to "t1",
                                            "arrivalTime" to "08:00:00",
                                            "headsign" to "ΝΕΑ ΕΛΒΕΤΙΑ",
                                            "route" to
                                                jsonObj(
                                                    "shortName" to "01",
                                                    "longName" to "ΝΕΑ ΕΛΒΕΤΙΑ",
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }
            val entries = repo(api).getStopTimetable("S1", DayOfWeek.MONDAY)

            // Sorted by time. "HH:mm:ss" truncated to "HH:mm".
            // Non-monitored kept.
            assertEquals(listOf("08:00", "14:30"), entries.map { it.departureTime })
            assertEquals(2, entries.size)
        }

    @Test
    fun stopTimetable_decodeFailure_propagates() {
        // A decode failure must not become a cached 24 h "no trips" board.
        // the empty-trips shape is the only legit empty.
        val api =
            FakeOseThApi().apply {
                stopTimetableResponse = envelope(jsonObj("trips" to jsonObj("bad" to 1)))
                // object where a list is expected
            }
        assertThrows(SerializationException::class.java) {
            runBlocking { repo(api).getStopTimetable("S1", DayOfWeek.MONDAY) }
        }
    }

    @Test
    fun stopTimetable_dateIsSelectedWeekdayAtMidnight() =
        runBlocking {
            val api = FakeOseThApi()
            repo(api).getStopTimetable("S1", DayOfWeek.WEDNESDAY)
            // The service day of the passed date: next occurrence of the weekday.
            assertTrue(api.lastStopTimetableDate!!.matches(Regex("\\d{2}/\\d{2}/\\d{4} 00:00:00")))
        }

    @Test
    fun getLines_passesLanguageQueryParam() =
        runBlocking {
            // The `language` query param must go on every call (interface
            // contract). /route feeds the line names and direction labels.
            val api =
                FakeOseThApi().apply {
                    routes = envelope(jsonObj("routes" to jsonArr(), "total" to 0))
                }
            repo(api, lang = "en").getLines()
            assertEquals("en", api.lastRoutesLanguage)
        }

    @Test
    fun stopTimetable_missingEnvelope_propagates() {
        // A malformed/error envelope must not become a cached 24 h "no
        // trips" board. Only a verified {"trips": []} is a legit empty
        // (same rule as the route catalog).
        val api = FakeOseThApi().apply { stopTimetableResponse = jsonObj("error" to "nope") }
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getStopTimetable("S1", DayOfWeek.MONDAY) }
        }
    }

    @Test
    fun stopTimetable_genuineEmpty_returnsEmpty() =
        runBlocking {
            // {"trips": []} is the verified semantic-empty (a day without
            // service). The schedule board shows "no trips", no error.
            val api = FakeOseThApi().apply { stopTimetableResponse = envelope(jsonObj("trips" to jsonArr())) }
            assertEquals(emptyList<TimetableEntry>(), repo(api).getStopTimetable("S1", DayOfWeek.MONDAY))
        }

    @Test
    fun lineTimetable_allShapesMissingEnvelope_propagates() {
        // Every shape answered malformed/error. That's an outage, not "the
        // line doesn't run that day", so propagating beats caching a fake
        // 24 h "no trips" board.
        val api =
            FakeOseThApi().apply {
                routes =
                    envelope(
                        jsonObj(
                            "routes" to
                                jsonArr(
                                    jsonObj(
                                        "id" to "01_7429_1_3",
                                        "shortName" to "01",
                                        "tripHeadsigns" to
                                            jsonArr(
                                                jsonObj(
                                                    "routeId" to "01_7429_1_3",
                                                    "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ",
                                                    "shapeId" to "5300",
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    )
                routeTimetableByShape["5300"] = jsonObj("error" to "nope") // missing envelope
            }
        val weekdayShape =
            LineVariant(
                provider = Provider.OSETh,
                lineId = "01_7429_1_3",
                id = "01_7429_1_3",
                shapeId = "5300",
                label = "Τ.Σ. ΕΥΚΑΡΠΙΑΣ",
                lineShortName = "01",
            )
        assertThrows(IOException::class.java) {
            runBlocking { repo(api).getLineTimetable(weekdayShape, DayOfWeek.MONDAY) }
        }
    }

    @Test
    fun lineTimetable_shapeMissingEnvelope_stillFallsBackToSibling() =
        runBlocking {
            // A dead own-shape endpoint must not abort the lookup. The
            // same-direction sibling is still tried, since a transient failure
            // on one shape is not "the line doesn't run that day".
            val api =
                FakeOseThApi().apply {
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "01_7429_1_3",
                                            "shortName" to "01",
                                            "tripHeadsigns" to
                                                jsonArr(
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ",
                                                        "shapeId" to "5300",
                                                    ),
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - ΣΑΒΒΑΤΟ",
                                                        "shapeId" to "5304",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                    routeTimetableByShape["5300"] = jsonObj("error" to "nope") // own shape: dead endpoint
                    routeTimetableByShape["5304"] =
                        envelope(
                            jsonObj(
                                "shortName" to "01",
                                "trips" to jsonArr(jsonObj("id" to "t1", "departureTime" to "05:35:00")),
                            ),
                        )
                }
            val weekdayShape =
                LineVariant(
                    provider = Provider.OSETh,
                    lineId = "01_7429_1_3",
                    id = "01_7429_1_3",
                    shapeId = "5300",
                    label = "Τ.Σ. ΕΥΚΑΡΠΙΑΣ",
                    lineShortName = "01",
                )
            val entries = repo(api).getLineTimetable(weekdayShape, DayOfWeek.SATURDAY)

            assertEquals(listOf("05:35"), entries.map { it.departureTime })
            assertEquals(listOf("5300", "5304"), api.routeTimetableShapeCalls)
        }

    // ---------- getLineTimetable ----------

    @Test
    fun lineTimetable_noShapeId_returnsEmpty() =
        runBlocking {
            val api = FakeOseThApi()
            val noShape = variant.copy(shapeId = null)
            assertTrue(repo(api).getLineTimetable(noShape, DayOfWeek.MONDAY).isEmpty())
        }

    @Test
    fun lineTimetable_passesShapeIdAndFormatsEntries() =
        runBlocking {
            val api =
                FakeOseThApi().apply {
                    routeTimetableResponse =
                        envelope(
                            jsonObj(
                                "id" to "01_7429_1_3",
                                "shortName" to "01",
                                "longName" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                "trips" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "t1",
                                            "departureTime" to "06:10:00",
                                            "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                        ),
                                        jsonObj(
                                            "id" to "t2",
                                            "departureTime" to "05:40:00",
                                            "headsign" to "ΚΑΤΩ ΤΟΥΜΠΑ",
                                        ),
                                    ),
                            ),
                        )
                }
            val entries = repo(api).getLineTimetable(variant, DayOfWeek.SUNDAY)

            assertEquals("sh2", api.lastRouteTimetableShapeId)
            assertTrue(api.lastRouteTimetableDate!!.matches(Regex("\\d{2}/\\d{2}/\\d{4} 00:00:00")))
            assertEquals(listOf("05:40", "06:10"), entries.map { it.departureTime })
            assertEquals("01", entries[0].lineShortName) // from the dto
        }

    @Test
    fun lineTimetable_shapeWithoutService_fallsBackToSameDirectionSibling() =
        runBlocking {
            // A direction has one routeId with several shapes: the weekday
            // shape (5300) and the ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ shape (5304) serve different
            // days. The timetable must answer "does the line run that day".
            // an empty weekday shape on Saturday falls back to the weekend
            // shape of the same routeId (never the other direction).
            val api =
                FakeOseThApi().apply {
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "01_7429_1_3",
                                            "shortName" to "01",
                                            "tripHeadsigns" to
                                                jsonArr(
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.",
                                                        "shapeId" to "5300",
                                                    ),
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "headsign" to
                                                            "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.- ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ",
                                                        "shapeId" to "5304",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                    routeTimetableByShape["5300"] =
                        envelope(jsonObj("shortName" to "01", "trips" to jsonArr()))
                    routeTimetableByShape["5304"] =
                        envelope(
                            jsonObj(
                                "shortName" to "01",
                                "longName" to "Κ.Τ.Ε.Λ.",
                                "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.- ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ",
                                "trips" to
                                    jsonArr(jsonObj("id" to "t1", "departureTime" to "05:35:00")),
                            ),
                        )
                }
            val weekdayShape =
                LineVariant(
                    provider = Provider.OSETh,
                    lineId = "01_7429_1_3",
                    id = "01_7429_1_3",
                    shapeId = "5300",
                    label = "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ.",
                    lineShortName = "01",
                )
            val entries = repo(api).getLineTimetable(weekdayShape, DayOfWeek.SATURDAY)

            assertEquals(listOf("05:35"), entries.map { it.departureTime })
            // Own shape first, then the weekend sibling, and nothing else.
            assertEquals(listOf("5300", "5304"), api.routeTimetableShapeCalls)
        }

    @Test
    fun lineTimetable_shapeServesDay_noSiblingCalls() =
        runBlocking {
            // When the variant's own shape has trips for the day, siblings
            // must not be queried at all.
            val api =
                FakeOseThApi().apply {
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "01_7429_1_3",
                                            "shortName" to "01",
                                            "tripHeadsigns" to
                                                jsonArr(
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ",
                                                        "shapeId" to "5300",
                                                    ),
                                                    jsonObj(
                                                        "routeId" to "01_7429_1_3",
                                                        "headsign" to "Τ.Σ. ΕΥΚΑΡΠΙΑΣ - ΣΑΒΒΑΤΟ",
                                                        "shapeId" to "5304",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                    routeTimetableByShape["5300"] =
                        envelope(
                            jsonObj(
                                "shortName" to "01",
                                "trips" to
                                    jsonArr(jsonObj("id" to "t1", "departureTime" to "07:00:00")),
                            ),
                        )
                }
            val weekdayShape =
                LineVariant(
                    provider = Provider.OSETh,
                    lineId = "01_7429_1_3",
                    id = "01_7429_1_3",
                    shapeId = "5300",
                    label = "Τ.Σ. ΕΥΚΑΡΠΙΑΣ",
                    lineShortName = "01",
                )
            val entries = repo(api).getLineTimetable(weekdayShape, DayOfWeek.MONDAY)

            assertEquals(listOf("07:00"), entries.map { it.departureTime })
            assertEquals(listOf("5300"), api.routeTimetableShapeCalls)
        }

    @Test
    fun lineVariants_lineIdIsTheCatalogRouteId_notTheCallersLineId() =
        runBlocking {
            // From the arrivals screen the caller's Line.id is the
            // direction-specific route id (01_7429_2_3), from the Lines tab
            // it's the parent id (01_7429_1_3). The variant identity must be
            // the matched catalog route's id in both cases (favorites).
            val api =
                FakeOseThApi().apply {
                    routes =
                        envelope(
                            jsonObj(
                                "routes" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "01_7429_1_3",
                                            "shortName" to "01",
                                            "tripHeadsigns" to
                                                jsonArr(
                                                    jsonObj(
                                                        "routeId" to "01_7429_2_3",
                                                        "headsign" to "Κ.Τ.Ε.Λ",
                                                        "shapeId" to "5284",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }
            val repo = OseThRepository(api, testJson, { "el" })
            val fromLinesTab = Line(Provider.OSETh, "01_7429_1_3", "01", "")
            val fromArrivals = Line(Provider.OSETh, "01_7429_2_3", "01", "") // id = direction id

            val a = repo.getLineVariants(fromLinesTab)
            val b = repo.getLineVariants(fromArrivals)

            assertEquals(a, b) // one identity regardless of the entry path
            assertEquals("01_7429_1_3", a[0].lineId)
        }

    @Test
    fun observeArrivals_decodeFailure_propagates() {
        // A changed API shape must surface as a poll failure, never as a
        // false "Καμία άφιξη" empty emission.
        val api =
            FakeOseThApi().apply {
                stopTimetableResponse = envelope(jsonObj("trips" to "bogus"))
            }
        val thrown =
            runBlocking {
                runCatching {
                    repo(
                        api,
                    ).observeArrivals("36108", emptyList()).first()
                }.exceptionOrNull()
            }
        assertTrue(thrown is SerializationException)
    }

    @Test
    fun emptyRoutesCatalog_notCached_retriesOnNextAccess() =
        runBlocking {
            // A 200 with {"routes": []} must not pin an empty Lines tab for
            // the process. The next access refetches (same rule as the OASA
            // lines catalog and the disk cache's empty-never-cached).
            val api = FakeOseThApi() // default routes fixture = empty
            val r = repo(api)
            assertTrue(r.getLines().isEmpty())

            api.routes =
                envelope(
                    jsonObj(
                        "routes" to jsonArr(jsonObj("id" to "01_7429_1_3", "shortName" to "01")),
                        "total" to 1,
                    ),
                )
            assertEquals(listOf("01"), r.getLines().map { it.shortName })
        }

    // ---------- getStopCatalog: GTFS first, telematics walk as fallback ----------

    private fun gtfsCatalog(): OseThGtfsCatalog =
        GtfsHarness(tmp.newFolder("gtfs"))
            .apply { seedFirstBuild() }
            .catalog()

    @Test
    fun getStopCatalog_gtfsServes_theTelematicsWalkIsNotTouched() =
        runBlocking {
            val api = FakeOseThApi()
            val r = OseThRepository(api, testJson, { "el" }, gtfsCatalog = gtfsCatalog())

            val stops = r.getStopCatalog()

            assertEquals(listOf("10002", "2002", "3003"), stops.map { it.id })
            assertEquals("ΔΙΑΝΑ", stops[0].name)
            assertEquals(40.64041, stops[0].lat, 1e-9)
            assertEquals(22.94085, stops[0].lon, 1e-9)
            assertEquals(listOf("01", "2"), stops[1].servingLines) // badges
            assertEquals(emptyList<Int>(), api.stopCalls) // walk never touched
        }

    @Test
    fun getStopCatalog_gtfsEnglishMode_usesFeedTranslations() =
        runBlocking {
            val api = FakeOseThApi()
            val r = OseThRepository(api, testJson, { "en" }, gtfsCatalog = gtfsCatalog())

            val stops = r.getStopCatalog()

            // English = the feed's proper transliteration when present, the
            // Greek name otherwise (same fallback rule as the catalog's).
            assertEquals(
                listOf("DIANA", "KAFTANZOGLIO", "ΠΛ. ΑΓΟΡΑ, ΚΕΝΤΡΟ"),
                stops.map { it.name },
            )
        }

    @Test
    fun getStopCatalog_gtfsUnavailable_fallsBackToTheWalk() =
        runBlocking {
            // A dead data.gov.gr (no responses at all) must degrade to the
            // telematics walk, never to an empty city or a crash.
            val api =
                FakeOseThApi().apply {
                    stopsPages[1] =
                        envelope(
                            jsonObj(
                                "stops" to
                                    jsonArr(
                                        jsonObj(
                                            "id" to "W1",
                                            "name" to "ΑΠΟ ΤΟΝ ΠΕΡΙΠΑΤΟ",
                                            "latitude" to 40.61,
                                            "longitude" to 22.96,
                                            "routes" to jsonArr(jsonObj("shortName" to "01")),
                                        ),
                                    ),
                                "total" to 1,
                            ),
                        )
                }
            val r =
                OseThRepository(
                    api,
                    testJson,
                    { "el" },
                    gtfsCatalog = GtfsHarness(tmp.newFolder("gtfs")).catalog(), // nothing seeded
                )

            val stops = r.getStopCatalog()

            assertEquals(listOf("W1"), stops.map { it.id })
            assertEquals(listOf("01"), stops[0].servingLines)
            assertEquals(listOf(1), api.stopCalls) // the walk served
        }
}
