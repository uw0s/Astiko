package app.astiko.data.oseth

import android.util.Log
import app.astiko.BuildConfig
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
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * OSETh adapter: maps the Thessaloniki telematics API onto the unified model.
 * All OSETh-specific knowledge (envelope, date format, id scheme) lives here.
 */
class OseThRepository(
    private val api: OseThApi,
    private val json: Json,
    private val langProvider: () -> String,
    /** Stop-catalog source: data.gov.gr GTFS (see OseThGtfsCatalog). Null
     *  = telematics walk only (tests, and a never-wired adapter). */
    private val gtfsCatalog: OseThGtfsCatalog? = null,
) : TransitRepository {
    private val lang: String get() = langProvider()

    // The route catalog (~30 routes), fetched once per language per
    // process. getLineVariants re-reads it instead of re-fetching the
    // whole /route list per line. One full-list fetch per line would
    // make the offline prefetch fire 29 calls for 1. Failures are not
    // cached, so a transient error retries on the next access (see
    // SingleFlightCache). One cache per catalog: the routes list and the
    // routeInfo responses must not share keys.
    private val routesCatalog = SingleFlightCache()
    private val routeInfoCatalog = SingleFlightCache()

    // Shape ids the provider has retired, mapped to the shape it serves for
    // the same direction today (key: routeId + the stored shape). Learned
    // from a rejected pair, so the failed attempt happens once per direction
    // instead of on every open and every 15 s vehicle poll.
    private val resolvedShapes = ConcurrentHashMap<Pair<String, String>, String>()

    @Volatile private var lastRoutesRefreshAt = 0L

    // The full stop catalog (3,698 stops, 4 pages of 1000), fetched once
    // per language per process and filtered locally by searchStops. The
    // API's name-search param (stopName) is ignored by the server, so
    // there is no server-side search to lean on. The web app filters the
    // full list client-side too. Failures propagate (search shows the
    // error state) and are not cached, so a transient error retries.
    private val stopsCatalog = SingleFlightCache()

    private suspend fun stopCatalog(): List<OseThStopDto> =
        stopsCatalog.get(
            key = lang,
            cacheIt = { it.isNotEmpty() },
        ) {
            // GTFS-first: the data.gov.gr feed (~2 s download, ~1-3 s
            // parse, then instant from disk) replaces the telematics walk
            // as the catalog source. See OseThGtfsCatalog for the design.
            // load() returns null on any failure (dead portal, broken zip,
            // sparse feed), so a dead data.gov.gr degrades to the walk and
            // never breaks search. The feed covers 3,648 stops, the
            // telematics list has 3,698. The 50 extras are
            // telematics-only dupes/edges (98.6 % coverage, accepted).
            val gtfs = gtfsCatalog?.load()
            if (gtfs != null) {
                return@get gtfs.map { it.toStopDto() }
            }
            // Fallback: the telematics walk. Page 1 first (it carries the
            // catalog `total`), then the rest in parallel. 4 pages of
            // ~600 KB sequential are slow on a flaky link, parallel cuts
            // the wait. The first search is the only one that fetches, the
            // disk catalog serves afterwards. The page walk is bounded by
            // `total`, so a growing catalog still comes back complete (no
            // fixed 4-page cap).
            val first = fetchCatalogPage(1)
            val total = first.total ?: first.stops.size
            val pageCount = ((total + STOPS_PAGE_SIZE - 1) / STOPS_PAGE_SIZE).coerceAtLeast(1)
            if (pageCount == 1) return@get first.stops
            val rest =
                coroutineScope {
                    (2..pageCount)
                        .map { page -> async { fetchCatalogPage(page) } }
                        .map { it.await() }
                }
            (listOf(first) + rest).flatMap { it.stops }
        }

    private suspend fun fetchCatalogPage(page: Int): OseThStopsDataDto {
        // A missing envelope propagates, same rule as every catalog here.
        // The decode runs on IO because Retrofit resumes this coroutine on
        // the caller's Main dispatcher, and a 600 KB page of nested routes
        // would freeze the UI.
        val data =
            api
                .stops(
                    lang = lang,
                    page = page,
                    language = lang,
                ).dataOrNull()
                ?: throw IOException("OSETh stop catalog unavailable")
        return withContext(Dispatchers.IO) {
            json.decodeFromJsonElement(OseThStopsDataDto.serializer(), data)
        }
    }

    private suspend fun routesDto(maxAgeMs: Long = Long.MAX_VALUE): OseThRoutesDataDto =
        routesCatalog.get(
            key = lang,
            maxAgeMs = maxAgeMs,
            // Empty routes are never cached. A 200 with {"routes": []} (an
            // outage shape) must not pin an empty Lines tab for the process,
            // same guard as the OASA lines catalog.
            cacheIt = { it.routes.isNotEmpty() },
        ) {
            // Catalog failures propagate. A dead or shape-changed API must
            // surface as a retryable error, never a fake empty Lines tab.
            // Swallowing failures with runCatching + getOrNull would turn
            // every transport/decode failure into null and getLines into [].
            val data =
                api.routes(lang = lang, language = lang).dataOrNull()
                    ?: throw IOException("OSETh route catalog unavailable")
            json.decodeFromJsonElement(OseThRoutesDataDto.serializer(), data)
        }

    // One direction's route info (geometry + ordered stops + live
    // vehicles), fetched once per (lang, routeId, shapeId) per process.
    // getVariantStops and getRouteGeometry are separate interface calls
    // that hit the same endpoint. Sharing the response halves the network
    // calls, about 130 routeInfo fetches for ~65 variants in the offline
    // prefetch. observeVehicles calls the API directly and stays live.
    private suspend fun routeInfo(
        routeId: String,
        shapeId: String,
    ): RouteInfoResult =
        routeInfoCatalog.get(
            key = "$lang-$routeId-$shapeId",
            cacheIt = { it is RouteInfoResult.Loaded },
        ) {
            val body =
                runCatchingNotCancelled {
                    api.routeInfo(
                        lang = lang,
                        routeId = routeId,
                        shapeId = shapeId,
                        language = lang,
                    )
                }.getOrNull() ?: return@get RouteInfoResult.Failed
            val data = body.dataOrNull()
            if (data == null) {
                logMissingData("route info", body)
                return@get RouteInfoResult.RetiredShape
            }
            runCatchingNotCancelled {
                RouteInfoResult.Loaded(
                    json.decodeFromJsonElement(OseThRouteInfoDto.serializer(), data),
                )
            }.getOrDefault(RouteInfoResult.Failed)
        }

    /**
     * One direction's route info. The stored shape id is tried first, so an id
     * the provider still serves is never rewritten. An answer without data
     * means it was renumbered: the direction's current shape is tried once.
     */
    private suspend fun routeInfoFor(variant: LineVariant): OseThRouteInfoDto? {
        val memoryKey = variant.id to (variant.shapeId ?: "")
        // The stored id was refused earlier, so the learned shape stands in.
        // A refusal here means the provider renumbered again, learn it below.
        val remembered = resolvedShapes[memoryKey]
        if (remembered != null) {
            when (val outcome = routeInfo(variant.id, remembered)) {
                is RouteInfoResult.Loaded -> return outcome.dto
                RouteInfoResult.Failed -> return null
                RouteInfoResult.RetiredShape -> resolvedShapes.remove(memoryKey)
            }
        } else {
            when (val outcome = routeInfo(variant.id, variant.shapeId ?: "")) {
                is RouteInfoResult.Loaded -> return outcome.dto
                RouteInfoResult.Failed -> return null
                RouteInfoResult.RetiredShape -> Unit
            }
        }
        val replacement = replacementShape(variant) ?: return null
        val outcome = routeInfo(variant.id, replacement)
        return if (outcome is RouteInfoResult.Loaded) {
            resolvedShapes[memoryKey] = replacement
            outcome.dto
        } else {
            null
        }
    }

    /**
     * The shape to try instead, or null when there is nothing safe to
     * substitute. The catalog in hand answers first, and only a direction it
     * cannot name costs a refetch, at most once a minute.
     */
    private suspend fun replacementShape(variant: LineVariant): String? {
        knownReplacement(variant)?.let { return it }
        refreshRoutesDto()
        return knownReplacement(variant)
    }

    /**
     * The shape the catalog lists for [variant]'s direction when it differs
     * from the one in hand. Null for an unknown direction (never guess across
     * route ids), for a shape the catalog still lists, and for several
     * candidates with no headsign match. A wrong weekday or weekend twin is
     * worse than the error the screen shows.
     */
    private suspend fun knownReplacement(variant: LineVariant): String? {
        val dto = runCatchingNotCancelled { routesDto() }.getOrNull() ?: return null
        val shapes =
            dto.routes
                .flatMap { it.tripHeadsigns.orEmpty() }
                .filter { it.routeId == variant.id }
                .mapNotNull { headsign -> headsign.shapeId?.let { it to (headsign.headsign ?: "").trim() } }
                .distinct()
        if (shapes.isEmpty()) return null
        if (shapes.any { it.first == variant.shapeId }) return null
        val matching = shapes.filter { it.second == variant.label.trim() }.map { it.first }.distinct()
        return when {
            matching.size == 1 -> matching.single()
            shapes.size == 1 -> shapes.single().first
            else -> null
        }
    }

    private suspend fun refreshRoutesDto() {
        val now = System.currentTimeMillis()
        if (now - lastRoutesRefreshAt < ROUTES_REFRESH_INTERVAL_MS) return
        lastRoutesRefreshAt = now
        runCatchingNotCancelled { routesDto(maxAgeMs = 0L) }
    }

    /** The provider answers HTTP 200 with its upstream failure in the envelope,
     *  which is what a retired shape id looks like. The text names the request
     *  it tried, so release logcat stays clean of ids. */
    private fun logMissingData(
        what: String,
        body: JsonElement,
    ) {
        if (!BuildConfig.DEBUG) return
        val reason = ((body as? JsonObject)?.get("error"))?.toString().orEmpty()
        Log.w(TAG, "$what came back without data: ${reason.take(400)}")
    }

    override val provider: Provider = Provider.OSETh

    override val supportsStopSearch: Boolean get() = true

    /** GTFS index entry -> the DTO shape the rest of the adapter already
     *  maps to Stop (see getStopCatalog). The GTFS index carries both
     *  names. The language is picked here: English = the feed's
     *  translation when present, Greek = stop_name. The decorator's
     *  lang-keyed disk entry then gets the same per-language list as
     *  before. */
    private fun GtfsStopEntry.toStopDto(): OseThStopDto =
        OseThStopDto(
            id = id,
            code = id,
            name = if (lang == "en") nameEn ?: nameEl else nameEl,
            latitude = lat,
            longitude = lon,
            routes = lines.map { OseThRouteDto(shortName = it) },
        )

    /**
     * One catalog or nearby stop. A stop without an id or a position is
     * dropped (a search hit cannot be opened on the arrivals map without
     * them). The nearby response reports meters, the route stop list no
     * distance at all.
     */
    private fun OseThStopDto.toStop(distanceKm: Double? = null): Stop? {
        val id = id ?: code ?: return null
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return Stop(
            provider = provider,
            id = id,
            name = name ?: id,
            lat = lat,
            lon = lon,
            distanceKm = distanceKm,
            servingLines = routes.orEmpty().mapNotNull { it.shortName }.distinct(),
        )
    }

    /** One stop of a route's ordered list. Same identity rules, no
     *  serving-line badges (the route response carries none). */
    private fun OseThRouteStopDto.toStop(): Stop? {
        val id = id ?: code ?: return null
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        return Stop(provider = provider, id = id, name = name ?: id, lat = lat, lon = lon)
    }

    /** One live bus. A missing or (0, 0) position means no GPS fix, so the
     *  position is dropped and the arrival or trip stays. */
    private fun OseThVehicleDto.toVehicle(): VehiclePosition? =
        VehiclePosition.orNull(
            vehicleId = id ?: "",
            lat = latitude,
            lon = longitude,
            heading = bearing?.toFloat(),
        )

    override suspend fun getStopCatalog(): List<Stop> =
        // The mapping is shared with searchStops' default (rank over this
        // list). The catalog carries badges so search rows show them.
        stopCatalog().mapNotNull { it.toStop() }

    override suspend fun getStopsNear(
        lat: Double,
        lon: Double,
        limit: Int,
    ): List<Stop> {
        // size = limit. The endpoint defaults to 20 regardless of what the
        // caller asks for, so the offline prefetch's 100-stop seed would
        // otherwise silently shrink to 20.
        // A missing envelope is a malformed/error response. Propagate it.
        // A verified {"stops": []} stays a semantic-empty (no stops near).
        val data =
            api
                .nearbyStops(
                    lang = lang,
                    lat = lat,
                    lon = lon,
                    language = lang,
                    size = limit,
                ).dataOrNull()
                ?: throw IOException("OSETh stops-near response unavailable")
        val dto = json.decodeFromJsonElement(OseThStopsDataDto.serializer(), data)
        // Sort by distance before taking the limit (same rule as OASA/CityBus).
        return dto.stops
            .mapNotNull { it.toStop(distanceKm = it.distance?.div(1000.0)) }
            .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
            .take(limit)
    }

    override suspend fun getStopRoutes(stopId: String): List<Line> {
        // Same contract as getStopsNear. A missing envelope is an error, a
        // verified {"routes": []} stays a semantic-empty (stop with no
        // scheduled service).
        val data =
            api
                .stopInfo(
                    lang = lang,
                    stopId = stopId,
                    language = lang,
                ).dataOrNull()
                ?: throw IOException("OSETh stop info unavailable")
        val dto = json.decodeFromJsonElement(OseThStopDto.serializer(), data)
        return dto.routes.orEmpty().mapNotNull { r ->
            r.id?.let {
                Line(
                    provider = provider,
                    id = it,
                    shortName = r.shortName ?: "",
                    longName = r.longName ?: "",
                )
            }
        }
    }

    override suspend fun getLines(): List<Line> {
        val dto = routesDto() // catalog failures propagate (see routesDto)
        return dto.routes
            .mapNotNull { r ->
                r.id?.let {
                    Line(
                        provider = provider,
                        id = it,
                        shortName = r.shortName ?: "",
                        longName = r.longName ?: "",
                    )
                }
            }.sortedWith(Comparator { a, b -> compareLineShortNames(a.shortName, b.shortName) })
    }

    override suspend fun getLineVariants(line: Line): List<LineVariant> {
        val dto = routesDto() // catalog failures propagate (see routesDto)
        val route =
            dto.routes.firstOrNull { it.id == line.id }
                // The stop's line entries carry a direction-specific route id
                // (01_7429_2_3) while /route lists one entry per line
                // (01_7429_1_3). Fall back to the line number, which is
                // unique in /route, so arrivals-screen lines resolve too.
                ?: dto.routes.firstOrNull { it.shortName == line.shortName }
        return route
            ?.tripHeadsigns
            .orEmpty()
            .mapNotNull { h ->
                val routeId = h.routeId ?: return@mapNotNull null
                LineVariant(
                    provider = provider,
                    // lineId = the matched route's id, not the caller's
                    // Line.id. The same direction reached from the Lines tab
                    // (parent route id) and from the arrivals screen
                    // (direction-specific id) must carry one identity. The
                    // favorites heart (isSameRoute) depends on it.
                    lineId = route?.id ?: line.id,
                    id = routeId,
                    lineShortName = line.shortName,
                    shapeId = h.shapeId,
                    label = h.headsign ?: line.shortName,
                )
            }
    }

    override suspend fun getVariantStops(variant: LineVariant): List<Stop> {
        // A null here means the fetch/decode failed and the shape id could not
        // be replaced either. Surface it as an error instead of a misleading
        // "no stops on this route" empty board.
        val dto = routeInfoFor(variant) ?: throw IOException("route info unavailable")
        return dto.stops.sortedBy { it.sequence ?: Int.MAX_VALUE }.mapNotNull { it.toStop() }
    }

    override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> {
        val dto = routeInfoFor(variant) ?: throw IOException("route info unavailable")
        return parseWkt(dto.shape?.lineString ?: return emptyList())
    }

    override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
        flow {
            while (true) {
                // A learned shape stands in for the stored one, so the poll
                // does not ask for a dead id every 15 s.
                val memoryKey = variant.id to (variant.shapeId ?: "")
                val shapeId = resolvedShapes[memoryKey] ?: variant.shapeId ?: ""
                val data =
                    api
                        .routeInfo(
                            lang = lang,
                            routeId = variant.id,
                            shapeId = shapeId,
                            language = lang,
                        ).dataOrNull()
                if (data == null && resolvedShapes[memoryKey] == null) {
                    replacementShape(variant)?.let { resolvedShapes[memoryKey] = it }
                }
                val vehicles =
                    if (data == null) {
                        emptyList()
                    } else {
                        json
                            .decodeOrNull(data, OseThRouteInfoDto.serializer())
                            ?.vehicles
                            .orEmpty()
                            .mapNotNull { it.toVehicle() }
                    }
                emit(vehicles)
                delay(POLL_INTERVAL_MS)
            }
        }

    /** One row per line: stop info reports both direction routes with
     * identical names (`01_7429_1_3` / `01_7429_2_3`). Weekend variants
     * keep their distinct longName and stay as their own row. */
    override fun stopLinesForDisplay(lines: List<Line>): List<Line> =
        lines
            .distinctBy { it.shortName to it.longName }
            .sortedWith(Comparator { a, b -> compareLineShortNames(a.shortName, b.shortName) })

    override fun observeArrivals(
        stopId: String,
        lines: List<Line>,
    ): Flow<List<Arrival>> =
        flow {
            while (true) {
                val date = LocalDateTime.now().format(DATE_FORMAT) // dd/MM/yyyy HH:mm:ss
                val data =
                    api
                        .stopTimetable(
                            lang = lang,
                            stopId = stopId,
                            language = lang,
                            date = date,
                        ).dataOrNull()
                val arrivals =
                    if (data == null) {
                        emptyList()
                    } else {
                        // Decode failures propagate (same rule as CityBus).
                        val dto = json.decodeFromJsonElement(OseThTimetableDto.serializer(), data)
                        // Live arrivals only: `monitored` = a bus is physically
                        // in transit. The endpoint also returns the rest of the
                        // day's schedule (monitored=false, no vehicle), which
                        // belongs to the schedule view.
                        dto.trips
                            .orEmpty()
                            .filter { it.monitored == true }
                            .mapNotNull { trip ->
                                val minutes =
                                    trip.arrivalInMinutes ?: trip.departureInMinutes
                                        ?: return@mapNotNull null
                                // Trips carry their route embedded. Fall back
                                // to the lines join.
                                val route = trip.route
                                val line = lines.firstOrNull { it.id == route?.id }
                                Arrival(
                                    routeCode = route?.id ?: line?.id ?: "",
                                    lineShortName = route?.shortName ?: line?.shortName ?: "",
                                    lineName = route?.longName ?: line?.longName ?: "",
                                    destination = trip.headsign ?: "",
                                    etaMinutes = minutes,
                                    scheduledTime = trip.arrivalTime?.take(5), // "HH:mm:ss" -> "HH:mm" (model contract)
                                    tripId = trip.id,
                                    // The shape discriminator of the running
                                    // trip. Without it the tap-to-route line
                                    // would draw the weekday twin of a weekend
                                    // trip (same routeId, different shape).
                                    shapeId = trip.shapeId,
                                    vehicle = trip.vehicle?.toVehicle(),
                                )
                            }.sortedBy { it.etaMinutes }
                    }
                emit(arrivals)
                delay(POLL_INTERVAL_MS)
            }
        }

    // Timetables. The stop endpoint returns the remaining-day schedule
    // from the passed date, so pass the day at 00:00:00 to get the whole
    // day. The route endpoint returns the service day's departures for
    // the passed date, 0 trips on days the route doesn't run (line 01 on
    // weekends).
    override val supportsStopTimetable: Boolean get() = true
    override val supportsLineTimetable: Boolean get() = true

    override suspend fun getStopTimetable(
        stopId: String,
        day: DayOfWeek,
    ): List<TimetableEntry> {
        // The arrivals flow filters monitored == true (live trips). A
        // timetable keeps every scheduled trip, they all belong here.
        // Decode failures propagate. A swallowed failure would be cached
        // as a 24 h "no trips" board, and so would a missing envelope.
        // Only a verified {"trips": []}, a day with no service, is a
        // legit empty worth caching.
        val data =
            api
                .stopTimetable(
                    lang = lang,
                    stopId = stopId,
                    language = lang,
                    date = dateFor(day),
                ).dataOrNull()
                ?: throw IOException("OSETh stop timetable unavailable")
        val dto = json.decodeFromJsonElement(OseThTimetableDto.serializer(), data)
        return dto.trips
            .orEmpty()
            .mapNotNull { trip ->
                val time = trip.departureTime ?: trip.arrivalTime ?: return@mapNotNull null
                TimetableEntry(
                    lineShortName = trip.route?.shortName ?: "",
                    lineName = trip.route?.longName ?: "",
                    destination = trip.headsign ?: "",
                    departureTime = time.take(5), // "HH:mm:ss" -> "HH:mm"
                    tripId = trip.id,
                )
            }.sortedBy { it.departureTime }
    }

    override suspend fun getLineTimetable(
        variant: LineVariant,
        day: DayOfWeek,
    ): List<TimetableEntry> {
        // Departures from the route's origin, for the service day of the
        // passed date. shapeId selects the direction's service split. Decode
        // failures propagate (same rule as getStopTimetable).
        //
        // A direction has one routeId with multiple shapes. The weekday shape
        // and the ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ shape serve different days. The timetable
        // must answer "does the line run that day", not "does this shape run
        // that day". When the variant's own shape has no trips, the other
        // shapes of the same routeId are tried in order (see siblingShapeIds).
        val shapeIds = siblingShapeIds(variant)
        if (shapeIds.isEmpty()) return emptyList()
        // A missing envelope on one shape is not "no service". The
        // same-direction siblings are still tried, a dead endpoint must not
        // answer "the line doesn't run that day". Only a verified empty
        // response, from at least one shape, means "no service".
        var (trips, answered) = timetableTrips(variant, day, shapeIds)
        if (trips.isNotEmpty() || answered) return trips
        // Every candidate came back without data, so the catalog's shape ids
        // are older than the provider's. Refetch it and walk the ids it lists
        // now, skipping the ones already refused.
        refreshRoutesDto()
        val refreshedShapeIds = siblingShapeIds(variant).filterNot { it in shapeIds }
        if (refreshedShapeIds.isNotEmpty()) {
            val retried = timetableTrips(variant, day, refreshedShapeIds)
            trips = retried.first
            answered = retried.second
        }
        if (answered) return trips
        // An error is not cached, an empty board would be pinned for a day.
        throw IOException("OSETh route timetable unavailable")
    }

    /**
     * The trips of the first shape in [shapeIds] that serves a schedule, and
     * whether any shape answered with a payload at all. The flag separates a
     * verified empty board from every id being stale, which the caller retries.
     */
    private suspend fun timetableTrips(
        variant: LineVariant,
        day: DayOfWeek,
        shapeIds: List<String>,
    ): Pair<List<TimetableEntry>, Boolean> {
        var answered = false
        for (shapeId in shapeIds) {
            val body =
                api.routeTimetable(
                    lang = lang,
                    routeId = variant.id,
                    date = dateFor(day),
                    shapeId = shapeId,
                    language = lang,
                )
            val data = body.dataOrNull()
            if (data == null) {
                logMissingData("route timetable", body)
                continue
            }
            answered = true
            val dto = json.decodeFromJsonElement(OseThRouteTimetableDto.serializer(), data)
            val trips =
                dto.trips
                    .mapNotNull { trip ->
                        val time = trip.departureTime ?: return@mapNotNull null
                        TimetableEntry(
                            lineShortName = dto.shortName ?: variant.lineShortName,
                            lineName = dto.longName ?: "",
                            destination = trip.headsign ?: dto.headsign ?: "",
                            departureTime = time.take(5), // "HH:mm:ss" -> "HH:mm"
                            tripId = trip.id,
                        )
                    }.sortedBy { it.departureTime }
            if (trips.isNotEmpty()) return trips to true
        }
        return emptyList<TimetableEntry>() to answered
    }

    /**
     * The shapeIds to query for one direction's timetable, in order: the
     * variant's own shape first, then the other shapes of the same route
     * (the weekday/weekend split shares one routeId, resolved from the
     * cached /route catalog, no network). Empty when the route is unknown.
     *
     * A /route entry lists the headsigns of both directions of the line,
     * and the timetable endpoint answers 400 (its body {"data": ""}) for a
     * shape paired with the other direction's route id, so only headsigns
     * whose routeId matches the variant are siblings.
     */
    private suspend fun siblingShapeIds(variant: LineVariant): List<String> {
        val dto = routesDto() // catalog failures propagate (see routesDto)
        val route =
            dto.routes.firstOrNull { it.id == variant.id }
                ?: dto.routes.firstOrNull { it.shortName == variant.lineShortName }
        val shapes =
            route
                ?.tripHeadsigns
                .orEmpty()
                .filter { it.routeId == variant.id }
                .mapNotNull { it.shapeId }
                .distinct()
        // A shape learned from an earlier renumbering goes first: the stored
        // one is known dead and would only cost a refused request.
        val own = resolvedShapes[variant.id to (variant.shapeId ?: "")] ?: variant.shapeId
        return if (own == null) {
            shapes
        } else {
            listOf(own) + shapes.filter { it != own }
        }
    }

    /** The next calendar date whose weekday is [day]. The API serves the
     * service day of the passed date, so the selected weekday's schedule
     * needs that weekday's date at 00:00:00 (today when it matches). */
    private fun dateFor(day: DayOfWeek): String {
        var date = LocalDate.now()
        while (date.dayOfWeek != day) date = date.plusDays(1)
        return date.format(DAY_FORMAT) + " 00:00:00"
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        /** The server caps paginated sizes at 1000 (probed live). */
        private const val STOPS_PAGE_SIZE = 1000

        /** A forced refetch reacts to a rejected id, it does not poll. Once a
         *  minute, so an outage cannot turn every failed call into a fetch. */
        private const val ROUTES_REFRESH_INTERVAL_MS = 60_000L

        private const val TAG = "OseThRepository"
    }
}

