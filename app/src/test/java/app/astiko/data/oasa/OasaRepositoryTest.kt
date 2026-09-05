package app.astiko.data.oasa

import app.astiko.data.jsonArr
import app.astiko.data.jsonObj
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.oasaEmpty
import app.astiko.data.testJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Hand-rolled OasaApi: feeds wire-shaped JSON without any network. */
class FakeOasaApi : OasaApi {
    var closestStops: JsonElement = oasaEmpty()
    var routesForStop: JsonElement = oasaEmpty()
    var routesForStopError: IOException? = null
    var routesForStopCalls = 0
    var stopArrivals: JsonElement = oasaEmpty()
    var masterLines: JsonElement = oasaEmpty()
    var lines: JsonElement = oasaEmpty()
    var linesCalls = 0

    /** When set, [lines] suspends on it, letting a test hold the catalog
     *  fetch open and fire concurrent callers at the shared-fetch join. */
    var linesGate: CompletableDeferred<Unit>? = null
    var routesForLine: (String) -> JsonElement = { oasaEmpty() }
    var stopsForRoute: JsonElement = oasaEmpty()
    var routeDetails: JsonElement = oasaEmpty()
    var busLocation: suspend (String) -> JsonElement = { oasaEmpty() }

    override suspend fun getClosestStops(
        lat: Double,
        lon: Double,
    ): JsonElement = closestStops

    override suspend fun getRoutesForStop(stopCode: String): JsonElement {
        routesForStopCalls++
        routesForStopError?.let { throw it }
        return routesForStop
    }

    override suspend fun getStopArrivals(stopCode: String): JsonElement = stopArrivals

    override suspend fun getMasterLines(): JsonElement = masterLines

    override suspend fun getLines(): JsonElement {
        linesCalls++
        linesGate?.await()
        return lines
    }

    override suspend fun getRoutesForLine(lineCode: String): JsonElement = routesForLine(lineCode)

    override suspend fun getStopsForRoute(routeCode: String): JsonElement = stopsForRoute

    override suspend fun getRouteDetailsAndStops(routeCode: String): JsonElement = routeDetails

    override suspend fun getBusLocation(routeCode: String): JsonElement = busLocation(routeCode)
}

class OasaRepositoryTest {
    private fun repo(lang: String = "el") = OasaRepository(FakeOasaApi(), testJson, { lang })

    private val variant =
        LineVariant(
            provider = Provider.OASA,
            lineId = "LC1",
            id = "R1",
            label = "ΠΡΟΣ ΚΕΝΤΡΟ",
            lineShortName = "021",
        )

    // ---------- getStopsNear ----------

