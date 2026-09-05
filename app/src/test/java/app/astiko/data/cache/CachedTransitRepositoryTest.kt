package app.astiko.data.cache

import app.astiko.data.TransitRepository
import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.model.VehiclePosition
import app.astiko.data.testJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.DayOfWeek

/**
 * CachedTransitRepository tests for the read-through/fallback contract:
 * fresh serves without network, stale refreshes on access, offline never
 * touches the delegate, failures serve stale, and live payloads degrade
 * to the cached schedule.
 */
class CachedTransitRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val line =
        Line(provider = Provider.OSETh, id = "01_7429_1_3", shortName = "01", longName = "TEST")
    private val lines = listOf(line)
    private val variant =
        LineVariant(
            provider = Provider.OSETh,
            lineId = line.id,
            id = "01_7429_1_3",
            shapeId = "5304",
            label = "ΚΑΤΕΥΘΥΝΣΗ",
            lineShortName = "01",
        )
    private val stop =
        Stop(provider = Provider.OSETh, id = "5271", name = "ΣΤΑΣΗ", lat = 40.0, lon = 22.0)

    private val online = MutableStateFlow(true)

    private fun repo(
        delegate: TransitRepository,
        cache: OfflineCache = OfflineCache(tmp.newFolder()),
        now: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() },
        onlineRetryDelays: List<Long> = listOf(2_000L, 4_000L, 8_000L),
    ): CachedTransitRepository =
        CachedTransitRepository(
            delegate = delegate,
            cache = cache,
            online = online,
            langProvider = { "el" },
            now = now,
            onlineRetryDelays = onlineRetryDelays,
        )

    /** Manually plant a cache entry with a chosen savedAt (the store always
     *  stamps "now"), so tests can age entries past their TTL. */
    private fun plant(
        cacheDir: java.io.File,
        key: String,
        savedAtMs: Long,
        dataJson: String,
    ) {
        java.io
            .File(
                cacheDir,
                "$key.json",
            ).writeText("""{"v":1,"savedAt":$savedAtMs,"data":$dataJson}""")
    }

    private val linesJson: String
        get() = testJson.encodeToString(ListSerializer(Line.serializer()), lines)

    private val dayMs = 24L * 60 * 60 * 1000
    private val hourMs = 60L * 60 * 1000

    // ---------------------------------------------------------------- read-through

    @Test
    fun `fresh entry serves without calling the delegate`() =
        runTest {
            val delegate = FakeRepo()
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            // Planted well inside the 24 h lines/variants TTL. An age of
            // exactly dayMs sat on the TTL boundary and flipped to
            // stale when the lines/variants TTL changed 7d -> 24h.
            plant(cacheDir, "oseth-el-lines", System.currentTimeMillis() - hourMs, linesJson)
            val cached = repo(delegate, cache)

            assertEquals(lines, cached.getLines())
            assertEquals(0, delegate.linesCalls)
        }

    @Test
    fun `stale entry refetches and rewrites`() =
        runTest {
            val delegate = FakeRepo().also { it.lines = lines }
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            plant(
                cacheDir,
                "oseth-el-lines",
                System.currentTimeMillis() - 8 * dayMs,
                linesJson,
            )
            val cached = repo(delegate, cache)

            assertEquals(lines, cached.getLines())
            assertEquals(1, delegate.linesCalls)
            // Rewritten with a fresh timestamp.
            val rewritten = cache.read("oseth-el-lines", ListSerializer(Line.serializer()))!!
            assertTrue(rewritten.savedAt > System.currentTimeMillis() - dayMs)
        }

    @Test
    fun `offline serves cache and never calls the delegate`() =
        runTest {
            val delegate = FakeRepo()
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            plant(cacheDir, "oseth-el-lines", System.currentTimeMillis() - dayMs, linesJson)
            online.value = false
            val cached = repo(delegate, cache)

            assertEquals(lines, cached.getLines())
            assertEquals(0, delegate.linesCalls)
        }

    @Test
    fun `offline miss throws instead of hanging on the network`() =
        runTest {
            val delegate = FakeRepo()
            online.value = false
            val cached = repo(delegate)

            try {
                cached.getLines()
                assertTrue("expected an IOException", false)
            } catch (e: IOException) {
                // the real "nothing cached" failure
            }
            assertEquals(0, delegate.linesCalls)
        }

    // ------------------------------------------------------- stop catalog

    private val catalogStops =
        listOf(
            Stop(provider = Provider.OSETh, id = "S1", name = "ΑΓΟΡΑ", lat = 40.0, lon = 22.0),
            Stop(provider = Provider.OSETh, id = "S2", name = "ΠΛ. ΑΓΟΡΑΣ", lat = 40.1, lon = 22.1),
        )
    private val stopsJson: String
        get() = testJson.encodeToString(ListSerializer(Stop.serializer()), catalogStops)

    @Test
    fun `stop catalog miss fetches once and is one disk entry`() =
        runTest {
            val delegate = FakeRepo().also { it.stopCatalog = catalogStops }
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache)

            // First search fetches the catalog and ranks locally.
            val first = cached.searchStops("αγορα")
            assertEquals(listOf("S1", "S2"), first.map { it.id })
            assertEquals(1, delegate.stopCatalogCalls)

            // The whole catalog sits under one disk entry, and the next
            // search (within the TTL) serves from it: no more fetches.
            val entry = cache.read("oseth-el-stopCatalog", ListSerializer(Stop.serializer()))!!
            assertEquals(catalogStops, entry.value)
            assertEquals(catalogStops, cached.searchStops("αγορα"))
            assertEquals(1, delegate.stopCatalogCalls)
        }

    @Test
    fun `stop catalog fresh entry serves without the delegate`() =
        runTest {
            val delegate = FakeRepo()
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            plant(cacheDir, "oseth-el-stopCatalog", System.currentTimeMillis() - hourMs, stopsJson)
            val cached = repo(delegate, cache)

            assertEquals(listOf("S1", "S2"), cached.searchStops("αγορα").map { it.id })
            assertEquals(0, delegate.stopCatalogCalls)
        }

    @Test
    fun `offline stop search serves the catalog entry`() =
        runTest {
            // Offline full-coverage search: the one disk entry answers,
            // no network, no per-stop files required.
            val delegate = FakeRepo()
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            plant(cacheDir, "oseth-el-stopCatalog", System.currentTimeMillis() - 8 * dayMs, stopsJson)
            online.value = false
            val cached = repo(delegate, cache)

            assertEquals(listOf("S1", "S2"), cached.searchStops("αγορα").map { it.id })
            assertEquals(0, delegate.stopCatalogCalls)
        }

    @Test
    fun `online fetch failure serves the stale entry`() =
        runTest {
            val delegate = FakeRepo()
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            plant(
                cacheDir,
                "oseth-el-lines",
                System.currentTimeMillis() - 8 * dayMs,
                linesJson,
            )
            delegate.linesError = IOException("server down")
            val cached = repo(delegate, cache)

            assertEquals(lines, cached.getLines())
        }

    @Test
    fun `online fetch failure with no cache propagates`() =
        runTest {
            val delegate = FakeRepo()
            delegate.linesError = IOException("server down")
            val cached = repo(delegate)

            try {
                cached.getLines()
                assertTrue("expected an IOException", false)
            } catch (e: IOException) {
                assertEquals("server down", e.message)
            }
        }

    @Test
    fun `empty catalog result is served but never written to disk`() =
        runTest {
            // An outage that surfaces as [] (adapter swallows, cancellation
            // mishandled, provider half-up) must not pin the empty catalog to
            // disk for the TTL. The next open refetches.
            val delegate = FakeRepo() // getLines returns emptyList
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache)

            assertEquals(emptyList<Line>(), cached.getLines())
            assertEquals(1, delegate.linesCalls)
            assertNull(cache.read<List<Line>>("oseth-el-lines", ListSerializer(Line.serializer())))

            // A later fetch with real data caches normally.
            delegate.lines = lines
            assertEquals(lines, cached.getLines())
            assertEquals(
                lines,
                cache.read<List<Line>>("oseth-el-lines", ListSerializer(Line.serializer()))!!.value,
            )
        }

    @Test
    fun `empty variant result is never written to disk`() =
        runTest {
            val delegate = FakeRepo() // getLineVariants returns emptyList
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache)

            assertTrue(cached.getLineVariants(line).isEmpty())
            assertNull(
                cache.read<List<LineVariant>>(
                    "oseth-el-lineVariants-01_7429_1_3-01",
                    ListSerializer(LineVariant.serializer()),
                ),
            )
        }

    @Test
    fun `lineVariants key includes the shortName - shared line ids never collide`() =
        runTest {
            // OASA's internal line_code is not unique (938 covers 040/550/Α2)
            // and getLineVariants resolves by the PUBLIC shortName. Two
            // public lines sharing an id must not share a cache slot, or a
            // fresh-cache lookup for 550 would serve 040's directions for
            // the TTL.
            val delegate = FakeRepo()
            val cache = OfflineCache(tmp.newFolder())
            val cached = repo(delegate, cache)
            val line040 =
                Line(provider = Provider.OASA, id = "938", shortName = "040", longName = "")
            val line550 =
                Line(provider = Provider.OASA, id = "938", shortName = "550", longName = "")
            val v040 =
                variant.copy(
                    provider = Provider.OASA,
                    lineId = "938",
                    id = "R040",
                    lineShortName = "040",
                )
            val v550 =
                variant.copy(
                    provider = Provider.OASA,
                    lineId = "938",
                    id = "R550",
                    lineShortName = "550",
                )
            delegate.variantsByShortName =
                mapOf("040" to listOf(v040), "550" to listOf(v550))

            assertEquals(listOf(v040), cached.getLineVariants(line040))
            // 040's slot exists, 550's does not. They are distinct keys.
            assertNotNull(
                cache.read<List<LineVariant>>(
                    "oseth-el-lineVariants-938-040",
                    ListSerializer(LineVariant.serializer()),
                ),
            )
            assertNull(
                cache.read<List<LineVariant>>(
                    "oseth-el-lineVariants-938-550",
                    ListSerializer(LineVariant.serializer()),
                ),
            )

            // The fresh-cache lookup for 550 fetches 550's variants. It must
            // not serve 040's cached directions. Keying by line.id alone
            // would collide.
            assertEquals(listOf(v550), cached.getLineVariants(line550))
            assertEquals(2, delegate.variantsCalls)
            assertNotNull(
                cache.read<List<LineVariant>>(
                    "oseth-el-lineVariants-938-550",
                    ListSerializer(LineVariant.serializer()),
                ),
            )
        }

    @Test
    fun `empty timetable result IS cached (no-service day is legitimate)`() =
        runTest {
            // Timetables keep caching empties: a day without service is a
            // stable result, so refetching it on every open would be wasteful.
            val delegate = FakeRepo()
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache)

            assertEquals(
                emptyList<TimetableEntry>(),
                cached.getStopTimetable("5271", DayOfWeek.MONDAY),
            )
            val read =
                cache.read<List<TimetableEntry>>(
                    "oseth-el-stopTimetable-5271-MONDAY",
                    ListSerializer(TimetableEntry.serializer()),
                )
            assertNotNull(read)
            assertTrue(read!!.value.isEmpty())
        }

    // ---------------------------------------------------------------- stops catalog

    @Test
    fun `online getStopsNear delegates and upserts the catalog`() =
        runTest {
            val delegate = FakeRepo()
            delegate.stopsNear = listOf(stop.copy(distanceKm = 0.3))
            val cached = repo(delegate)

            assertEquals(listOf(stop.copy(distanceKm = 0.3)), cached.getStopsNear(40.0, 22.0))
            assertEquals(1, delegate.stopsNearCalls)

            // Offline nearby now serves from the catalog, client-side haversine.
            online.value = false
            val offline = cached.getStopsNear(40.0, 22.0)
            assertEquals(1, offline.size)
            assertEquals("ΣΤΑΣΗ", offline[0].name)
            assertNotNull(offline[0].distanceKm)
            assertEquals(1, delegate.stopsNearCalls) // no NEW calls while offline
        }

    @Test
    fun `offline getStopsNear with empty catalog throws`() =
        runTest {
            val delegate = FakeRepo()
            online.value = false
            val cached = repo(delegate)

            try {
                cached.getStopsNear(40.0, 22.0)
                assertTrue("expected an IOException", false)
            } catch (e: IOException) {
                // real "nothing cached"
            }
        }

    @Test
    fun `getVariantStops upserts route stops into the catalog`() =
        runTest {
            val delegate = FakeRepo()
            delegate.routeStops = listOf(stop)
            val cached = repo(delegate)

            cached.getVariantStops(variant)
            assertEquals(1, delegate.routeStopsCalls)

            // The stop is now in the catalog, so offline nearby finds it.
            online.value = false
            assertEquals(1, cached.getStopsNear(40.0, 22.0).size)
        }

    @Test
    fun `repeated nearby fetches skip rewriting identical catalog entries`() =
        runTest {
            val delegate = FakeRepo()
            delegate.stopsNear =
                listOf(
                    stop.copy(distanceKm = 0.3),
                    stop.copy(id = "5272", distanceKm = 0.5),
                )
            val cache = OfflineCache(tmp.newFolder())
            val cached = repo(delegate, cache)

            cached.getStopsNear(40.0, 22.0)
            val writes = cache.generation.value // one per NEW stop
            assertEquals(2L, writes)

            // Same stops again (a GPS move): the dirty-check skips both, no
            // rewrite, no generation bump. A force-rewrite path would rewrite
            // every stop on every GPS fix, real disk churn through the cache
            // mutex.
            cached.getStopsNear(40.0, 22.0)
            assertEquals(writes, cache.generation.value)

            // A renamed stop still refreshes in place.
            delegate.stopsNear =
                listOf(
                    stop.copy(name = "ΝΕΟ ΟΝΟΜΑ", distanceKm = 0.3),
                    stop.copy(id = "5272", distanceKm = 0.5),
                )
            cached.getStopsNear(40.0, 22.0)
            assertEquals(writes + 1, cache.generation.value)
        }

    @Test
    fun `route stop upsert preserves an enriched catalog entry`() =
        runTest {
            val delegate = FakeRepo()
            val cache = OfflineCache(tmp.newFolder())
            val cached = repo(delegate, cache)
            // The catalog already holds a RICHER version of the stop (serving
            // lines from a previous nearby/badge fetch).
            cache.write(
                "oseth-el-stops-${stop.id}",
                stop.copy(servingLines = listOf("01", "12")),
                Stop.serializer(),
            )
            delegate.routeStops = listOf(stop) // bare route-stop copy, no lines

            cached.getVariantStops(variant)

            // The bare copy must not overwrite the enriched entry. A
            // force-rewrite path would wipe the badges from the offline
            // catalog.
            val stored = cache.read("oseth-el-stops-${stop.id}", Stop.serializer())!!.value
            assertEquals(listOf("01", "12"), stored.servingLines)
        }

    @Test
    fun `single-flight fetch - a cancelled leader does not kill the joiner`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            var calls = 0
            val delegate = GatedLinesRepo(release, started, { calls++ })
            val cached = repo(delegate)

            val leader = launch { cached.getLines() }
            started.await() // the leader is inside the fetch
            val joiner = async { cached.getLines() }
            yield() // let the joiner attach to the leader's in-flight gate
            leader.cancelAndJoin()
            release.complete(Unit)

            // The joiner's own call is still alive: it retries as the new
            // leader and gets the value. Propagating the leader's cancellation
            // to the joiner would fail a live caller with a spurious
            // cancellation.
            assertEquals(lines, joiner.await())
            assertEquals(2, calls) // leader's attempt + the joiner's fresh fetch
        }

    // ---------------------------------------------------------------- live flows

    @Test
    fun `observeArrivals failure degrades to the cached schedule while offline`() =
        runTest {
            // The fallback keeps trips within [now, now+2h] of the current
            // time. A pinned clock makes the horizon deterministic.
            val fixedNow = java.time.LocalDateTime.of(2026, 8, 15, 10, 0)
            val delegate = FakeRepo()
            delegate.arrivalsError = IOException("no network")
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache, now = { fixedNow })
            // The degrade is gated on actual offline state: the connectivity
            // monitor is the signal, a fetch failure alone is not.
            online.value = false

            // Today's (pinned) weekday timetable: two trips inside the 2 h
            // horizon (10:30 -> eta 30, 11:30 -> eta 90), one already passed,
            // one beyond the horizon.
            val entries =
                listOf(
                    TimetableEntry(
                        lineShortName = "01",
                        lineName = "TEST",
                        destination = "ΠΡΟΟΡΙΣΜΟΣ",
                        departureTime = "09:00",
                    ),
                    TimetableEntry(
                        lineShortName = "02",
                        lineName = "TEST",
                        destination = "ΠΡΟΟΡΙΣΜΟΣ",
                        departureTime = "10:30",
                    ),
                    TimetableEntry(
                        lineShortName = "03",
                        lineName = "TEST",
                        destination = "ΠΡΟΟΡΙΣΜΟΣ",
                        departureTime = "11:30",
                    ),
                    TimetableEntry(
                        lineShortName = "04",
                        lineName = "TEST",
                        destination = "ΠΡΟΟΡΙΣΜΟΣ",
                        departureTime = "12:30",
                    ),
                )
            plant(
                cacheDir,
                "oseth-el-stopTimetable-5271-${fixedNow.dayOfWeek.name}",
                System.currentTimeMillis() - dayMs,
                testJson.encodeToString(ListSerializer(TimetableEntry.serializer()), entries),
            )

            val arrivals = cached.observeArrivals("5271", lines).first()
            assertEquals(2, arrivals.size)
            assertTrue(arrivals.all { it.isScheduled })
            assertEquals(listOf(30, 90), arrivals.map { it.etaMinutes })
            assertEquals(listOf("10:30", "11:30"), arrivals.map { it.scheduledTime })
            assertEquals("02", arrivals[0].lineShortName)
            assertNull(arrivals[0].vehicle)
        }

    @Test
    fun `observeArrivals success emits live rows`() =
        runTest {
            val delegate = FakeRepo()
            val cached = repo(delegate)

            val arrivals = cached.observeArrivals("5271", lines).first()
            assertEquals(1, arrivals.size)
            assertFalse(arrivals[0].isScheduled)
        }

    @Test
    fun `observeArrivals failure without cached schedule propagates`() =
        runTest {
            val delegate = FakeRepo()
            delegate.arrivalsError = IOException("no network")
            val cached = repo(delegate)

            try {
                cached.observeArrivals("5271", lines).first()
                assertTrue("expected an IOException", false)
            } catch (e: IOException) {
                // real error, no schedule source for this stop
            }
        }

    @Test
    fun `observeArrivals failure while online propagates despite a cached schedule`() =
        runTest {
            // A poll failure while the network is still validated must not
            // degrade to the schedule. A backgrounded app (network still
            // VALIDATED) would otherwise come back showing "Schedule · offline"
            // rows with no offline banner. The failure must propagate. The
            // ViewModel keeps the last live list frozen and the next
            // successful poll restores it.
            val fixedNow = java.time.LocalDateTime.of(2026, 8, 15, 10, 0)
            val delegate = FakeRepo()
            delegate.arrivalsError = IOException("transient background failure")
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache, now = { fixedNow })
            // online.value stays true (the default). The network never dropped.
            plant(
                cacheDir,
                "oseth-el-stopTimetable-5271-${fixedNow.dayOfWeek.name}",
                System.currentTimeMillis() - dayMs,
                testJson.encodeToString(
                    ListSerializer(TimetableEntry.serializer()),
                    listOf(
                        TimetableEntry(
                            lineShortName = "01",
                            lineName = "TEST",
                            destination = "ΠΡΟΟΡΙΣΜΟΣ",
                            departureTime = "10:30",
                        ),
                    ),
                ),
            )

            try {
                cached.observeArrivals("5271", lines).first()
                assertTrue("expected the failure to propagate while online", false)
            } catch (e: IOException) {
                // A validated network does not mean the API is down.
                // The fallback is reserved for real offline use
            }
        }

    @Test
    fun `observeArrivals retries quickly while online and recovers`() =
        runTest {
            // The wake case: the first fetch after resume dies on a stale
            // socket. A short retry lands the data in seconds instead of
            // the next 15 s tick.
            val delegate = FakeRepo()
            delegate.arrivalsErrorOnce = IOException("stale socket")
            val cached = repo(delegate, onlineRetryDelays = listOf(100L))

            val arrivals = cached.observeArrivals("5271", lines).first()

            assertEquals(2, delegate.arrivalsSubscriptions)
            assertEquals(listOf(5), arrivals.map { it.etaMinutes })
        }

    @Test
    fun `observeArrivals online failure exhausts retries then propagates`() =
        runTest {
            // A genuine outage must not retry forever inside the flow. The
            // retries exhaust. The ViewModel's 15 s cadence takes over.
            val delegate = FakeRepo()
            delegate.arrivalsError = IOException("provider down")
            val cached = repo(delegate, onlineRetryDelays = listOf(100L, 200L))

            try {
                cached.observeArrivals("5271", lines).first()
                assertTrue("expected the failure to propagate", false)
            } catch (e: IOException) {
                // the provider is down. The ViewModel shows the honest error
            }
            assertEquals(3, delegate.arrivalsSubscriptions)
        }

    @Test
    fun `observeVehicles failure emits empty list`() =
        runTest {
            val delegate = FakeRepo()
            delegate.vehiclesError = IOException("no network")
            val cached = repo(delegate)

            assertEquals(emptyList<VehiclePosition>(), cached.observeVehicles(variant).first())
        }

    // ---------------------------------------------------------------- misc

    @Test
    fun `warmStop seeds the catalog for offline nearby`() =
        runTest {
            val delegate = FakeRepo()
            val cached = repo(delegate)
            cached.warmStop(stop)

            online.value = false
            assertEquals(1, cached.getStopsNear(40.0, 22.0).size)
        }

    @Test
    fun `refreshExpired refetches only stale entries`() =
        runTest {
            // Non-empty results (empties are never cached. See the empty-
            // catalog tests above, so they would be refetched every time).
            val delegate =
                FakeRepo().also {
                    it.lines = lines
                    it.variants = listOf(variant)
                }
            val cacheDir = tmp.newFolder()
            val cache = OfflineCache(cacheDir)
            val cached = repo(delegate, cache)
            // Warm both entries through the decorator (registers the tasks).
            cached.getLines()
            cached.getLineVariants(line)
            assertEquals(1, delegate.linesCalls)
            assertEquals(1, delegate.variantsCalls)

            // Age the lines entry. The variants entry stays fresh.
            plant(
                cacheDir,
                "oseth-el-lines",
                System.currentTimeMillis() - 8 * dayMs,
                linesJson,
            )

            cached.refreshExpired()

            assertEquals(2, delegate.linesCalls) // stale, refetched
            assertEquals(1, delegate.variantsCalls) // fresh, served from cache
        }

    @Test
    fun `stopLinesForDisplay passes through`() {
        val cached = repo(FakeRepo())
        assertEquals(lines, cached.stopLinesForDisplay(lines))
    }

    /** Minimal delegate with call counters and injectable results/errors. */
    class FakeRepo : TransitRepository {
        override val provider: Provider = Provider.OSETh

        var lines: List<Line> = emptyList()
        var linesError: Throwable? = null
        var linesCalls = 0

        var stopsNear: List<Stop> = emptyList()
        var stopsNearCalls = 0

        var variants: List<LineVariant> = emptyList()

        /** Shared-id collision coverage. OASA lines that share a line_code
         *  resolve different variants per public shortName, so the fake must
         *  answer per shortName. The single [variants] list would mask the
         *  cache-key bug. */
        var variantsByShortName: Map<String, List<LineVariant>> = emptyMap()
        var variantsCalls = 0

        var routeStops: List<Stop> = emptyList()
        var routeStopsCalls = 0

        var geometry: List<GeoPoint> = emptyList()
        var geometryCalls = 0

        var arrivals: List<Arrival> =
            listOf(
                Arrival(
                    routeCode = "01",
                    lineShortName = "01",
                    lineName = "TEST",
                    destination = "ΠΡΟΟΡΙΣΜΟΣ",
                    etaMinutes = 5,
                    scheduledTime = "12:00",
                ),
            )
        var arrivalsError: Throwable? = null

        /** Thrown on the first subscription only; the wake glitch, a
         *  transient failure that a quick retry succeeds past. */
        var arrivalsErrorOnce: Throwable? = null
        var arrivalsSubscriptions = 0

        var vehiclesError: Throwable? = null

        override suspend fun getStopsNear(
            lat: Double,
            lon: Double,
            limit: Int,
        ): List<Stop> {
            stopsNearCalls++
            return stopsNear
        }

        override suspend fun getStopRoutes(stopId: String): List<Line> = lines

        override fun stopLinesForDisplay(lines: List<Line>): List<Line> = lines

        override suspend fun getLines(): List<Line> {
            linesCalls++
            linesError?.let { throw it }
            return lines
        }

        override suspend fun getLineVariants(line: Line): List<LineVariant> {
            variantsCalls++
            return variantsByShortName[line.shortName] ?: variants
        }

        override suspend fun getVariantStops(variant: LineVariant): List<Stop> {
            routeStopsCalls++
            return routeStops
        }

        var stopCatalog: List<Stop> = emptyList()
        var stopCatalogError: Throwable? = null
        var stopCatalogCalls = 0

        override suspend fun getStopCatalog(): List<Stop> {
            stopCatalogCalls++
            stopCatalogError?.let { throw it }
            return stopCatalog
        }

        override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> {
            geometryCalls++
            return geometry
        }

        override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
            flow {
                vehiclesError?.let { throw it }
                emit(emptyList())
            }

        override fun observeArrivals(
            stopId: String,
            lines: List<Line>,
        ): Flow<List<Arrival>> =
            flow {
                arrivalsSubscriptions++
                arrivalsErrorOnce?.let {
                    arrivalsErrorOnce = null
                    throw it
                }
                arrivalsError?.let { throw it }
                emit(arrivals)
            }

        override suspend fun getStopTimetable(
            stopId: String,
            day: DayOfWeek,
        ): List<TimetableEntry> = emptyList()

        override suspend fun getLineTimetable(
            variant: LineVariant,
            day: DayOfWeek,
        ): List<TimetableEntry> = emptyList()
    }

    /** A repository whose getLines blocks until [release]. Lets tests
     *  cancel a fetch in progress and observe the shared-fetch behavior. */
    private class GatedLinesRepo(
        private val release: CompletableDeferred<Unit>,
        private val started: CompletableDeferred<Unit>,
        private val onCall: () -> Unit,
    ) : TransitRepository {
        override val provider: Provider = Provider.OSETh

        override suspend fun getLines(): List<Line> {
            onCall()
            if (!started.isCompleted) started.complete(Unit)
            release.await()
            return listOf(
                Line(
                    provider = Provider.OSETh,
                    id = "01_7429_1_3",
                    shortName = "01",
                    longName = "TEST",
                ),
            )
        }

        override suspend fun getStopsNear(
            lat: Double,
            lon: Double,
            limit: Int,
        ): List<Stop> = emptyList()

        override suspend fun getStopRoutes(stopId: String): List<Line> = emptyList()

        override fun stopLinesForDisplay(lines: List<Line>): List<Line> = lines

        override suspend fun getLineVariants(line: Line): List<LineVariant> = emptyList()

        override suspend fun getVariantStops(variant: LineVariant): List<Stop> = emptyList()

        override suspend fun getStopCatalog(): List<Stop> = emptyList()

        override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> = emptyList()

        override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
            flow {
                emit(emptyList())
            }

        override fun observeArrivals(
            stopId: String,
            lines: List<Line>,
        ): Flow<List<Arrival>> =
            flow {
                emit(emptyList())
            }

        override suspend fun getStopTimetable(
            stopId: String,
            day: DayOfWeek,
        ): List<TimetableEntry> = emptyList()

        override suspend fun getLineTimetable(
            variant: LineVariant,
            day: DayOfWeek,
        ): List<TimetableEntry> = emptyList()
    }
}
