package app.astiko.data.oseth

import app.astiko.data.testJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The CKAN package_show endpoint, as the loader calls it (tests pin the
 *  wire contract: if the production constant changes, this breaks). */
internal const val TEST_CKAN_URL =
    "https://data.gov.gr/api/3/action/package_show?id=dedomena-astikon-sygkoinonion-p-e-thessalonikis"

/**
 * A tiny GTFS feed mirroring the real shape: BOM on every file, real
 * column orders, the `location_type` station row (dropped), a row
 * without coordinates (dropped), a quoted name with a comma (kept), and
 * one stop served by two lines in stop_times order 2-then-01 (badges
 * must come out natural-sorted: [01, 2]).
 */
internal fun gtfsFixtureZip(): ByteArray =
    zipOf(
        "stops.txt" to
            csv(
                "\uFEFFstop_id,stop_code,stop_name,stop_desc,stop_lat,stop_lon,zone_id,stop_url," +
                    "location_type,parent_station,stop_timezone,wheelchair_boarding,platform_code",
                "10002,10002,ΔΙΑΝΑ,,40.64041,22.94085,,,0,,,,",
                "2002,2002,ΚΑΦΤΑΝΖΟΓΛΕΙΟ,,40.62,22.96,,,0,,,,",
                "3003,3003,\"ΠΛ. ΑΓΟΡΑ, ΚΕΝΤΡΟ\",,40.63,22.99,,,0,,,,",
                "4004,4004,ΧΩΡΙΣ ΣΥΝΤΕΤΑΓΜΕΝΕΣ,,,22.98,,,0,,,,",
                "5005,5005,ΣΤΑΘΜΟΣ,,40.64,22.97,,,1,,,,",
            ) + "\n",
        "routes.txt" to
            csv(
                "\uFEFFroute_id,agency_id,route_short_name,route_long_name,route_desc,route_type," +
                    "route_url,route_color,route_text_color",
                "01_5351_1_3,OSETH,01,Τ.Σ. ΕΥΚΑΡΠΙΑΣ,,,,,",
                "02_5351_1_3,OSETH,2,ΑΛΛΗ,,,,,",
            ) + "\n",
        "trips.txt" to
            csv(
                "\uFEFFroute_id,service_id,trip_id,trip_headsign,trip_short_name,direction_id," +
                    "block_id,shape_id",
                "01_5351_1_3,1,t1,,,1,,,",
                "02_5351_1_3,1,t2,,,1,,,",
            ) + "\n",
        "stop_times.txt" to
            csv(
                "\uFEFFtrip_id,arrival_time,departure_time,stop_id,stop_sequence,stop_headsign," +
                    "pickup_type,drop_off_type,shape_dist_traveled,timepoint",
                "t1,10:00:00,10:00:00,10002,1,,0,1,,1",
                "t2,10:05:00,10:05:00,2002,1,,0,1,,1",
                "t1,10:10:00,10:10:00,2002,2,,0,1,,1",
            ) + "\n",
        "translations.txt" to
            csv(
                "\uFEFFtable_name,field_name,language,translation,record_id,record_sub_id," +
                    "field_value",
                "stops,stop_name,en-GB,DIANA,,,ΔΙΑΝΑ",
                "stops,stop_name,en-GB,KAFTANZOGLIO,,,ΚΑΦΤΑΝΖΟΓΛΕΙΟ",
                "routes,route_long_name,en-GB,POLITISTIKI GRAMMI,,,ΠΟΛΙΤΙΣΤΙΚΗ ΓΡΑΜΜΗ",
            ) + "\n",
    )

/** One CSV line per row string. */
private fun csv(vararg lines: String): String = lines.joinToString("\n")

/** The CKAN response body: one resource per (id, name) pair, each with a
 *  pointing `url` (the exact shape the loader's downloadUrl expects). */