    @Test
    fun stopsNear_parsesAndSortsByDistance() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S2",
                                "StopDescr" to "ΜΑΚΡΙΑ",
                                "StopLat" to "37.9800",
                                "StopLng" to "23.7400",
                            ),
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopLat" to "37.9757",
                                "StopLng" to "23.7341",
                            ),
                        )
                }
            val r = OasaRepository(api, testJson, { "el" })
            val stops = r.getStopsNear(37.9757, 23.7341, 10)

            assertEquals(listOf("S1", "S2"), stops.map { it.id })
            assertEquals("ΑΓΟΡΑ", stops[0].name)
            assertEquals(0.0, stops[0].distanceKm!!, 1e-6)
        }

    @Test
    fun stopsNear_emptyStringResponse_returnsEmpty() =
        runBlocking {
            // The classic OASA quirk: `""` when nothing is running.
            assertEquals(emptyList<Stop>(), repo().getStopsNear(37.97, 23.73, 10))
        }

    @Test
    fun stopsNear_dropsStopsWithoutCoordinates() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΟΚ",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                            jsonObj(
                                "StopCode" to "S2",
                                "StopDescr" to "ΧΩΡΙΣ LAT",
                                "StopLng" to "23.73",
                            ),
                            jsonObj(
                                "StopCode" to "S3",
                                "StopDescr" to "ΣΚΟΥΠΙΔΙΑ",
                                "StopLat" to "abc",
                                "StopLng" to "23.73",
                            ),
                        )
                }
            val stops = OasaRepository(api, testJson, { "el" }).getStopsNear(37.97, 23.73, 10)
            assertEquals(listOf("S1"), stops.map { it.id })
        }

    @Test
    fun stopsNear_englishLocale_picksEngFields_withGreekFallback() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            // Both fields present: transliteration wins in English.
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopDescrEng" to "AGORA",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                            // Eng missing (e.g. StopStreetEng is often null): Greek fallback.
                            jsonObj(
                                "StopCode" to "S2",
                                "StopDescr" to "ΠΛΑΤΕΙΑ",
                                "StopStreet" to "ΟΔΟΣ",
                                "StopLat" to "37.98",
                                "StopLng" to "23.74",
                            ),
                        )
                }
            val en = OasaRepository(api, testJson, { "en" }).getStopsNear(37.97, 23.73, 10)
            assertEquals("AGORA", en[0].name)
            assertEquals("ΠΛΑΤΕΙΑ", en[1].name)
            assertEquals("ΟΔΟΣ", en[1].street) // StopStreetEng null -> Greek street

            val el = OasaRepository(api, testJson, { "el" }).getStopsNear(37.97, 23.73, 10)
            assertEquals("ΑΓΟΡΑ", el[0].name)
        }

    @Test
    fun stopsNear_servingLines_enrichedPerStop() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                        )
                    routesForStop =
                        jsonArr(
                            jsonObj("RouteCode" to "R1", "LineID" to "040", "hidden" to "0"),
                            jsonObj("RouteCode" to "R2", "LineID" to "040", "hidden" to "0"),
                            jsonObj("RouteCode" to "R3", "LineID" to "2", "hidden" to "1"),
                            // Hidden routes do not become badges.
                        )
                }
            val stops = OasaRepository(api, testJson, { "el" }).getStopsNear(37.97, 23.73, 10)
            assertEquals(listOf("040"), stops[0].servingLines)
        }

    @Test
    fun stopsNear_badgeFetchFailure_dropsOnlyThatStopsBadges() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                            jsonObj(
                                "StopCode" to "S2",
                                "StopDescr" to "ΠΛΑΤΕΙΑ",
                                "StopLat" to "37.98",
                                "StopLng" to "23.74",
                            ),
                        )
                    routesForStopError = IOException("badges down")
                }
            val stops = OasaRepository(api, testJson, { "el" }).getStopsNear(37.97, 23.73, 10)

            // Both stops survive. The broken enrichment only costs the badges.
            assertEquals(listOf("S1", "S2"), stops.map { it.id })
            assertEquals(emptyList<String>(), stops[0].servingLines)
        }

    @Test
    fun stopsNear_badgeLinesCached_secondRefreshMakesNoBadgeCalls() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                            jsonObj(
                                "StopCode" to "S2",
                                "StopDescr" to "ΠΛΑΤΕΙΑ",
                                "StopLat" to "37.98",
                                "StopLng" to "23.74",
                            ),
                        )
                    routesForStop = jsonArr(jsonObj("RouteCode" to "R1", "LineID" to "040"))
                }
            val r = OasaRepository(api, testJson, { "el" })

            val first = r.getStopsNear(37.97, 23.73, 10)
            assertEquals(listOf("040"), first[0].servingLines)
            val callsAfterFirst = api.routesForStopCalls

            // Same area again: the badge enrichment is cached, zero new calls.
            val second = r.getStopsNear(37.97, 23.73, 10)
            assertEquals(listOf("040"), second[0].servingLines)
            assertEquals(callsAfterFirst, api.routesForStopCalls)
        }

    @Test
    fun stopsNear_badgeFailure_notCached_retriedOnNextRefresh() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                        )
                    routesForStopError = IOException("badges down")
                }
            val r = OasaRepository(api, testJson, { "el" })
            assertEquals(emptyList<String>(), r.getStopsNear(37.97, 23.73, 10)[0].servingLines)

            // The API heals: the failed enrichment must not be cached, so the
            // next refresh retries and the badges appear.
            api.routesForStopError = null
            api.routesForStop = jsonArr(jsonObj("RouteCode" to "R1", "LineID" to "040"))
            assertEquals(listOf("040"), r.getStopsNear(37.97, 23.73, 10)[0].servingLines)
        }

    @Test
    fun stopsNear_limitIsApplied() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "1",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                            jsonObj(
                                "StopCode" to "S2",
                                "StopDescr" to "2",
                                "StopLat" to "37.98",
                                "StopLng" to "23.74",
                            ),
                            jsonObj(
                                "StopCode" to "S3",
                                "StopDescr" to "3",
                                "StopLat" to "37.99",
                                "StopLng" to "23.75",
                            ),
                        )
                }
            val stops = OasaRepository(api, testJson, { "el" }).getStopsNear(37.97, 23.73, 2)
            assertEquals(listOf("S1", "S2"), stops.map { it.id })
        }

    // ---------- getLines ----------

    @Test
    fun lines_sortedByShortName() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    masterLines =
                        jsonArr(
                            jsonObj(
                                "line_code" to "LC1",
                                "ml_id" to "040",
                                "ml_descr" to "ΠΕΙΡΑΙΑΣ",
                            ),
                            jsonObj("line_code" to "LC2", "ml_id" to "2", "ml_descr" to "ΚΥΨΕΛΗ"),
                            jsonObj(
                                "line_code" to "LC3",
                                "ml_id" to "01N",
                                "ml_descr" to "ΝΥΧΤΕΡΙΝΗ",
                            ),
                            jsonObj("ml_id" to "X"), // no line_code, dropped
                        )
                }
            val lines = OasaRepository(api, testJson, { "el" }).getLines()
            assertEquals(listOf("01N", "2", "040"), lines.map { it.shortName })
            assertEquals("LC2", lines[1].id)
            assertEquals("ΚΥΨΕΛΗ", lines[1].longName)
        }

    @Test
    fun getLines_emptyResponse_propagates() {
        // The master-lines catalog must never legitimately be empty (191
        // records). A `""` response is an outage, a retryable error, not
        // a fake empty city (same rule as the OSETh route catalog).
        val api = FakeOasaApi() // masterLines default = `""`
        assertThrows(IOException::class.java) {
            runBlocking { OasaRepository(api, testJson, { "el" }).getLines() }
        }
    }

    @Test
    fun lineVariants_catalogFailure_propagates() {
        // A dead webGetLines must fail the direction sheet with a retryable
        // error. Swallowing the failure into an empty list would answer
        // "no directions" on an outage.
        val api = FakeOasaApi() // lines default = `""`
        val line = Line(Provider.OASA, "LC1", "021", "ΑΝΩ ΚΥΨΕΛΗ")
        assertThrows(IOException::class.java) {
            runBlocking { OasaRepository(api, testJson, { "el" }).getLineVariants(line) }
        }
    }

    // ---------- getLineVariants (the direction inconsistency quirk) ----------

    @Test
    fun lineVariants_collectsActiveRoutesOfAllLineEntries() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    // Line 021 has one entry per direction. Both must be collected.
                    lines =
                        jsonArr(
                            jsonObj(
                                "LineCode" to "LC1",
                                "LineID" to "021",
                                "LineDescr" to "ΑΝΩ ΚΥΨΕΛΗ",
                            ),
                            jsonObj(
                                "LineCode" to "LC2",
                                "LineID" to "021",
                                "LineDescr" to "ΚΑΤΩ ΚΥΨΕΛΗ",
                            ),
                            jsonObj(
                                "LineCode" to "LCO",
                                "LineID" to "040",
                                "LineDescr" to "ΑΛΛΗ ΓΡΑΜΜΗ",
                            ), // not 021
                        )
                    routesForLine = { lineCode ->
                        when (lineCode) {
                            "LC1" -> {
                                jsonArr(
                                    jsonObj(
                                        "route_code" to "R1",
                                        "route_active" to "1",
                                        "route_descr" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                                    ),
                                )
                            }

                            "LC2" -> {
                                jsonArr(
                                    jsonObj(
                                        "route_code" to "R2",
                                        "route_active" to "1",
                                        "route_descr" to "ΠΡΟΣ ΤΕΡΜΑ",
                                    ),
                                    jsonObj(
                                        "route_code" to "R3",
                                        "route_active" to "0",
                                        "route_descr" to "ΑΝΕΝΕΡΓΟ",
                                    ), // inactive, dropped
                                    jsonObj("route_code" to "R1", "route_active" to "1"),
                                    // Duplicate of LC1's route, deduped.
                                )
                            }

                            else -> {
                                oasaEmpty()
                            }
                        }
                    }
                }
            val line = Line(Provider.OASA, "LC1", "021", "ΑΝΩ ΚΥΨΕΛΗ")
            val variants = OasaRepository(api, testJson, { "el" }).getLineVariants(line)

            assertEquals(listOf("R1", "R2"), variants.map { it.id })
            assertEquals("ΠΡΟΣ ΚΕΝΤΡΟ", variants[0].label)
        }

    @Test
    fun lineVariants_noLineEntries_returnsEmpty() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    lines =
                        jsonArr(jsonObj("LineCode" to "LC1", "LineID" to "040"))
                }
            val line = Line(Provider.OASA, "LC1", "021", "")
            assertEquals(
                emptyList<LineVariant>(),
                OasaRepository(api, testJson, {
                    "el"
                }).getLineVariants(line),
            )
        }

    @Test
    fun lineVariants_combinedPublicNumber_matchesByDashParts() =
        runBlocking {
            // The master list merges two lines into one public number
            // ("219-816"), which never appears in webGetLines. Each part
            // lives under its own LineID/line_code.
            val api =
                FakeOasaApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "LineCode" to "LC219",
                                "LineID" to "219",
                                "LineDescr" to "ΣΤ. ΚΑΛΛΙΘΕΑ",
                            ),
                            jsonObj(
                                "LineCode" to "LC816",
                                "LineID" to "816",
                                "LineDescr" to "ΤΑΥΡΟΣ",
                            ),
                            jsonObj(
                                "LineCode" to "LC040",
                                "LineID" to "040",
                                "LineDescr" to "ΑΛΛΗ",
                            ),
                        )
                    routesForLine = { lineCode ->
                        when (lineCode) {
                            "LC219" -> {
                                jsonArr(
                                    jsonObj(
                                        "route_code" to "R219",
                                        "route_active" to "1",
                                        "route_descr" to "ΠΡΟΣ ΑΓ. ΔΗΜΗΤΡΙΟ",
                                    ),
                                )
                            }

                            "LC816" -> {
                                jsonArr(
                                    jsonObj(
                                        "route_code" to "R816",
                                        "route_active" to "1",
                                        "route_descr" to "ΠΡΟΣ ΤΑΥΡΟ",
                                    ),
                                )
                            }

                            else -> {
                                oasaEmpty()
                            }
                        }
                    }
                }
            val line = Line(Provider.OASA, "LC219", "219-816", "ΣΤ. ΚΑΛΛΙΘΕΑ - ΤΑΥΡΟΣ")
            val variants = OasaRepository(api, testJson, { "el" }).getLineVariants(line)

            assertEquals(listOf("R219", "R816"), variants.map { it.id })
        }

    @Test
    fun lineVariants_concurrentCallers_shareOneLinesFetch() =
        runBlocking {
            // The lines catalog uses one shared fetch: the network runs
            // outside the mutex and concurrent callers await the same fetch.
            // Holding the mutex across webGetLines would serialize every badge
            // fetch and getLineVariants behind the catalog fetch.
            val gate = CompletableDeferred<Unit>()
            val api =
                FakeOasaApi().apply {
                    linesGate = gate
                    lines =
                        jsonArr(
                            jsonObj(
                                "LineCode" to "LC1",
                                "LineID" to "021",
                                "LineDescr" to "ΑΝΩ ΚΥΨΕΛΗ",
                            ),
                        )
                    routesForLine = { lineCode ->
                        jsonArr(
                            jsonObj(
                                "route_code" to "R1",
                                "route_active" to "1",
                                "route_descr" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                            ),
                        )
                    }
                }
            val repository = OasaRepository(api, testJson, { "el" })
            val line = Line(Provider.OASA, "LC1", "021", "ΑΝΩ ΚΥΨΕΛΗ")

            // Two concurrent callers reach the join while the fetch is held
            // open on the gate (the fetch runs on the repository's scope).
            val results = (1..2).map { async { repository.getLineVariants(line) } }
            gate.complete(Unit)
            val all = results.awaitAll()

            assertEquals(2, all.size)
            assertEquals(1, api.linesCalls)
            assertEquals(listOf("R1"), all[0].map { it.id })
        }

    @Test
    fun lineVariants_lineIdIsTheCatalogEntryCode_notTheCallersLineId() =
        runBlocking {
            // The favorite identity must not depend on the entry path: from the
            // arrivals screen the caller's Line.id is the route_code, from the
            // Lines tab it's the line_code. The variant's lineId must be the
            // matched catalog entry's code in both cases.
            val api =
                FakeOasaApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "LineCode" to "LC1",
                                "LineID" to "021",
                                "LineDescr" to "ΑΝΩ ΚΥΨΕΛΗ",
                            ),
                        )
                    routesForLine =
                        {
                            jsonArr(
                                jsonObj(
                                    "route_code" to "R1",
                                    "route_active" to "1",
                                    "route_descr" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                                ),
                            )
                        }
                }
            val repo = OasaRepository(api, testJson, { "el" })
            val fromLinesTab = Line(Provider.OASA, "LC1", "021", "ΑΝΩ ΚΥΨΕΛΗ")
            val fromArrivals = Line(Provider.OASA, "R1", "021", "ΑΝΩ ΚΥΨΕΛΗ") // id = route_code

            val a = repo.getLineVariants(fromLinesTab)
            val b = repo.getLineVariants(fromArrivals)

            assertEquals(a, b) // one identity regardless of the entry path
            assertEquals("LC1", a[0].lineId)
        }

    @Test
    fun lineVariants_oneEntryNetworkFailure_keepsTheOtherDirections() =
        runBlocking {
            // One entry's failure must not fail the whole sheet. The other
            // directions still resolve (same per-item isolation as the
            // arrivals vehicle join).
            val api =
                FakeOasaApi().apply {
                    lines =
                        jsonArr(
                            jsonObj(
                                "LineCode" to "LC1",
                                "LineID" to "021",
                                "LineDescr" to "ΑΝΩ ΚΥΨΕΛΗ",
                            ),
                            jsonObj(
                                "LineCode" to "LC2",
                                "LineID" to "021",
                                "LineDescr" to "ΚΑΤΩ ΚΥΨΕΛΗ",
                            ),
                        )
                    routesForLine = { lineCode ->
                        if (lineCode == "LC1") throw IOException("network down")
                        jsonArr(
                            jsonObj(
                                "route_code" to "R2",
                                "route_active" to "1",
                                "route_descr" to "ΠΡΟΣ ΤΕΡΜΑ",
                            ),
                        )
                    }
                }
            val line = Line(Provider.OASA, "LC1", "021", "ΑΝΩ ΚΥΨΕΛΗ")
            val variants = OasaRepository(api, testJson, { "el" }).getLineVariants(line)
            assertEquals(listOf("R2"), variants.map { it.id })
        }

    @Test
    fun stopsNear_limitAppliedAfterDistanceSort() =
        runBlocking {
            // The API's arrival order is not a contract. The nearest stops
            // must win even when the response is not pre-sorted.
            val api =
                FakeOasaApi().apply {
                    closestStops =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "FAR",
                                "StopDescr" to "ΜΑΚΡΙΑ",
                                "StopLat" to "37.99",
                                "StopLng" to "23.75",
                            ),
                            jsonObj(
                                "StopCode" to "NEAR",
                                "StopDescr" to "ΚΟΝΤΑ",
                                "StopLat" to "37.9757",
                                "StopLng" to "23.7341",
                            ),
                        )
                }
            val stops = OasaRepository(api, testJson, { "el" }).getStopsNear(37.9757, 23.7341, 1)
            assertEquals(listOf("NEAR"), stops.map { it.id })
        }

    // ---------- getVariantStops / getRouteGeometry ----------

    @Test
    fun variantStops_parsesAndDropsIncomplete() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    stopsForRoute =
                        jsonArr(
                            jsonObj(
                                "StopCode" to "S1",
                                "StopDescr" to "ΑΓΟΡΑ",
                                "StopLat" to "37.97",
                                "StopLng" to "23.73",
                            ),
                            jsonObj("StopCode" to "S2", "StopDescr" to "ΧΩΡΙΣ ΘΕΣΗ"),
                        )
                }
            val stops = OasaRepository(api, testJson, { "el" }).getVariantStops(variant)
            assertEquals(listOf("S1"), stops.map { it.id })
        }

    @Test
    fun routeGeometry_parsesDetailsObject() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    routeDetails =
                        jsonObj(
                            "details" to
                                jsonArr(
                                    jsonObj("routed_y" to "37.97", "routed_x" to "23.73"),
                                    // lat, lon
                                    jsonObj("routed_y" to "37.98", "routed_x" to "23.74"),
                                ),
                            "stops" to jsonArr(),
                        )
                }
            assertEquals(
                listOf(GeoPoint(37.97, 23.73), GeoPoint(37.98, 23.74)),
                OasaRepository(api, testJson, { "el" }).getRouteGeometry(variant),
            )
        }

    @Test
    fun routeGeometry_emptyOrNonObject_returnsEmpty() =
        runBlocking {
            assertEquals(emptyList<GeoPoint>(), repo().getRouteGeometry(variant)) // "" response
            val api = FakeOasaApi().apply { routeDetails = jsonArr() } // array, not object
            assertEquals(
                emptyList<GeoPoint>(),
                OasaRepository(api, testJson, {
                    "el"
                }).getRouteGeometry(variant),
            )
        }

    // ---------- observeArrivals ----------

    @Test
    fun arrivals_vehicleJoin_fetchesRoutesInParallel() =
        runBlocking {
            // A busy stop involves more routes than one concurrency chunk.
            // the per-route getBusLocation calls must overlap, not serialize.
            // A sequential join would stall the poll on the sum of the
            // latencies.
            val routeCodes = (1..8).map { "R$it" }
            val inFlight =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val maxInFlight =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val api =
                FakeOasaApi().apply {
                    stopArrivals =
                        jsonArr(
                            *routeCodes
                                .map {
                                    jsonObj(
                                        "route_code" to it,
                                        "veh_code" to "V$it",
                                        "btime2" to "5",
                                    )
                                }.toTypedArray(),
                        )
                    busLocation = { routeCode ->
                        val now = inFlight.incrementAndGet()
                        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        delay(50) // widen the overlap window
                        inFlight.decrementAndGet()
                        oasaEmpty()
                    }
                }

            val arrivals =
                OasaRepository(
                    api,
                    testJson,
                    { "el" },
                ).observeArrivals("S1", emptyList()).first()

            val max = maxInFlight.get()
            assertEquals(8, arrivals.size)
            // A sequential join would never see more than one call in flight.
            assertTrue("expected parallel getBusLocation calls, max=$max", max > 1)
        }

    @Test
    fun arrivals_joinLinesAndVehicles_sortedByEta() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    stopArrivals =
                        jsonArr(
                            jsonObj("route_code" to "R1", "veh_code" to "V1", "btime2" to "3"),
                            jsonObj("route_code" to "R2", "veh_code" to "VX", "btime2" to "1"),
                        )
                    busLocation = { routeCode ->
                        if (routeCode == "R1") {
                            jsonArr(
                                jsonObj(
                                    "VEH_NO" to "V1",
                                    "CS_LAT" to "37.90",
                                    "CS_LNG" to "23.70",
                                    "VEH_HEADING" to "90",
                                ),
                                jsonObj(
                                    "VEH_NO" to "OTHER",
                                    "CS_LAT" to "37.91",
                                    "CS_LNG" to "23.71",
                                ),
                            )
                        } else {
                            oasaEmpty() // R2: nothing running
                        }
                    }
                }
            val lines =
                listOf(
                    Line(Provider.OASA, "R1", "040", "ΠΕΙΡΑΙΑΣ", destination = "ΣΥΝΤΑΓΜΑ"),
                )
            val arrivals =
                OasaRepository(
                    api,
                    testJson,
                    { "el" },
                ).observeArrivals("S1", lines).first()

            assertEquals(listOf("R2", "R1"), arrivals.map { it.routeCode }) // sorted by eta
            assertEquals(1, arrivals[0].etaMinutes)
            // Joined from the lines list.
            assertEquals("040", arrivals[1].lineShortName)
            assertEquals("ΠΕΙΡΑΙΑΣ", arrivals[1].lineName)
            assertEquals("ΣΥΝΤΑΓΜΑ", arrivals[1].destination)
            // Vehicle matched by VEH_NO == veh_code.
            val vehicle = arrivals[1].vehicle
            assertEquals("V1", vehicle!!.vehicleId)
            assertEquals(37.90, vehicle.lat, 1e-6)
            assertEquals(90f, vehicle.heading!!, 1e-6f)
            // R2's bus never showed up in getBusLocation, so no position.
            assertNull(arrivals[0].vehicle)
        }

    @Test
    fun arrivals_emptyStringResponse_emitsEmptyList() =
        runBlocking {
            assertTrue(repo().observeArrivals("S1", emptyList()).first().isEmpty())
        }

    @Test
    fun arrivals_malformedEntries_areSkipped() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    stopArrivals =
                        jsonArr(
                            jsonObj("route_code" to "R1", "veh_code" to "V1", "btime2" to "abc"),
                            // Bad minutes, dropped.
                            jsonObj("route_code" to "R2", "veh_code" to "V2", "btime2" to "5"),
                        )
                }
            val arrivals =
                OasaRepository(
                    api,
                    testJson,
                    { "el" },
                ).observeArrivals("S1", emptyList()).first()
            assertEquals(listOf("R2"), arrivals.map { it.routeCode })
        }

    @Test
    fun arrivals_zeroZeroCoordinates_dropVehicleKeepArrival() =
        runBlocking {
            // CS_LAT/CS_LNG "0"/"0" = no GPS fix (same convention as CityBus).
            // The arrival survives, the bus is coming, only the position is
            // dropped, so the row then shows the no-location icon.
            val api =
                FakeOasaApi().apply {
                    stopArrivals =
                        jsonArr(
                            jsonObj("route_code" to "R1", "veh_code" to "V1", "btime2" to "3"),
                        )
                    busLocation = {
                        jsonArr(
                            jsonObj("VEH_NO" to "V1", "CS_LAT" to "0", "CS_LNG" to "0"),
                            // no GPS fix
                            jsonObj("VEH_NO" to "V2", "CS_LAT" to "37.90", "CS_LNG" to "23.70"),
                        )
                    }
                }
            val arrivals =
                OasaRepository(
                    api,
                    testJson,
                    { "el" },
                ).observeArrivals("S1", emptyList()).first()

            assertEquals(1, arrivals.size)
            assertEquals("V1", arrivals[0].vehicleId)
            assertNull(arrivals[0].vehicle) // never a bogus marker at (0, 0)
        }

    // ---------- observeVehicles ----------

    @Test
    fun vehicles_zeroZeroCoordinates_dropped() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    busLocation = {
                        jsonArr(
                            jsonObj("VEH_NO" to "V1", "CS_LAT" to "0", "CS_LNG" to "0"),
                            // no GPS fix
                            jsonObj(
                                "VEH_NO" to "V2",
                                "CS_LAT" to "37.90",
                                "CS_LNG" to "23.70",
                                "VEH_HEADING" to "90",
                            ),
                        )
                    }
                }
            val variant = LineVariant(Provider.OASA, "R1", "R1", "ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ", "040")
            val vehicles = OasaRepository(api, testJson, { "el" }).observeVehicles(variant).first()

            assertEquals(listOf("V2"), vehicles.map { it.vehicleId })
        }

    // ---------- getStopRoutes / stopLinesForDisplay ----------

    @Test
    fun stopRoutes_hiddenRoutesAreNotReal() =
        runBlocking {
            val api =
                FakeOasaApi().apply {
                    routesForStop =
                        jsonArr(
                            jsonObj(
                                "RouteCode" to "R1",
                                "LineID" to "040",
                                "LineDescr" to "ΠΕΙΡΑΙΑΣ",
                                "RouteDescr" to "ΠΡΟΣ ΚΕΝΤΡΟ",
                                "hidden" to "0",
                            ),
                            jsonObj("RouteCode" to "RH", "LineID" to "040", "hidden" to "1"),
                        )
                }
            val lines = OasaRepository(api, testJson, { "el" }).getStopRoutes("S1")
            assertEquals(listOf("R1"), lines.map { it.id })
            assertEquals("040", lines[0].shortName)
            assertEquals("ΠΡΟΣ ΚΕΝΤΡΟ", lines[0].destination)
        }

    @Test
    fun stopLinesForDisplay_oneRowPerPublicLine() {
        val lines =
            listOf(
                Line(Provider.OASA, "R1", "040", "ΠΕΙΡΑΙΑΣ"),
                Line(Provider.OASA, "R2", "040", "ΠΕΙΡΑΙΑΣ"), // same public number, other direction
                Line(Provider.OASA, "R3", "2", "ΚΥΨΕΛΗ"),
            )
        val displayed = repo().stopLinesForDisplay(lines)
        assertEquals(listOf("2", "040"), displayed.map { it.shortName }) // numeric prefix first
        assertTrue(displayed.size == 2)
    }
}
