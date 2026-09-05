package app.astiko.data.cache

import android.util.Log
import app.astiko.data.POLL_INTERVAL_MS
import app.astiko.data.TransitRepository
import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.model.VehiclePosition
import app.astiko.util.haversineKm
import app.astiko.util.rankStopSearch
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.time.DayOfWeek
import kotlin.coroutines.coroutineContext

/**
 * Offline cache decorator: wraps a network adapter with a disk-backed
 * read-through cache. The UI keeps talking to [TransitRepository] and
 * never knows a cache exists.
 *
 * Rules:
 * - Non-polling calls (catalogs, timetables, route data): a fresh cache
 *   serves without a network call. A stale cache refreshes on access, and
 *   a failed fetch serves the stale entry. A miss with no cache entry
 *   propagates, so the screens' existing error states keep working.
 * - No validated network: the fetch is never attempted. No 15 s
 *   timeouts, instant offline screens. The cache serves or the call
 *   fails fast.
 * - Live payloads (arrivals, vehicles) are never written to disk.
 * - `getStopsNear` is query-relative, so it is not cached as-is. Every
 *   stop the app ever sees (nearby responses, route stop lists,
 *   favorites) is upserted into a per-provider+lang stop catalog, and
 *   offline nearby is a client-side haversine over that catalog.
 *   Coverage grows with usage.
 * - Cache keys carry the language. OASA `*Eng` transliterations,
 *   OSETh's and CityBus's `{lang}` paths all produce language-bound
 *   data, so both languages coexist under their own keys.
 */
