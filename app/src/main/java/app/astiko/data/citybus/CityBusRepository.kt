package app.astiko.data.citybus

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
import app.astiko.util.SingleFlightCache
import app.astiko.util.compareLineShortNames
import app.astiko.util.haversineKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.time.DayOfWeek

/**
 * Adapter for the shared citybus.gr platform, used by many cities with
 * the same API. Larissa is agency 102. All platform quirks (JWT auth,
 * 404-means-empty live data, string coordinates, code ids, no per-route
 * vehicles) are handled here. The agency id and token page are
 * constructor parameters, so another city is a new AppContainer entry,
 * not a new adapter.
 */
class CityBusRepository(
    private val api: CityBusApi,
    private val json: Json,
    private val agency: String,
    private val langProvider: () -> String,
    provider: Provider, // plain param, the override below shadows it
    // False for tenants that don't publish schedules. Agrinio's
    // trips/stop/.../day/... Answers 404 on every stop and day, while
    // live arrivals work fine. Hide the timetable entry point instead of
    // showing a misleading "no trips" empty state.
    private val supportsTimetables: Boolean = true,
) : TransitRepository {
    private val lang: String get() = langProvider()

    override val provider: Provider = provider

    // Static catalog data (510 stops / 70 routes / 21 lines), fetched once
    // per language per process, then joined for stops-near, stop routes
    // and route stops. Keyed by lang, a locale change mid-process must not
    // serve stale-named stops or lines. One cache per catalog (the three
    // lists share the lang key, so a shared map would serve stops as
    // lines). Failures are not cached, so a transient error retries on
    // the next access (see SingleFlightCache).
    private val stopsCatalog = SingleFlightCache()
    private val routesCatalog = SingleFlightCache()
    private val linesCatalog = SingleFlightCache()

    private suspend fun stops(): List<CityBusStopDto> =
        stopsCatalog.get(
            key = lang,
            cacheIt = { it.isNotEmpty() },
        ) {
            // Failures propagate, no empty-swallow, same rule as
            // getVariantStops. An outage must surface as the screens' error
            // state with retry, and the offline decorator falls back to a
            // stale cache, never to a silent empty city. The shared in-flight
            // entry is cleared on failure, so the next access retries.
            json.decodeFromJsonElement(
                ListSerializer(CityBusStopDto.serializer()),
                api.stops(lang = lang, agency = agency),
            )
        }

    private suspend fun routes(): List<CityBusRouteDto> =
        routesCatalog.get(
            key = lang,
            cacheIt = { it.isNotEmpty() },
        ) {
            json.decodeFromJsonElement(
                ListSerializer(CityBusRouteDto.serializer()),
                api.routes(lang = lang, agency = agency),
            )
        }

    private suspend fun lines(): List<CityBusLineDto> =
        linesCatalog.get(
            key = lang,
            cacheIt = { it.isNotEmpty() },
        ) {
            json.decodeFromJsonElement(
                ListSerializer(CityBusLineDto.serializer()),
                api.lines(lang = lang, agency = agency),
            )
        }

    /** The full mapped stop catalog. Shared by [getStopsNear] (which adds
     *  the query-relative distance) and [getStopCatalog] (the search raw
     *  material). Stops without coordinates are dropped: a row without a
     *  position can't open the arrivals map, and nearby ordering needs
     *  one too. */
    private suspend fun catalogStops(): List<Stop> =
        stops().mapNotNull { s ->
            val stopLat = s.latitude ?: return@mapNotNull null
            val stopLon = s.longitude ?: return@mapNotNull null
            Stop(
                provider = provider,
                id = s.code,
                name = s.name ?: s.code,
                lat = stopLat,
                lon = stopLon,
                servingLines = s.lineCodes.distinct(),
            )
        }

    /** No nearby endpoint on this platform. The full stop list is small
     * (510), so distances are computed client-side with haversine. */
    override suspend fun getStopsNear(
        lat: Double,
        lon: Double,
        limit: Int,
    ): List<Stop> =
        catalogStops()
            .map { it.copy(distanceKm = haversineKm(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.distanceKm }
            .take(limit)

    // No name-search endpoint on this platform either, but the full stop
    // catalog is already fetched and cached per language ([stops]). The
    // interface's default searchStops ranks it locally. No extra HTTP.
    override val supportsStopSearch: Boolean get() = true

    override suspend fun getStopCatalog(): List<Stop> = catalogStops()

    override suspend fun getStopRoutes(stopId: String): List<Line> {
        val stop = stops().firstOrNull { it.code == stopId } ?: return emptyList()
        val routesById = routes().associateBy { it.code }
        // The stop lists routeCodes (one per direction). Each route embeds
        // its parent line. Line.id is the route code, and the arrivals join
        // matches live arrivals by routeCode.
        return stop.routeCodes.orEmpty().mapNotNull { code ->
            val route = routesById[code] ?: return@mapNotNull null
            val line = route.lines.firstOrNull()
            Line(
                provider = provider,
                id = route.code,
                shortName = line?.code ?: route.code,
                longName = line?.name ?: route.name ?: route.code,
                destination = route.name ?: "",
            )
        }
    }

    /** One row per line. A stop is served by several routes of the same
     * line (01's 001/003/005 all call at stop 0116), each reporting the
     * same public number. Same rule as OASA. */
    override fun stopLinesForDisplay(lines: List<Line>): List<Line> =
        lines
            .distinctBy { it.shortName }
            .sortedWith(Comparator { a, b -> compareLineShortNames(a.shortName, b.shortName) })

    override suspend fun getLines(): List<Line> =
        lines()
            .mapNotNull { l ->
                // Lines without routes (12 Γαιόπολις, suspended) are dead
                // ends. The direction sheet would be empty.
                if (l.routes.isEmpty()) return@mapNotNull null
                Line(
                    provider = provider,
                    id = l.code,
                    shortName = l.code,
                    // Mesologgi's tenant returns null line names. Fall back
                    // to the first embedded route's name, then the code.
                    longName = l.name ?: l.routes.firstOrNull()?.name ?: l.code,
                )
            }.sortedWith(Comparator { a, b -> compareLineShortNames(a.shortName, b.shortName) })

    override suspend fun getLineVariants(line: Line): List<LineVariant> {
        // Stop-route lines carry route-code ids, lines-tab lines carry the
        // line code. The public number is the stable key in both cases.
        val entry = lines().firstOrNull { it.code == line.shortName } ?: return emptyList()
        return entry.routes
            .map { r ->
                LineVariant(
                    provider = provider,
                    lineId = entry.code, // the line code, for the points endpoint
                    id = r.code, // the route code (direction)
                    lineShortName = entry.code,
                    label = r.name ?: entry.name ?: entry.code,
                )
            }.distinctBy { it.id }
    }

    override suspend fun getVariantStops(variant: LineVariant): List<Stop> {
        // Network and decode failures propagate, no empty-swallow. The
        // decorator surfaces them as the screen's error state with retry.
        // Only the documented "404 = no service" conventions map to empty.
        val element = api.routeSequence(lang = lang, agency = agency, routeCode = variant.id)
        val sequence =
            json.decodeFromJsonElement(
                ListSerializer(CityBusSequenceEntryDto.serializer()),
                element,
            )
        // The sequence carries only stop codes. Join with the stop list.
        val stopsByCode = stops().associateBy { it.code }
        return sequence.sortedBy { it.sequence ?: Int.MAX_VALUE }.mapNotNull { e ->
            val s = stopsByCode[e.code] ?: return@mapNotNull null
            val stopLat = s.latitude ?: return@mapNotNull null
            val stopLon = s.longitude ?: return@mapNotNull null
            Stop(
                provider = provider,
                id = s.code,
                name = s.name ?: s.code,
                lat = stopLat,
                lon = stopLon,
            )
        }
    }

    override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> {
        // The endpoint returns the points of every route of the line. Keep
        // only this variant's route, points are lon/lat strings. Failures
        // propagate, same rule as getVariantStops.
        val element = api.linePoints(agency = agency, lineCode = variant.lineId)
        val routes =
            json.decodeFromJsonElement(
                ListSerializer(CityBusLinePointsDto.serializer()),
                element,
            )
        return routes
            .firstOrNull { it.routeCode == variant.id }
            ?.routePoints
            .orEmpty()
            .sortedBy { it.sequence ?: Int.MAX_VALUE }
            .mapNotNull { p ->
                val lat = p.latitude?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = p.longitude?.toDoubleOrNull() ?: return@mapNotNull null
                GeoPoint(lat, lon)
            }
    }

    /** The platform has no per-route vehicle endpoint, only per-stop live
     * data, so the line map shows the route and stops without moving
     * buses. Incoming buses do appear on the arrivals map, carried by
     * [observeArrivals] (stops/live returns their GPS positions). Emit
     * once and complete. A perpetual empty-list loop would spin a
     * coroutine forever with nothing to update, and the cached decorator
     * breaks cleanly when the flow completes. */
    override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> =
        flow {
            emit(emptyList())
        }

    override val supportsStopTimetable: Boolean get() = supportsTimetables

    // No per-line departures endpoint on this platform, but every trip of
    // a route starts at its first stop. The origin stop's day schedule
    // filtered by routeCode is the route's departure board (see
    // getLineTimetable).
    override val supportsLineTimetable: Boolean get() = supportsTimetables

    override suspend fun getStopTimetable(
        stopId: String,
        day: DayOfWeek,
    ): List<TimetableEntry> {
        // Day numbering quirk. The API counts 0=Sunday to 6=Saturday, while
        // java.time.DayOfWeek counts MONDAY=1 to SUNDAY=7. `value % 7` maps
        // between them. The API rejects anything outside 0..6 with HTTP 400,
        // which this mapping can never produce.
        return stopTrips(stopId, day)
            .mapNotNull { it.toTimetableEntry() }
            .sortedBy { it.departureTime }
    }

    override suspend fun getLineTimetable(
        variant: LineVariant,
        day: DayOfWeek,
    ): List<TimetableEntry> {
        // Derived, not native. Fetch the day schedule of the route's first
        // stop (its origin, every trip starts there) and keep only this
        // route's trips. Short-turn trips that start at an intermediate stop
        // are missed, and on loop routes the final "return to origin" visit
        // can show up as an extra departure.
        val origin = routeOrigin(variant.id) ?: return emptyList()
        val dayTrips = stopTrips(origin.code, day)
        val own =
            dayTrips
                .filter { it.routeCode == variant.id } // other lines/routes at the stop
                .mapNotNull { it.toTimetableEntry() }
                .sortedBy { it.departureTime }
        if (own.isNotEmpty()) return own
        // A route that does not run on the selected day (weekday vs the
        // "ΛΑΙΚΗ ΣΑΒΒΑΤΟΥ" market routes of Larissa 01) must not answer
        // "the line doesn't run". Fall back to the same-direction sibling
        // routes' trips at this origin. The direction field splits the route
        // list, so the reverse direction's terminating visits here are
        // excluded. Sibling routes that start elsewhere don't appear
        // in the origin's day schedule, which is a correct miss.
        val line = lines().firstOrNull { it.code == variant.lineShortName } ?: return emptyList()
        val route = line.routes.firstOrNull { it.code == variant.id } ?: return emptyList()
        val direction = route.direction ?: return emptyList()
        val siblingCodes =
            line.routes
                .filter { it.direction == direction && it.code != variant.id }
                .map { it.code }
                .toSet()
        if (siblingCodes.isEmpty()) return emptyList()
        return dayTrips
            .filter { it.routeCode in siblingCodes }
            .mapNotNull { it.toTimetableEntry() }
            .sortedBy { it.departureTime }
    }

    /**
     * The route's origin stop, the entry with the lowest sequence number
     * in the raw route sequence, resolved by code against the stop
     * catalog. [getVariantStops] filters its join (stops missing from the
     * catalog or without coordinates are dropped), so its first survivor
     * is not a reliable origin. A dropped first entry would silently
     * shift the derived line timetable to the second stop's schedule.
     * The raw sequence has no such filter. Only the stop code is needed
     * for the timetable call, so no lat/lon is required here.
     */
    private suspend fun routeOrigin(routeId: String): CityBusStopDto? {
        val element = api.routeSequence(lang = lang, agency = agency, routeCode = routeId)
        val sequence =
            json.decodeFromJsonElement(
                ListSerializer(CityBusSequenceEntryDto.serializer()),
                element,
            )
        val originCode = sequence.minByOrNull { it.sequence ?: Int.MAX_VALUE }?.code ?: return null
        return stops().firstOrNull { it.code == originCode }
    }

    /**
     * The stop's day schedule, with the API's "404 = no service that day"
     * mapped to an empty list, the same convention as stops/live. Xanthi
     * stop 172 returns 404 on Sunday when line 12 doesn't run, and 200
     * with trips on Monday and Saturday. Any other failure, HTTP or a
     * decode error, propagates to the timetable's error state. A swallowed
     * decode failure would be cached as a 24 h "no trips" board, pinning
     * a one-off API blip for a whole day.
     */
    private suspend fun stopTrips(
        stopCode: String,
        day: DayOfWeek,
    ): List<CityBusTripDto> {
        val element =
            try {
                api.stopTrips(
                    lang = lang,
                    agency = agency,
                    stopCode = stopCode,
                    day = day.value % 7,
                )
            } catch (e: HttpException) {
                if (e.code() == 404) return emptyList()
                throw e
            }
        return json.decodeFromJsonElement(ListSerializer(CityBusTripDto.serializer()), element)
    }

    private fun CityBusTripDto.toTimetableEntry(): TimetableEntry? {
        val time = tripTime ?: return null
        return TimetableEntry(
            lineShortName = lineCode ?: "",
            lineName = lineName ?: "",
            destination = routeName ?: "",
            departureTime = time.take(5), // "HH:mm"
            tripId = id?.toString(),
        )
    }

    override fun observeArrivals(
        stopId: String,
        lines: List<Line>,
    ): Flow<List<Arrival>> =
        flow {
            while (true) {
                val element =
                    try {
                        api.stopLive(lang = lang, agency = agency, stopCode = stopId)
                    } catch (e: HttpException) {
                        // HTTP 404 = no bus approaching this stop right now (night,
                        // off-service), the API's way of saying "empty" rather
                        // than an error. Any other failure propagates to the caller.
                        if (e.code() == 404) null else throw e
                    }
                val arrivals =
                    if (element == null) {
                        emptyList()
                    } else {
                        // Decode failures propagate (no empty-swallow). A
                        // changed API shape must surface as a poll failure
                        // (keep-last-data / offline fallback downstream),
                        // never as a false "Καμία άφιξη".
                        val dto = json.decodeFromJsonElement(CityBusLiveDto.serializer(), element)
                        val seenTripIds = mutableSetOf<String>()
                        dto.vehicles
                            .mapNotNull { v ->
                                val minutes = v.departureMins ?: return@mapNotNull null
                                val line = lines.firstOrNull { it.id == v.routeCode }
                                Arrival(
                                    routeCode = v.routeCode ?: "",
                                    lineShortName = v.lineCode ?: line?.shortName ?: "",
                                    lineName = v.lineName ?: line?.longName ?: "",
                                    destination = v.routeName ?: line?.destination ?: "",
                                    etaMinutes = minutes,
                                    // vehicleCode identifies the trip, not
                                    // the bus, so it works for both the
                                    // list key and the map match.
                                    tripId = v.vehicleCode,
                                    vehicleId = v.vehicleCode,
                                    vehicle =
                                        VehiclePosition.orNull(
                                            vehicleId = v.vehicleCode ?: "",
                                            lat = v.latitude?.toDoubleOrNull(),
                                            lon = v.longitude?.toDoubleOrNull(),
                                        ),
                                )
                            }
                            // Loop routes (Xanthi 02-12, Serres 002/004/023...)
                            // visit a stop twice and can report both passes of
                            // one trip in one response. The list keys rows by
                            // tripId, so a duplicate would crash the LazyColumn.
                            // Keep only the next pass (earliest ETA, the list is
                            // ETA-sorted). Rows without a tripId are untouched.
                            .sortedBy { it.etaMinutes }
                            .filter { a -> a.tripId == null || seenTripIds.add(a.tripId) }
                    }
                emit(arrivals)
                delay(POLL_INTERVAL_MS)
            }
        }

    companion object {
    }
}
