package app.astiko.data.citybus

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * CityBus platform API, https://rest.citybus.gr/api/v1
 * Unofficial. Larissa is agency 102.
 *
 * Auth: every request needs `Authorization: Bearer <JWT>`, added by
 * [CityBusAuthInterceptor] (the token is scraped from the city app page).
 * The `{lang}` path segment follows the app language ("el" | "en",
 * localized stop/line/route names).
 *
 * Quirks:
 * - `stops/live/{code}` returns HTTP 404 when no bus is approaching that
 *   stop right now, not an error. The adapter maps 404 to empty arrivals.
 * - Live vehicle coordinates are strings. `"0"/"0"` = no GPS fix.
 * - Path segments need the zero-padded `code` (numeric `id`s 404).
 */
interface CityBusApi {
    /** All stops of the agency (Larissa: 510), one call, no pagination. */
    @GET("api/v1/{lang}/{agency}/stops")
    suspend fun stops(
        @Path("lang") lang: String,
        @Path("agency") agency: String,
    ): JsonElement

    /** All lines with their routes embedded (21). */
    @GET("api/v1/{lang}/{agency}/lines")
    suspend fun lines(
        @Path("lang") lang: String,
        @Path("agency") agency: String,
    ): JsonElement

    /** Route geometry of every route of one line ({lang} segment optional). */
    @GET("api/v1/{agency}/lines/{lineCode}/points")
    suspend fun linePoints(
        @Path("agency") agency: String,
        @Path("lineCode") lineCode: String,
    ): JsonElement

    /** All routes with their parent line embedded (70). */
    @GET("api/v1/{lang}/{agency}/routes")
    suspend fun routes(
        @Path("lang") lang: String,
        @Path("agency") agency: String,
    ): JsonElement

    /** Ordered stop codes along one route. */
    @GET("api/v1/{lang}/{agency}/routes/{routeCode}/sequence")
    suspend fun routeSequence(
        @Path("lang") lang: String,
        @Path("agency") agency: String,
        @Path("routeCode") routeCode: String,
    ): JsonElement

    /** Live arrivals at a stop (with bus GPS). HTTP 404 = no bus
     *  approaching right now. */
    @GET("api/v1/{lang}/{agency}/stops/live/{stopCode}")
    suspend fun stopLive(
        @Path("lang") lang: String,
        @Path("agency") agency: String,
        @Path("stopCode") stopCode: String,
    ): JsonElement

    /**
     * Full-day scheduled timetable at a stop, across all lines
     * (day 0=Sunday to 6=Saturday, HTTP 400 outside that range).
     * Schedule only, no real-time delay fields.
     */
    @GET("api/v1/{lang}/{agency}/trips/stop/{stopCode}/day/{day}")
    suspend fun stopTrips(
        @Path("lang") lang: String,
        @Path("agency") agency: String,
        @Path("stopCode") stopCode: String,
        @Path("day") day: Int,
    ): JsonElement
}