internal fun ckanFixtureBody(vararg resources: Pair<String, String>): ByteArray =
    (
        "{\"success\":true,\"result\":{\"resources\":[" +
            resources.joinToString(",") { (id, name) ->
                "{\"id\":\"$id\",\"name\":\"$name\",\"created\":\"2026-01-01T00:00:00.000000\"," +
                    "\"format\":\"ZIP\",\"url\":\"https://example.com/feed/$id.zip\"}"
            } +
            "]}}"
    ).toByteArray()

internal fun zipOf(vararg entries: Pair<String, String>): ByteArray =
    ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        out.toByteArray()
    }

/** URL -> bytes fake transport. Unregistered URLs answer null (failure),
 *  `calls` records every request in order. */
internal class GtfsFetchStub {
    val calls = mutableListOf<String>()
    private val responses = mutableMapOf<String, ByteArray?>()

    fun respond(
        url: String,
        body: ByteArray?,
    ) {
        responses[url] = body
    }

    suspend fun fetch(url: String): ByteArray? {
        calls += url
        return responses[url]
    }
}

/** A catalog wired against a [GtfsFetchStub], with an injectable clock. */
internal class GtfsHarness(
    val dir: File,
    val fetch: GtfsFetchStub = GtfsFetchStub(),
    val now: () -> Long = { 0L },
    /** The tiny fixture feeds have 1-3 stops; the sparse test overrides
     *  this explicitly. */
    val minStops: Int = 1,
) {
    fun catalog(): OseThGtfsCatalog =
        OseThGtfsCatalog(
            json = testJson,
            dir = dir,
            fetch = fetch::fetch,
            now = now,
            minStops = minStops,
        )

    /** The responses for a first build: CKAN (newest = r-new) + the feed zip. */
    fun seedFirstBuild() {
        fetch.respond(
            TEST_CKAN_URL,
            ckanFixtureBody(
                "r-old" to "GTFS 05/03/2026-18/03/2026",
                "r-new" to "GTFS 06/08/2026-17/08/2026",
            ),
        )
        fetch.respond("https://example.com/feed/r-new.zip", gtfsFixtureZip())
    }
}

class OseThGtfsCatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun harness(
        minStops: Int = 1,
        now: () -> Long = { 0L },
    ): GtfsHarness = GtfsHarness(dir = tmp.newFolder("gtfs"), now = now, minStops = minStops)

    // ------------------------------------------------------------- build

    @Test
    fun firstBuild_parsesIdsNamesBadgesAndCoordinates() =
        runBlocking {
            val h = harness().also { it.seedFirstBuild() }

            val entries = h.catalog().load()

            assertNotNull(entries)
            assertEquals(listOf("10002", "2002", "3003"), entries!!.map { it.id })
            // Both languages from the index (English from translations).
            assertEquals("ΔΙΑΝΑ", entries[0].nameEl)
            assertEquals("DIANA", entries[0].nameEn)
            assertEquals("ΚΑΦΤΑΝΖΟΓΛΕΙΟ", entries[1].nameEl)
            assertEquals("KAFTANZOGLIO", entries[1].nameEn)
            // No translation -> Greek-only. The repository falls back.
            assertEquals("ΠΛ. ΑΓΟΡΑ, ΚΕΝΤΡΟ", entries[2].nameEl) // quoted comma kept
            assertNull(entries[2].nameEn)
            // Coordinates survive.
            assertEquals(40.64041, entries[0].lat, 1e-9)
            assertEquals(22.94085, entries[0].lon, 1e-9)
            // Badges: natural-sorted line short names, distinct.
            assertEquals(listOf("01"), entries[0].lines)
            assertEquals(listOf("01", "2"), entries[1].lines)
            // The no-coordinate row (4004) and the station (5005, location_type 1) are gone.
            assertTrue(entries.none { it.id == "4004" || it.id == "5005" })
            // Exactly the two requests: CKAN resource list + the feed zip.
            assertEquals(
                listOf(TEST_CKAN_URL, "https://example.com/feed/r-new.zip"),
                h.fetch.calls,
            )
            // Artifacts on disk, no tmp leftovers.
            assertTrue(h.dir.resolve("feed.zip").isFile)
            assertTrue(h.dir.resolve("index.json").isFile)
            assertTrue(h.dir.resolve("marker.json").isFile)
            assertTrue(
                h.dir
                    .listFiles()
                    .orEmpty()
                    .none { it.name.endsWith(".tmp") },
            )
        }

    @Test
    fun freshIndex_servesFromDisk_withZeroNetwork() =
        runBlocking {
            val h = harness().also { it.seedFirstBuild() }
            val catalog = h.catalog()
            val first = catalog.load()
            assertNotNull(first)
            h.fetch.calls.clear()

            // The marker is fresh, so a second caller (other language,
            // warm + search) serves from disk with no requests at all.
            val again = catalog.load()

            assertEquals(first, again)
            assertEquals(emptyList<String>(), h.fetch.calls)
        }

    @Test
    fun secondCatalogInstance_servesTheDiskIndex() =
        runBlocking {
            val h = harness().also { it.seedFirstBuild() }
            val first = h.catalog().load()
            assertNotNull(first)

            // Another process-equivalent instance over the same dir: fresh
            // marker -> index roundtrip, no network.
            h.fetch.calls.clear()
            assertEquals(first, h.catalog().load())
            assertEquals(emptyList<String>(), h.fetch.calls)
        }

    // -------------------------------------------------------------- freshness

    @Test
    fun staleIndex_checksCkan_sameFeedKeepsIndexAndStampsTheCheck() =
        runBlocking {
            var t = 0L
            val h = harness(now = { t }).also { it.seedFirstBuild() }
            val catalog = h.catalog()
            val first = catalog.load()
            assertNotNull(first)
            h.fetch.calls.clear()

            // 10 days later (past the 7-day freshness): CKAN answers with
            // the SAME latest feed -> index serves, zip not redownloaded.
            t = 10L * 24 * 60 * 60 * 1000
            assertEquals(first, catalog.load())
            assertEquals(listOf(TEST_CKAN_URL), h.fetch.calls)

            // 12 h later: checked today, no CKAN hammering (daily cap).
            h.fetch.calls.clear()
            t += 12L * 60 * 60 * 1000
            assertEquals(first, catalog.load())
            assertEquals(emptyList<String>(), h.fetch.calls)
        }

    @Test
    fun newerFeed_downloadsAndReparses() =
        runBlocking {
            var t = 0L
            val h = harness(now = { t }).also { it.seedFirstBuild() }
            val catalog = h.catalog()
            assertNotNull(catalog.load())

            // A newer feed is published. The stale index at 10 days must
            // pick it up: new stop 6006, English translation included.
            val newerZip =
                zipOf(
                    "stops.txt" to
                        csv(
                            "\uFEFFstop_id,stop_code,stop_name,stop_desc,stop_lat,stop_lon,zone_id," +
                                "stop_url,location_type,parent_station,stop_timezone,wheelchair_boarding,platform_code",
                            "6006,6006,ΚΑΙΝΟΥΡΓΙΟ,,40.60,22.90,,,0,,,,",
                        ) + "\n",
                    "routes.txt" to
                        csv(
                            "\uFEFFroute_id,agency_id,route_short_name,route_long_name,route_desc," +
                                "route_type,route_url,route_color,route_text_color",
                            "03_5_1_3,OSETH,03,ΝΕΑ,,,,,",
                        ) + "\n",
                    "trips.txt" to
                        csv(
                            "\uFEFFroute_id,service_id,trip_id,trip_headsign,trip_short_name," +
                                "direction_id,block_id,shape_id",
                            "03_5_1_3,1,t9,,,1,,,",
                        ) + "\n",
                    "stop_times.txt" to
                        csv(
                            "\uFEFFtrip_id,arrival_time,departure_time,stop_id,stop_sequence," +
                                "stop_headsign,pickup_type,drop_off_type,shape_dist_traveled,timepoint",
                            "t9,10:00:00,10:00:00,6006,1,,0,1,,1",
                        ) + "\n",
                    "translations.txt" to
                        csv(
                            "\uFEFFtable_name,field_name,language,translation,record_id,record_sub_id," +
                                "field_value",
                            "stops,stop_name,en-GB,KAINOURGIO,,,ΚΑΙΝΟΥΡΓΙΟ",
                        ) + "\n",
                )
            h.fetch.respond(
                TEST_CKAN_URL,
                ckanFixtureBody(
                    "r-old" to "GTFS 06/08/2026-17/08/2026",
                    "r-newer" to "GTFS 20/08/2026-01/09/2026",
                ),
            )
            h.fetch.respond("https://example.com/feed/r-newer.zip", newerZip)

            t = 10L * 24 * 60 * 60 * 1000
            val entries = catalog.load()

            assertNotNull(entries)
            assertEquals(listOf("6006"), entries!!.map { it.id })
            assertEquals("ΚΑΙΝΟΥΡΓΙΟ", entries[0].nameEl)
            assertEquals("KAINOURGIO", entries[0].nameEn)
            assertEquals(listOf("03"), entries[0].lines)

            // The rebuild reset the marker: the next load is network-free.
            h.fetch.calls.clear()
            assertEquals(entries, catalog.load())
            assertEquals(emptyList<String>(), h.fetch.calls)
        }

    // ------------------------------------------------------------- failures

    @Test
    fun firstBuild_downloadFailure_returnsNull() =
        runBlocking {
            val h = harness()
            h.fetch.respond(TEST_CKAN_URL, ckanFixtureBody("r-new" to "GTFS 06/08/2026-17/08/2026"))
            // no zip response -> download fails
            assertNull(h.catalog().load())
            // Nothing was committed: a retry must not serve an empty catalog.
            assertNull(h.catalog().load())
            assertTrue(
                h.dir
                    .listFiles()
                    .orEmpty()
                    .none { it.name == "index.json" },
            )
        }

    @Test
    fun firstBuild_ckanDead_returnsNull() =
        runBlocking {
            val h = harness()
            assertNull(h.catalog().load())
        }

    @Test
    fun staleIndex_downloadFailure_keepsServingTheLocalIndex() =
        runBlocking {
            var t = 0L
            val h = harness(now = { t }).also { it.seedFirstBuild() }
            val catalog = h.catalog()
            val first = catalog.load()
            assertNotNull(first)

            // 10 days later a newer feed is published, but the download
            // dies: the stale index still serves (local beats the
            // 11-17 s telematics walk).
            t = 10L * 24 * 60 * 60 * 1000
            h.fetch.respond(
                TEST_CKAN_URL,
                ckanFixtureBody("r-newer" to "GTFS 20/08/2026-01/09/2026"),
            )
            // no zip response
            assertEquals(first, catalog.load())
        }

    @Test
    fun brokenZip_returnsNull() =
        runBlocking {
            val h = harness()
            h.fetch.respond(TEST_CKAN_URL, ckanFixtureBody("r-new" to "GTFS 06/08/2026-17/08/2026"))
            h.fetch.respond("https://example.com/feed/r-new.zip", "this is not a zip".toByteArray())
            assertNull(h.catalog().load())
        }

    @Test
    fun sparseFeed_returnsNull_andWritesNothing() =
        runBlocking {
            // A "feed" with a handful of stops is a partial/garbage export,
            // not a catalog: below the floor it must fail (the repository
            // then falls back to the telematics walk).
            val h = harness(minStops = 10)
            h.fetch.respond(TEST_CKAN_URL, ckanFixtureBody("r-new" to "GTFS 06/08/2026-17/08/2026"))
            h.fetch.respond("https://example.com/feed/r-new.zip", gtfsFixtureZip()) // 3 usable stops
            assertNull(h.catalog().load())
            assertTrue(
                h.dir
                    .listFiles()
                    .orEmpty()
                    .none { it.name == "index.json" },
            )
            assertTrue(
                h.dir
                    .listFiles()
                    .orEmpty()
                    .none { it.name == "marker.json" },
            )
        }

    @Test
    fun missingTranslations_stillBuilds_greekOnly() =
        runBlocking {
            val noTranslations =
                zipOf(
                    "stops.txt" to
                        csv(
                            "\uFEFFstop_id,stop_code,stop_name,stop_desc,stop_lat,stop_lon,zone_id," +
                                "stop_url,location_type,parent_station,stop_timezone,wheelchair_boarding,platform_code",
                            "10002,10002,ΔΙΑΝΑ,,40.64041,22.94085,,,0,,,,",
                        ) + "\n",
                    "routes.txt" to
                        csv(
                            "\uFEFFroute_id,agency_id,route_short_name,route_long_name,route_desc," +
                                "route_type,route_url,route_color,route_text_color",
                            "01_5351_1_3,OSETH,01,Τ.Σ. ΕΥΚΑΡΠΙΑΣ,,,,,",
                        ) + "\n",
                    "trips.txt" to
                        csv(
                            "\uFEFFroute_id,service_id,trip_id,trip_headsign,trip_short_name," +
                                "direction_id,block_id,shape_id",
                            "01_5351_1_3,1,t1,,,1,,,",
                        ) + "\n",
                    "stop_times.txt" to
                        csv(
                            "\uFEFFtrip_id,arrival_time,departure_time,stop_id,stop_sequence," +
                                "stop_headsign,pickup_type,drop_off_type,shape_dist_traveled,timepoint",
                            "t1,10:00:00,10:00:00,10002,1,,0,1,,1",
                        ) + "\n",
                )
            val h = harness()
            h.fetch.respond(TEST_CKAN_URL, ckanFixtureBody("r-new" to "GTFS 06/08/2026-17/08/2026"))
            h.fetch.respond("https://example.com/feed/r-new.zip", noTranslations)

            val entries = h.catalog().load()

            assertNotNull(entries)
            assertEquals("ΔΙΑΝΑ", entries!![0].nameEl)
            assertNull(entries[0].nameEn) // no translations.txt -> Greek-only
            assertEquals(listOf("01"), entries[0].lines)
        }

    @Test
    fun indexLost_zipAndMarkerSurvive_reparsesLocally_noNetwork() =
        runBlocking {
            val h = harness().also { it.seedFirstBuild() }
            val catalog = h.catalog()
            val first = catalog.load()
            assertNotNull(first)

            // The index file vanishes (manual delete, crash between the
            // artifact writes). The zip and marker survive: the next load
            // re-parses the local zip, zero network.
            assertTrue(h.dir.resolve("index.json").delete())
            h.fetch.calls.clear()
            val rebuilt = catalog.load()

            assertEquals(first, rebuilt)
            assertEquals(emptyList<String>(), h.fetch.calls)
            assertTrue(h.dir.resolve("index.json").isFile) // healed
        }

    // ------------------------------------------------------------- splitter

    @Test
    fun splitCsv_quotesCommasEscapesAndBom() {
        assertEquals(listOf("a", "b", "c"), splitCsvLine("a,b,c"))
        assertEquals(listOf("a,b", "c"), splitCsvLine("\"a,b\",c"))
        assertEquals(listOf("he said \"hi\"", "x"), splitCsvLine("\"he said \"\"hi\"\"\",x"))
        assertEquals(listOf("", "x", ""), splitCsvLine(",x,"))
        assertEquals(listOf("stop_id", "stop_name"), splitCsvLine("\uFEFFstop_id,stop_name"))
    }

    @Test
    fun splitCsv_quoteMidFieldIsLiteral() {
        // RFC-ish leniency: a quote mid-field is literal (today's stop
        // names contain none. This only proves we never throw).
        assertEquals(listOf("a\"b", "c"), splitCsvLine("a\"b,c"))
    }
}
