package app.astiko.data.oseth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Raw JSON shapes of the OSETh API (camelCase, inside the {"data": ...} envelope).

@Serializable
data class OseThStopsDataDto(
    val stops: List<OseThStopDto> = emptyList(),
    val total: Int? = null, // full catalog size; drives the page walk
)

@Serializable
data class OseThStopDto(
    val id: String? = null,
    val code: String? = null,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val distance: Double? = null, // meters
    val routes: List<OseThRouteDto>? = null,
)

@Serializable
data class OseThRouteDto(
    val id: String? = null,
    val shortName: String? = null,
    val longName: String? = null,
    val tripHeadsigns: List<OseThHeadsignDto>? = null,
)

@Serializable
data class OseThHeadsignDto(
    @SerialName("routeId") val routeId: String? = null,
    val headsign: String? = null,
    @SerialName("shapeId") val shapeId: String? = null,
)

@Serializable
data class OseThRoutesDataDto(
    val routes: List<OseThRouteDto> = emptyList(),
)

@Serializable
data class OseThRouteInfoDto(
    val shape: OseThShapeDto? = null,
    val stops: List<OseThRouteStopDto> = emptyList(),
    val vehicles: List<OseThVehicleDto> = emptyList(),
)

@Serializable
data class OseThShapeDto(
    @SerialName("lineString") val lineString: String? = null, // WKT: LINESTRING (lon lat, ...)
)

@Serializable
data class OseThVehicleDto(
    val id: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val bearing: Double? = null,
)

@Serializable
data class OseThRouteStopDto(
    val id: String? = null,
    val code: String? = null,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sequence: Int? = null,
)

@Serializable
data class OseThTimetableDto(
    val trips: List<OseThTripDto>? = null,
)

/** Route timetable envelope: route/{routeId}/timetable (trips reuse OseThTripDto). */
@Serializable
data class OseThRouteTimetableDto(
    @SerialName("shortName") val shortName: String? = null,
    @SerialName("longName") val longName: String? = null,
    val headsign: String? = null,
    val trips: List<OseThTripDto> = emptyList(),
)

@Serializable
data class OseThTripDto(
    val id: String? = null,
    val headsign: String? = null,
    @SerialName("shapeId") val shapeId: String? = null,
    val route: OseThRouteDto? = null,
    @SerialName("arrivalTime") val arrivalTime: String? = null,
    @SerialName("departureTime") val departureTime: String? = null,
    val monitored: Boolean? = null,
    @SerialName("arrivalInMinutes") val arrivalInMinutes: Int? = null,
    @SerialName("departureInMinutes") val departureInMinutes: Int? = null,
    val vehicle: OseThVehicleDto? = null,
)
