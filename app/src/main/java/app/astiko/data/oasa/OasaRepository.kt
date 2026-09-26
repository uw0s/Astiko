package app.astiko.data.oasa

import app.astiko.data.POLL_INTERVAL_MS
import app.astiko.data.TransitRepository
import app.astiko.data.decodeOrNull
import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.model.VehiclePosition
import app.astiko.util.SingleFlightCache
import app.astiko.util.compareLineShortNames
import app.astiko.util.haversineKm
import app.astiko.util.mapBounded
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.DayOfWeek

/**
 * OASA adapter: maps the raw telematics API onto the unified model.
 * All OASA-specific knowledge (actions, id chain, quirks) lives here.
 */
class OasaRepository(
    private val api: OasaApi,
    private val json: Json,
    private val langProvider: () -> String,
) : TransitRepository {
    /** Badge enrichment cache. One HTTP call per stop, data is static per
     *  session, so caching successes makes a repeat nearby refresh cost
     *  zero badge calls. A fresh area still fetches each new stop once.
     *  Failures are not cached, they retry on the next refresh instead of
     *  leaving the badges empty forever. The mutex guards only the map
     *  access, never the network, so the parallel badge fetches stay
     *  parallel. */
    private val mutex = Mutex()

    /** The line catalog: one shared single-flight fetch per process, only
     *  successful non-empty results cached (see [SingleFlightCache]). */
    private val lineEntriesCatalog = SingleFlightCache()

    /** Serving lines per stop (badges), keyed by stop id. The enrichment
     *  semantics live on the [mutex] comment. */
    private val servingLinesCache = mutableMapOf<String, List<String>>()

    override val provider: Provider = Provider.OASA

    /** OASA has no language switch. English lives in the `*Eng` fields,
     *  Latin transliterations ("KLEISOBHS"). Pick them when the app is in
     *  English, falling back to Greek. */
    private val english: Boolean get() = langProvider() == "en"

    private fun pick(
        el: String?,
        eng: String?,
    ): String? = if (english) eng ?: el else el

    /**
     * One stop of a nearby or route response. A stop without usable
     * coordinates is dropped (a row without a position cannot open the
     * arrivals map). [distanceFrom] is the query point, the API reports no
     * distance for this call.
     */
    private fun OasaStopDto.toStop(distanceFrom: GeoPoint? = null): Stop? {
        val lat = stopLat?.toDoubleOrNull() ?: return null
        val lon = stopLng?.toDoubleOrNull() ?: return null
        return Stop(
            provider = provider,
            id = stopCode,
            name = pick(stopDescr, stopDescrEng) ?: stopCode,
            street = pick(stopStreet, stopStreetEng),
            lat = lat,
            lon = lon,
            distanceKm = distanceFrom?.let { haversineKm(it.lat, it.lon, lat, lon) },
        )
    }

    /** One live bus. A missing or (0, 0) position means no GPS fix, so the
     *  position is dropped and the arrival or trip stays. */
    private fun OasaVehicleDto.toVehicle(): VehiclePosition? =
        VehiclePosition.orNull(
            vehicleId = vehicleNo ?: "",
            lat = lat?.toDoubleOrNull(),
            lon = lng?.toDoubleOrNull(),
            heading = heading?.toFloatOrNull(),
        )

    override suspend fun getStopsNear(
        lat: Double,
        lon: Double,
        limit: Int,
    ): List<Stop> {
        val objects = api.getClosestStops(lat, lon).asArrayOrNull() ?: return emptyList()
        // Sort by computed distance before taking the limit. The API's
        // arrival order is not a contract, and CityBus and the offline
        // path sort-then-take too. A reordered or paginated response must
        // not silently drop the nearest stops.
        val stops =
            objects
                .mapNotNull { obj ->
                    json
                        .decodeOrNull(obj, OasaStopDto.serializer())
                        ?.toStop(distanceFrom = GeoPoint(lat, lon))
                }.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
                .take(limit)

        // Enrich with the lines serving each stop, one extra call per stop,
        // bounded so a nearby refresh or the 100-stop offline prefetch does
        // not fire 100 simultaneous requests at the API. A single failure
        // only drops that stop's badges.
        return stops.mapBounded(VEHICLE_JOIN_CONCURRENCY) { stop ->
            stop.copy(
                servingLines =
                    runCatchingNotCancelled { servingLinesOf(stop.id) }.getOrDefault(emptyList()),
            )
        }
    }

    override suspend fun getLines(): List<Line> {
        // The master-lines catalog must never legitimately be empty (191
        // records). A `""`/non-array response is an outage, not "no lines",
        // so propagate it. The Lines tab then shows a retryable error
        // instead of a fake empty city (same rule as the OSETh route
        // catalog). A verified `[]` still flows through as empty, never
        // pinned by the disk cache (cacheEmpty=false).
        val objects =
            api.getMasterLines().asArrayOrNull()
                ?: throw IOException("OASA master-lines catalog unavailable")
        return objects
            .mapNotNull { obj ->
                val dto = json.decodeOrNull(obj, OasaMasterLineDto.serializer())
                    ?: return@mapNotNull null
                val lineCode = dto.lineCode ?: return@mapNotNull null
                Line(
                    provider = provider,
                    id = lineCode,
                    shortName = dto.mlId ?: "",
                    longName = pick(dto.mlDescr, dto.mlDescrEng) ?: "",
                )
            }.sortedWith(Comparator { a, b -> compareLineShortNames(a.shortName, b.shortName) })
    }

    override suspend fun getLineVariants(line: Line): List<LineVariant> {
        // OASA models directions inconsistently. Line 5 has one entry with
        // two routes (both directions), line 021 has one entry per
        // direction. Collect the active routes of every entry of the line.
        val entries =
            allLineEntries()
                .filter { it.lineId == line.shortName }
                .ifEmpty {
                    // Combined public numbers ("219-816") never appear in
                    // webGetLines as such. The master list merges two lines
                    // into one public number, while the variant list stores
                    // each part under its own LineID/line_code (219 maps to
                    // 1036, 816 to 1037). Fall back to matching the parts.
                    line.shortName.split('-').flatMap { part ->
                        allLineEntries().filter { it.lineId == part }
                    }
                }
        if (entries.isEmpty()) return emptyList()
        return coroutineScope {
            entries
                .map { entry ->
                    async {
                        // One entry's network failure must not fail the whole
                        // sheet. The other directions still resolve, same
                        // per-item isolation as the arrivals vehicle join.
                        runCatchingNotCancelled {
                            api.getRoutesForLine(entry.lineCode).asArrayOrNull().orEmpty()
                        }.getOrDefault(emptyList())
                            .mapNotNull { obj ->
                                json.decodeOrNull(obj, OasaRouteForLineDto.serializer())
                            }.filter { it.routeActive != "0" }
                            .map { r ->
                                LineVariant(
                                    provider = provider,
                                    // lineId = the matched entry's internal code,
                                    // not the caller's Line.id. The same direction
                                    // reached from the Lines tab (Line.id =
                                    // line_code) and from the arrivals screen
                                    // (Line.id = route_code) must carry one
                                    // identity. The favorites heart (isSameRoute)
                                    // depends on it.
                                    lineId = entry.lineCode,
                                    id = r.routeCode,
                                    lineShortName = line.shortName,
                                    label = pick(r.routeDescr, r.routeDescrEng) ?: line.shortName,
                                )
                            }
                    }
                }.awaitAll()
                .flatten()
                .distinctBy { it.id }
        }
    }

    private suspend fun allLineEntries(): List<OasaLineDto> =
        lineEntriesCatalog.get(
            key = "lines",
            // Only successful results with data are cached. A failed
            // or cancelled fetch must not pin the empty list.
            cacheIt = { it.isNotEmpty() },
        ) { fetchLineEntries() }

    private suspend fun fetchLineEntries(): List<OasaLineDto> {
        // Catalog failures propagate. A `""`/non-array response is an
        // outage, the lines catalog always has ~250 records. A swallowed
        // failure would answer "no directions" on an outage instead of a
        // retryable error.
        val objects =
            api.getLines().asArrayOrNull()
                ?: throw IOException("OASA line catalog unavailable")
        return objects.mapNotNull { obj ->
            json.decodeOrNull(obj, OasaLineDto.serializer())
        }
    }

    override suspend fun getVariantStops(variant: LineVariant): List<Stop> {
        // variant.id is a route_code. Its stops come straight from webGetStops.
        val stops = api.getStopsForRoute(variant.id).asArrayOrNull() ?: return emptyList()
        return stops.mapNotNull { obj ->
            json.decodeOrNull(obj, OasaStopDto.serializer())?.toStop()
        }
    }

    override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> {
        // The endpoint answers {"details": [...], "stops": [...]}. Points
        // are lon/lat.
        val element = api.getRouteDetailsAndStops(variant.id) as? JsonObject ?: return emptyList()
        val objects = element["details"] as? JsonArray ?: return emptyList()
        return objects.mapNotNull { obj ->
            val dto = json.decodeOrNull(obj, OasaRoutePointDto.serializer())
                ?: return@mapNotNull null
            val lat = dto.y?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = dto.x?.toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat, lon)
        }
    }

    override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
        flow {
            while (true) {
                val objects = api.getBusLocation(variant.id).asArrayOrNull() ?: emptyList()
                val vehicles =
                    objects.mapNotNull { obj ->
                        json.decodeOrNull(obj, OasaVehicleDto.serializer())?.toVehicle()
                    }
                emit(vehicles)
                delay(POLL_INTERVAL_MS)
            }
        }

    private suspend fun servingLinesOf(stopId: String): List<String> {
        mutex.withLock { servingLinesCache[stopId]?.let { return it } }
        // The network call runs outside the mutex. The badge enrichment
        // fires these per stop in parallel (see getStopsNear).
        val result =
            runCatchingNotCancelled {
                api
                    .getRoutesForStop(stopId)
                    .asArrayOrNull()
                    .orEmpty()
                    .mapNotNull { obj ->
                        json.decodeOrNull(obj, OasaRouteDto.serializer())
                    }.filter { it.hidden != "1" }
                    .mapNotNull { it.lineId }
                    .distinct()
            }
        mutex.withLock { result.onSuccess { servingLinesCache[stopId] = it } }
        return result.getOrDefault(emptyList())
    }

    override suspend fun getStopRoutes(stopId: String): List<Line> {
        val objects = api.getRoutesForStop(stopId).asArrayOrNull() ?: return emptyList()
        return objects.mapNotNull { obj ->
            val dto = json.decodeOrNull(obj, OasaRouteDto.serializer())
                ?: return@mapNotNull null
            if (dto.hidden == "1") return@mapNotNull null // hidden routes are not real
            Line(
                provider = provider,
                id = dto.routeCode, // arrivals reference route_code
                shortName = dto.lineId ?: "",
                longName = pick(dto.lineDescr, dto.lineDescrEng) ?: "",
                destination = pick(dto.routeDescr, dto.routeDescrEng) ?: "",
            )
        }
    }

    /** One row per line: OASA reports one route per direction, both sharing
     * the public LineID (the direction lives in RouteDescr). The list passed
     * to observeArrivals is not deduped. Arrivals match per route_code. */
    override fun stopLinesForDisplay(lines: List<Line>): List<Line> =
        lines
            .distinctBy { it.shortName }
            .sortedWith(Comparator { a, b -> compareLineShortNames(a.shortName, b.shortName) })

    override fun observeArrivals(
        stopId: String,
        lines: List<Line>,
    ): Flow<List<Arrival>> =
        flow {
            while (true) {
                val objects = api.getStopArrivals(stopId).asArrayOrNull() ?: emptyList()
                val arrivals =
                    objects
                        .mapNotNull { obj ->
                            val dto = json.decodeOrNull(obj, OasaArrivalDto.serializer())
                                ?: return@mapNotNull null
                            val minutes = dto.minutes?.toIntOrNull() ?: return@mapNotNull null
                            val line = lines.firstOrNull { it.id == dto.routeCode }
                            Arrival(
                                routeCode = dto.routeCode,
                                lineShortName = line?.shortName ?: "",
                                lineName = line?.longName ?: "",
                                destination = line?.destination ?: "",
                                etaMinutes = minutes,
                                vehicleId = dto.vehicleCode,
                            )
                        }.sortedBy { it.etaMinutes }

                // Live positions: getBusLocation per involved route, matched
                // by vehicle number. Fetched in parallel and chunked. A busy
                // stop can involve a dozen routes, and their latencies sum
                // on a sequential fetch, stalling the 15 s poll. 6 in flight
                // is polite to an unofficial API (same bound as the offline
                // prefetcher).
                val vehiclesByRoute =
                    arrivals
                        .map { it.routeCode }
                        .distinct()
                        .mapBounded(VEHICLE_JOIN_CONCURRENCY) { routeCode ->
                            routeCode to
                                runCatchingNotCancelled {
                                    api
                                        .getBusLocation(routeCode)
                                        .asArrayOrNull()
                                        .orEmpty()
                                        .mapNotNull { obj ->
                                            json
                                                .decodeOrNull(obj, OasaVehicleDto.serializer())
                                                ?.toVehicle()
                                        }
                                }.getOrDefault(emptyList())
                        }.toMap()
                emit(
                    arrivals.map { a ->
                        a.copy(
                            vehicle =
                                vehiclesByRoute[a.routeCode]
                                    .orEmpty()
                                    .firstOrNull { it.vehicleId == a.vehicleId },
                        )
                    },
                )
                delay(POLL_INTERVAL_MS)
            }
        }

    // Timetables: the API has line-level schedules only (getDailySchedule,
    // current service day, no day-of-week parameter) and it is not wired
    // yet. Until then the capabilities stay off and the entry points are
    // hidden.
    override val supportsStopTimetable: Boolean get() = false
    override val supportsLineTimetable: Boolean get() = false

    override suspend fun getStopTimetable(
        stopId: String,
        day: DayOfWeek,
    ): List<TimetableEntry> = emptyList()

    override suspend fun getLineTimetable(
        variant: LineVariant,
        day: DayOfWeek,
    ): List<TimetableEntry> = emptyList()

    // Stop search needs a stop index (the API has no name-search endpoint).
    // Until an index exists the capability stays off and the search entry
    // point is hidden. The default searchStops would only ever see this
    // empty list.
    override suspend fun getStopCatalog(): List<Stop> = emptyList()

    companion object {
        /** Parallel getBusLocation fetches per arrivals poll: a dozen
         *  routes at a busy stop must not serialize into a multi-second
         *  poll stall. */
        private const val VEHICLE_JOIN_CONCURRENCY = 6
    }
}

/** `""`/`null`/object -> null (OASA says "nothing running"). Array -> its objects. */
private fun JsonElement.asArrayOrNull(): List<JsonObject>? =
    when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        else -> null
    }
