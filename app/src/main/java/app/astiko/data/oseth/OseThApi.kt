package app.astiko.data.oseth

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * OSETh telematics API, https://oseth.com.gr/{lang}/telematics-api
 * Unofficial. Quirks are handled in the adapter.
 *
 * Every endpoint returns the envelope {"data": ..., "error": "", "status_code": 200}
 * (or HTTP 400 + {"error": ...}). The language prefix follows the app
 * language ("el" | "en"). Pass the same value to the `language` query
 * params, which the API uses for names/headsigns.
 */
interface OseThApi {
    /** Nearest stops to a point, sorted by distance (meters). */
    @GET("{lang}/telematics-api/stop/nearby")
    suspend fun nearbyStops(
        @Path("lang") lang: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("longitude") lon: Double,
        @Query("latitude") lat: Double,
        @Query("language") language: String,
    ): JsonElement

    /**
     * Stops (paginated). The server caps [size] at 1000, so the full
     * catalog (3,698 stops) is 4 pages. The `stopName` query param is
     * ignored by the server (with a name it still returns the first page
     * of the full list), so stop search fetches the whole catalog once
     * and filters locally, exactly what the web app's autocomplete does.
     */
    @GET("{lang}/telematics-api/stop")
    suspend fun stops(
        @Path("lang") lang: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 1000,
        @Query("language") language: String,
    ): JsonElement

    /** Stop details + routes serving it. */
    @GET("{lang}/telematics-api/stop/{stopId}/info")
    suspend fun stopInfo(
        @Path("lang") lang: String,
        @Path("stopId") stopId: String,
        @Query("language") language: String,
    ): JsonElement

    /** Live arrivals at a stop. Date is required (dd/MM/yyyy HH:mm:ss). */
    @GET("{lang}/telematics-api/stop/{stopId}/timetable")
    suspend fun stopTimetable(
        @Path("lang") lang: String,
        @Path("stopId") stopId: String,
        @Query("language") language: String,
        @Query("date") date: String,
    ): JsonElement

    /** All routes (226) with their direction headsigns. */
    @GET("{lang}/telematics-api/route")
    suspend fun routes(
        @Path("lang") lang: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 1000,
        @Query("language") language: String,
    ): JsonElement

    /** One direction: geometry, ordered stops, live vehicles. */
    @GET("{lang}/telematics-api/route/{routeId}/info")
    suspend fun routeInfo(
        @Path("lang") lang: String,
        @Path("routeId") routeId: String,
        @Query("shapeId") shapeId: String,
        @Query("language") language: String,
    ): JsonElement

    /**
     * Full-day scheduled departures of one route direction. The date picks
     * the service day. A weekday returns that day's trips, a weekend
     * returns none for lines that don't run then.
     */
    @GET("{lang}/telematics-api/route/{routeId}/timetable")
    suspend fun routeTimetable(
        @Path("lang") lang: String,
        @Path("routeId") routeId: String,
        @Query("date") date: String,
        @Query("shapeId") shapeId: String,
        @Query("language") language: String,
    ): JsonElement
}