/** "LINESTRING (lon lat, lon lat, ...)" -> points. */
internal fun parseWkt(lineString: String): List<GeoPoint> {
    val inner =
        lineString
            .removePrefix("LINESTRING")
            .trim()
            .removePrefix("(")
            .removeSuffix(")")
    return inner.split(",").mapNotNull { pair ->
        val parts = pair.trim().split(Regex("\\s+"))
        val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        GeoPoint(lat, lon)
    }
}

/**
 * Unwrap the {"data": ...} envelope. OSETh answers HTTP 200 with data set
 * to the empty string when its upstream call failed, and every endpoint's
 * payload is an object, so anything else counts as a missing envelope.
 * Decoding it instead would turn an upstream error into a broken screen.
 */
private fun JsonElement.dataOrNull(): JsonElement? = ((this as? JsonObject)?.get("data")) as? JsonObject

/**
 * What a route-info call produced. A shape id the provider no longer knows
 * and a transport failure need different handling: only the first one is
 * evidence that the provider renumbered the direction, so only it may be
 * retried with another shape.
 */
private sealed interface RouteInfoResult {
    data class Loaded(
        val dto: OseThRouteInfoDto,
    ) : RouteInfoResult

    /** The provider took the request and answered without a data object. */
    data object RetiredShape : RouteInfoResult

    /** Transport or decode failure. Says nothing about the pair. */
    data object Failed : RouteInfoResult
}
