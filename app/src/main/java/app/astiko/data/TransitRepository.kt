package app.astiko.data

import app.astiko.data.model.Arrival
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.model.VehiclePosition
import app.astiko.util.rankStopSearch
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

/**
 * The normalization layer: one interface for every transport provider.
 * The UI talks to this and nothing else.
 */
interface TransitRepository {
    val provider: Provider

    suspend fun getStopsNear(
        lat: Double,
        lon: Double,
        limit: Int = 20,
    ): List<Stop>

    /**
     * The provider's full stop catalog, with serving-line badges. This is
     * the raw material of [searchStops]. Providers without a usable
     * name-search endpoint answer it from their own catalog fetch (CityBus:
     * one call. OSETH: 4 pages of 1000). The offline decorator caches it as
     * one disk entry with a TTL, so a stopped process does not re-fetch it.
     */
    suspend fun getStopCatalog(): List<Stop>

    /**
     * Stop search by name (accent/case-insensitive substring, ranked: name
     * prefix before name contains before stop-code prefix). The default is a
     * local ranked filter over [getStopCatalog]. Providers with no search
     * capability ([supportsStopSearch] = false) never get called. Blank
     * queries answer empty without touching the catalog.
     */
    suspend fun searchStops(
        query: String,
        limit: Int = 30,
    ): List<Stop> {
        if (query.isBlank()) return emptyList()
        return rankStopSearch(getStopCatalog(), query, limit)
    }

    suspend fun getStopRoutes(stopId: String): List<Line>

    /**
     * One row per LINE for the "Γραμμές σε αυτή τη στάση" list.
     * The raw stop-routes response reports every direction separately
     * (OASA: one route per direction. OSETh: `01_7429_1_3` / `_2_3`),
     * so the raw list shows the same line twice. The FULL list must
     * still be the one passed to [observeArrivals]. Arrivals are
     * enriched by matching the route code against it.
     */
    fun stopLinesForDisplay(lines: List<Line>): List<Line>

    suspend fun getLines(): List<Line>

    suspend fun getLineVariants(line: Line): List<LineVariant>

    suspend fun getVariantStops(variant: LineVariant): List<Stop>

    suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint>

    fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>>

    fun observeArrivals(
        stopId: String,
        lines: List<Line>,
    ): Flow<List<Arrival>>

    /**
     * The direction variant a live arrival's bus is traveling on, for
     * drawing its route on the arrivals map. Built from fields the
     * arrivals payload already carries: [Arrival.routeCode] is the
     * direction's route id in every adapter, [Arrival.lineShortName] the
     * line code, [Arrival.shapeId] the OSETh shape discriminator. The UI
     * never builds variants from provider DTOs itself. Adapters may
     * override when a provider needs more than the arrival's own fields
     * (the default covers OASA, OSETh and CityBus). Arrivals without a
     * route id (offline schedule estimates) answer null, as do rows whose
     * line number is unknown (CityBus needs it to call the points
     * endpoint).
     */
    fun variantFor(arrival: Arrival): LineVariant? {
        if (arrival.routeCode.isBlank() || arrival.lineShortName.isBlank()) return null
        return LineVariant(
            provider = provider,
            lineId = arrival.lineShortName,
            id = arrival.routeCode,
            shapeId = arrival.shapeId,
            lineShortName = arrival.lineShortName,
            label = arrival.destination,
        )
    }

    /**
     * Full-day scheduled timetable at a stop (every line serving it)
     * for one day of the week.
     */
    suspend fun getStopTimetable(
        stopId: String,
        day: DayOfWeek,
    ): List<TimetableEntry>

    suspend fun getLineTimetable(
        variant: LineVariant,
        day: DayOfWeek,
    ): List<TimetableEntry>

    val supportsStopTimetable: Boolean get() = false

    val supportsLineTimetable: Boolean get() = false

    /**
     * Whether [searchStops] is available for this provider. The UI hides
     * the stop-search entry point when false (OASA has no name-search
     * endpoint today. Its catalog would be empty anyway).
     */
    val supportsStopSearch: Boolean get() = false

    /**
     * Background warm-up of the stop catalog, called when the city opens
     * so the first search is served from the disk entry instead of
     * waiting for the fetch (the OSETH full list is 4 slow server pages).
     * The default is a no-op. The offline decorator implements it, and
     * failures are its problem to swallow (offline/outage just leaves the
     * entry unbuilt, the search screen reports it).
     */
    suspend fun warmStopCatalog() {}
}

/**
 * Live-data poll interval, shared by every provider adapter's poll loop,
 * the cached decorator's retry cadence, the ViewModel restart loops and
 * the arrivals screen's countdown ring. All of them drive the same
 * cadence and the same staleness estimate, so do not change it in one
 * place alone.
 */
const val POLL_INTERVAL_MS = 15_000L