class CachedTransitRepository(
    private val delegate: TransitRepository,
    private val cache: OfflineCache,
    /** Validated-internet state (StateFlow so tests can flip it without Android). */
    private val online: StateFlow<Boolean>,
    private val langProvider: () -> String,
    /** Injectable clock for the offline fallback (tests pin the time). */
    private val now: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() },
    /**
     * Retry delays for arrivals fetches that fail while the network is
     * still validated. Exhausting the list propagates (see
     * observeArrivals), and the ViewModel's own 15 s cadence retries from
     * there without hammering the provider.
     */
    private val onlineRetryDelays: List<Long> = listOf(2_000L, 4_000L, 8_000L),
) : TransitRepository {
    override val provider: Provider get() = delegate.provider

    private val lang: String get() = langProvider()

    override suspend fun getStopsNear(
        lat: Double,
        lon: Double,
        limit: Int,
    ): List<Stop> {
        if (online.value) {
            try {
                val stops = delegate.getStopsNear(lat, lon, limit)
                // Grow the offline catalog with every stop the API reports.
                upsertStops(stops)
                return stops
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Provider down while the network looks fine (captive
                // portal, rate limit). Same catalog fallback as offline.
                return catalogStopsNear(lat, lon, limit, originalError = e)
            }
        }
        return catalogStopsNear(lat, lon, limit)
    }

    override suspend fun getStopRoutes(stopId: String): List<Line> =
        readThrough(
            type = "stopRoutes",
            id = stopId,
            ttlMs = TTL_STOP_ROUTES,
            serializer = ListSerializer(Line.serializer()),
            fetch = { delegate.getStopRoutes(stopId) },
            // A stop with no routes is legitimate but transient. An outage
            // surfacing as [] must not pin the empty state.
            cacheEmpty = false,
        )

    override fun stopLinesForDisplay(lines: List<Line>): List<Line> = delegate.stopLinesForDisplay(lines)

    override suspend fun getLines(): List<Line> =
        readThrough(
            type = "lines",
            id = "",
            ttlMs = TTL_LINES,
            serializer = ListSerializer(Line.serializer()),
            fetch = { delegate.getLines() },
            cacheEmpty = false,
        )

    override suspend fun getLineVariants(line: Line): List<LineVariant> =
        readThrough(
            type = "lineVariants",
            // Identity = both adapter-resolution inputs. OASA's line_code
            // is not unique (938 covers 040/550/Α2) and variants resolve by
            // public shortName, so line.id alone would serve 040's
            // directions for 550's slot.
            id = "${line.id}-${line.shortName}",
            ttlMs = TTL_LINE_VARIANTS,
            serializer = ListSerializer(LineVariant.serializer()),
            fetch = { delegate.getLineVariants(line) },
            cacheEmpty = false,
        )

    override suspend fun getVariantStops(variant: LineVariant): List<Stop> =
        readThrough(
            type = "routeStops",
            id = variantKey(variant),
            ttlMs = TTL_ROUTE_STOPS,
            serializer = ListSerializer(Stop.serializer()),
            fetch = { delegate.getVariantStops(variant) },
            cacheEmpty = false,
            // Route stop lists feed the catalog too (whole routes, not just
            // the 20 nearest stops).
            onFresh = { upsertStops(it) },
        )

    override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> =
        readThrough(
            type = "routeGeometry",
            id = variantKey(variant),
            ttlMs = TTL_ROUTE_GEOMETRY,
            serializer = ListSerializer(GeoPoint.serializer()),
            fetch = { delegate.getRouteGeometry(variant) },
            cacheEmpty = false,
        )

    /**
     * Live vehicles are never cached. A failed poll emits an empty list
     * so the map stays alive with no buses (CityBus has no per-route
     * vehicle endpoint either).
     */
    override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
        flow {
            while (true) {
                try {
                    delegate.observeVehicles(variant).collect { emit(it) }
                    // The real adapters poll forever. A flow ending without
                    // an error has nothing more to emit.
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(emptyList())
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

    /**
     * Live arrivals are never cached. Offline polls degrade to the cached
     * weekday timetable (next 2 h, `isScheduled` rows). Online failures
     * propagate and the ViewModel keeps the last live list. The gate is
     * validated connectivity, never a fetch failure. A backgrounded
     * process fails polls while still online, and swapping live telemetry
     * for a schedule labeled "offline" would lie. Providers without a
     * schedule source (OASA, Agrinio, Mesologgi) or stops never cached
     * keep the honest error/frozen state. Online failures retry on a
     * short backoff ([onlineRetryDelays]) first. The wake failure is a
     * stale socket. A quick retry lands the data in 2-4 s instead of
     * waiting out the 15 s tick.
     */
    override fun observeArrivals(
        stopId: String,
        lines: List<Line>,
    ): Flow<List<Arrival>> =
        flow {
            var retries = 0
            while (true) {
                try {
                    delegate.observeArrivals(stopId, lines).collect {
                        // An emission means the network works. The next
                        // outage starts its own burst from zero.
                        retries = 0
                        emit(it)
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (online.value) {
                        if (retries < onlineRetryDelays.size) {
                            val delayMs = onlineRetryDelays[retries]
                            retries++
                            Log.w(
                                TAG,
                                "arrivals poll failed while online, retrying in ${delayMs}ms",
                                e,
                            )
                            delay(delayMs)
                            continue
                        }
                        // Genuine outage. Give up. The ViewModel retries on
                        // its 15 s cadence from here.
                        Log.w(TAG, "arrivals poll failed while online, no more retries", e)
                        throw e
                    }
                    val fallback = scheduledFallbackOrNull(stopId)
                    if (fallback == null) throw e
                    emit(fallback)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

    /**
     * Synthetic arrivals from the cached stop timetable. Trips from now to
     * +2 h as `isScheduled` rows with `etaMinutes`. Null when today's
     * weekday isn't cached, so the caller surfaces the original error.
     */
    private suspend fun scheduledFallbackOrNull(stopId: String): List<Arrival>? {
        // One clock read, so a call straddling midnight never mixes two days.
        val current = now()
        val day = current.dayOfWeek
        val cached =
            cache.read(
                key("stopTimetable", "$stopId-${day.name}"),
                ListSerializer(TimetableEntry.serializer()),
            ) ?: return null
        // Age cap. A seasonal timetable change would present old times as
        // "arrives in X min", and months-old data is beyond honest
        // labeling, even with the "Πρόγραμμα · εκτός σύνδεσης" rows.
        if (System.currentTimeMillis() - cached.savedAt > FALLBACK_MAX_AGE_MS) return null
        val nowMinute = current.hour * 60 + current.minute
        return cached.value
            .mapNotNull { entry ->
                val time = entry.departureTime.take(5) // "HH:mm"
                val hour = time.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                val minute = time.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
                val tripMinute = hour * 60 + minute
                // Drop past trips and trips beyond the 2 h horizon (the
                // live board's scope).
                if (tripMinute < nowMinute || tripMinute > nowMinute + FALLBACK_HORIZON_MINUTES) {
                    return@mapNotNull null
                }
                Arrival(
                    // TimetableEntry has no route id. The UI's list key
                    // falls back to the index.
                    routeCode = "",
                    lineShortName = entry.lineShortName,
                    lineName = entry.lineName,
                    destination = entry.destination,
                    etaMinutes = tripMinute - nowMinute,
                    scheduledTime = entry.departureTime,
                    vehicleId = null,
                    tripId = entry.tripId,
                    vehicle = null,
                    isScheduled = true,
                )
            }.sortedBy { it.etaMinutes }
    }

    override suspend fun getStopTimetable(
        stopId: String,
        day: DayOfWeek,
    ): List<TimetableEntry> =
        readThrough(
            // Keyed by weekday, not date. The API's date param derives from
            // the weekday, so one entry serves every Monday (holiday
            // drift is accepted).
            type = "stopTimetable",
            id = "$stopId-${day.name}",
            ttlMs = TTL_TIMETABLE,
            serializer = ListSerializer(TimetableEntry.serializer()),
            fetch = { delegate.getStopTimetable(stopId, day) },
        )

    override suspend fun getLineTimetable(
        variant: LineVariant,
        day: DayOfWeek,
    ): List<TimetableEntry> =
        readThrough(
            type = "lineTimetable",
            id = "${variantKey(variant)}-${day.name}",
            ttlMs = TTL_TIMETABLE,
            serializer = ListSerializer(TimetableEntry.serializer()),
            fetch = { delegate.getLineTimetable(variant, day) },
        )

    override val supportsStopTimetable: Boolean get() = delegate.supportsStopTimetable
    override val supportsLineTimetable: Boolean get() = delegate.supportsLineTimetable
    override val supportsStopSearch: Boolean get() = delegate.supportsStopSearch

    /** The stop catalog is one disk entry with a TTL, like the other
     *  catalogs. Search never re-fetches it per keystroke and works
     *  offline with full city coverage. No per-stop writes here. The
     *  full catalog is 4 slow server pages, and writing 3.7 k individual
     *  stop files on top of the fetch would keep the user waiting
     *  (offline nearby keeps its own incremental per-stop coverage).
     */
    override suspend fun searchStops(
        query: String,
        limit: Int,
    ): List<Stop> {
        if (query.isBlank()) return emptyList()
        return rankStopSearch(stopCatalog(), query, limit)
    }

    override suspend fun getStopCatalog(): List<Stop> = delegate.getStopCatalog()

    /** Warm the catalog when the city opens: fetch + cache the disk entry
     *  (+ register the refresh task), so the first search is instant.
     *  Failures are swallowed: offline or an outage just leaves the entry
     *  unbuilt and the search screen reports it on first use. */
    override suspend fun warmStopCatalog() {
        if (!supportsStopSearch) return
        try {
            stopCatalog()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or provider down. Nothing to do, the next search
            // retries.
        }
    }

    private suspend fun stopCatalog(): List<Stop> =
        readThrough(
            type = "stopCatalog",
            id = "",
            ttlMs = TTL_STOP_CATALOG,
            serializer = ListSerializer(Stop.serializer()),
            fetch = { delegate.getStopCatalog() },
            // A catalog is never legitimately empty. An outage
            // surfacing as [] must not pin an empty city (same rule
            // as the lines and variants catalogs).
            cacheEmpty = false,
        )

    /** Seed the stop catalog from a favorite, so it opens offline even
     *  if never seen in a nearby/route response. */
    suspend fun warmStop(stop: Stop) {
        if (stop.provider != provider) return
        cache.write(key("stops", stop.id), stop.copy(distanceKm = null), Stop.serializer())
    }

    // ---------------------------------------------------- background refresh

    // Every successful non-polling fetch registers its call behind the cache
    // key, so the connectivity-return refresh re-runs exactly the entries
    // that aged past TTL (fresh ones serve from cache, no network).
    private val refreshTasks = mutableMapOf<String, suspend () -> Unit>()

    /** Re-run every registered fetch (called once on connectivity return,
     *  recent entries no-op). The snapshot is synchronous, so no lock is
     *  held across suspension. */
    suspend fun refreshExpired() {
        val tasks = synchronized(refreshTasks) { refreshTasks.values.toList() }
        if (tasks.isEmpty()) return
        tasks.forEach { task ->
            // One failing entry must not abort the rest, and cancellation
            // must keep cancelling.
            runCatchingNotCancelled { task() }
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Read-through. Offline serves cache or fails fast (no network
     * attempt). Online: a fresh entry serves, a stale one refetches, and
     * a failed refetch serves the stale entry. Only a complete miss
     * propagates the error.
     */
    private suspend fun <T> readThrough(
        type: String,
        id: String,
        ttlMs: Long,
        serializer: KSerializer<T>,
        fetch: suspend () -> T,
        onFresh: (suspend (T) -> Unit)? = null,
        /** Catalog calls are never legitimately empty. An outage surfacing
         *  as `[]` must not pin the empty state for the TTL. Timetables
         *  keep caching empties (a no-service day is legitimate). */
        cacheEmpty: Boolean = true,
    ): T {
        val key = key(type, id)
        val cached = cache.read(key, serializer)
        if (!online.value) {
            return cached?.value ?: throw IOException(OFFLINE_MISS_MESSAGE)
        }
        if (cached != null && System.currentTimeMillis() - cached.savedAt < ttlMs) {
            return cached.value
        }
        return try {
            val value = fetchSingleFlight(key) { fetch() }
            // A failed cache write must never fail the fetch. Cancellation
            // must keep cancelling (runCatching swallows it).
            val isEmptyCatalog = !cacheEmpty && (value as? List<*>)?.isEmpty() == true
            if (!isEmptyCatalog) {
                runCatchingNotCancelled { cache.write(key, value, serializer) }
            }
            onFresh?.invoke(value)
            // Register the refetch closure. It re-runs readThrough (network
            // only for stale or absent entries). The lambda is created
            // outside the critical section.
            val task: suspend () -> Unit = {
                readThrough(type, id, ttlMs, serializer, fetch, onFresh, cacheEmpty)
            }
            synchronized(refreshTasks) { refreshTasks[key] = task }
            value
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.value ?: throw e
        }
    }

    /**
     * Shared fetch for concurrent misses of the same key (the direction
     * sheet and the lines tab opening the same variants). One network
     * fetch. The entry is dropped on settle, so failures retry on the
     * next access.
     */
    private val inflightLock = Any()
    private val inflight = mutableMapOf<String, CompletableDeferred<Any?>>()

    private suspend fun <T> fetchSingleFlight(
        key: String,
        fetch: suspend () -> T,
    ): T {
        val existing = synchronized(inflightLock) { inflight[key] }
        if (existing != null) {
            return try {
                @Suppress("UNCHECKED_CAST")
                existing.await() as T
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The leader's caller was cancelled and their fetch died
                // with it. Awaiting their failed gate must not cancel us,
                // so retry (their finally already dropped the entry, so we
                // become the leader or await the next one).
                if (!coroutineContext.isActive) throw e // we were cancelled
                fetchSingleFlight(key, fetch)
            }
        }
        val gate = CompletableDeferred<Any?>()
        val winner =
            synchronized(inflightLock) {
                inflight.getOrPut(key) { gate }
            }
        if (winner !== gate) {
            // Another caller won the race. Await their fetch.
            @Suppress("UNCHECKED_CAST")
            return winner.await() as T
        }
        // I am the leader. Fetch and publish for everyone waiting.
        try {
            val value = fetch()
            gate.complete(value)
            return value
        } catch (e: Throwable) {
            gate.completeExceptionally(e)
            throw e
        } finally {
            synchronized(inflightLock) { inflight.remove(key) }
        }
    }

    /** Client-side haversine over the disk stop catalog. An empty catalog
     *  throws, so offline-first users get the honest "nothing cached"
     *  error, never a fake empty list. */
    private suspend fun catalogStopsNear(
        lat: Double,
        lon: Double,
        limit: Int,
        originalError: Exception? = null,
    ): List<Stop> {
        val cached = cache.readAll(stopsKeyPrefix(), Stop.serializer())
        if (cached.isEmpty()) {
            throw originalError ?: IOException(OFFLINE_MISS_MESSAGE)
        }
        return cached
            .map {
                it.value.copy(
                    distanceKm = haversineKm(lat, lon, it.value.lat, it.value.lon),
                )
            }.sortedBy { it.distanceKm }
            .take(limit)
    }

    private suspend fun upsertStops(stops: List<Stop>) {
        stops.forEach { stop ->
            if (stop.id.isBlank()) return@forEach
            val cacheKey = key("stops", stop.id)
            // Skip stops already on disk with the same identity. Nearby
            // lists re-upsert on every GPS fix or poll, and each rewrite
            // is encode + tmp + rename through the cache mutex. Comparing
            // identity fields still refreshes renamed or moved stops in
            // place and preserves richer entries (serving lines).
            val existing = cache.read(cacheKey, Stop.serializer())
            if (existing?.value?.let {
                    it.name == stop.name && it.lat == stop.lat && it.lon == stop.lon
                } == true
            ) {
                return@forEach
            }
            // distanceKm is query-relative. Never persist it.
            cache.write(cacheKey, stop.copy(distanceKm = null), Stop.serializer())
        }
    }

    private fun variantKey(variant: LineVariant): String =
        if (variant.shapeId.isNullOrBlank()) variant.id else "${variant.id}-${variant.shapeId}"

    /** `${provider}-${lang}-${type}[-${id}]`. The same logical data
     *  differs per locale, so the language is part of the key. */
    private fun key(
        type: String,
        id: String,
    ): String {
        val prefix = "${provider.name.lowercase()}-$lang"
        return if (id.isBlank()) "$prefix-$type" else "$prefix-$type-$id"
    }

    /** Filename prefix of this provider+lang's stop-catalog entries. */
    private fun stopsKeyPrefix(): String = "${provider.name.lowercase()}-$lang-stops-"

    companion object {
        private const val TAG = "CachedTransitRepository"
        private const val TTL_LINES = 24L * 60 * 60 * 1000
        private const val TTL_LINE_VARIANTS = 24L * 60 * 60 * 1000
        private const val TTL_ROUTE_STOPS = 30L * 24 * 60 * 60 * 1000
        private const val TTL_ROUTE_GEOMETRY = 30L * 24 * 60 * 60 * 1000
        private const val TTL_STOP_ROUTES = 24L * 60 * 60 * 1000
        private const val TTL_TIMETABLE = 24L * 60 * 60 * 1000

        // Stops move rarely (a stop list is the same data as the 30-day
        // route-stop entries), so the full stop catalog refreshes on the
        // same cycle. Search beyond that age refetches on access.
        private const val TTL_STOP_CATALOG = 30L * 24 * 60 * 60 * 1000
        private const val OFFLINE_MISS_MESSAGE = "No network and nothing cached yet"
        private const val FALLBACK_HORIZON_MINUTES = 120 // offline arrivals look 2 h ahead

        // Older than this, a cached schedule is no longer presented as
        // "due in X min" (seasonal changes make it a lie).
        private const val FALLBACK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
