package app.astiko.data.oasa

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OASA telematics API, https://telematics.oasa.gr/api/
 * Unofficial. Quirks are handled in the adapter.
 *
 * Every action is `?act=ACTION&p1=...&p2=...`. Responses are JSON arrays,
 * except live endpoints which return the JSON string `""` (or `null`)
 * when nothing is running, hence the untyped JsonElement return.
 */
interface OasaApi {
    /** Nearest stops to a point. p1=lat, p2=lon. */
    @GET("?act=getClosestStops")
    suspend fun getClosestStops(
        @Query("p1") lat: Double,
        @Query("p2") lon: Double,
    ): JsonElement

    /** All routes/lines serving a stop. */
    @GET("?act=webRoutesForStop")
    suspend fun getRoutesForStop(
        @Query("p1") stopCode: String,
    ): JsonElement

    /** Live arrivals at a stop: [{route_code, veh_code, btime2(minutes)}]. */
    @GET("?act=getStopArrivals")
    suspend fun getStopArrivals(
        @Query("p1") stopCode: String,
    ): JsonElement

    /** One record per line number (191), with the master description. */
    @GET("?act=webGetMasterLines")
    suspend fun getMasterLines(): JsonElement

    /** Every line variant (476 entries: line_code + public number + descr). */
    @GET("?act=webGetLines")
    suspend fun getLines(): JsonElement

    /** The routes of a line variant. */
    @GET("?act=getRoutesForLine")
    suspend fun getRoutesForLine(
        @Query("p1") lineCode: String,
    ): JsonElement

    /** Ordered stops along a route. */
    @GET("?act=webGetStops")
    suspend fun getStopsForRoute(
        @Query("p1") routeCode: String,
    ): JsonElement

    /** Route polyline + stops: {"details": [points], "stops": [...]}. */
    @GET("?act=webGetRoutesDetailsAndStops")
    suspend fun getRouteDetailsAndStops(
        @Query("p1") routeCode: String,
    ): JsonElement

    /** Live GPS of every bus on a route: [{VEH_NO, CS_LAT, CS_LNG, VEH_HEADING}]. */
    @GET("?act=getBusLocation")
    suspend fun getBusLocation(
        @Query("p1") routeCode: String,
    ): JsonElement
}
