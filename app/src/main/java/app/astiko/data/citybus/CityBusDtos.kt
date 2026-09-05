package app.astiko.data.citybus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Raw JSON shapes of the CityBus platform API (camelCase, plain arrays/objects, no envelope).

@Serializable
data class CityBusStopDto(
    val code: String = "", // stable zero-padded key used in path segments
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val lineCodes: List<String> = emptyList(),
    val routeCodes: List<String>? = null,
)

@Serializable
data class CityBusLineDto(
    val code: String = "",
    val name: String? = null,
    val routes: List<CityBusRouteRefDto> = emptyList(),
)

/** A route as embedded in a line: /lines -> routes[]. */
@Serializable
data class CityBusRouteRefDto(
    val code: String = "",
    val name: String? = null, // direction label ("ΝΕΑ ΣΜΥΡΝΗ - ΝΕΑΠΟΛΗ")
    val direction: Int? = null, // 1/2
)

@Serializable
data class CityBusRouteDto(
    val code: String = "",
    val name: String? = null,
    val lines: List<CityBusLineRefDto> = emptyList(),
)

/** A line as embedded in a route: /routes -> lines[]. */
@Serializable
data class CityBusLineRefDto(
    val code: String = "",
    val name: String? = null,
)

@Serializable
data class CityBusSequenceEntryDto(
    val sequence: Int? = null,
    val code: String = "",
)

@Serializable
data class CityBusLinePointsDto(
    @SerialName("routeCode") val routeCode: String = "",
    val routePoints: List<CityBusPointDto> = emptyList(),
)

@Serializable
data class CityBusPointDto(
    val sequence: Int? = null,
    val longitude: String? = null, // strings, not doubles
    val latitude: String? = null,
)

/** One scheduled trip of the stop timetable: trips/stop/{code}/day/{day}. */
@Serializable
data class CityBusTripDto(
    val id: Long? = null,
    @SerialName("lineCode") val lineCode: String? = null,
    @SerialName("lineName") val lineName: String? = null,
    @SerialName("routeCode") val routeCode: String? = null,
    @SerialName("routeName") val routeName: String? = null,
    @SerialName("tripTime") val tripTime: String? = null, // "HH:mm"
)

@Serializable
data class CityBusLiveDto(
    val vehicles: List<CityBusLiveVehicleDto> = emptyList(),
)

@Serializable
data class CityBusLiveVehicleDto(
    // YYYYMMDD_{route}_{seq}_{HH:MM}, identifies the TRIP not the bus.
    @SerialName("vehicleCode") val vehicleCode: String? = null,
    @SerialName("lineCode") val lineCode: String? = null,
    @SerialName("lineName") val lineName: String? = null,
    @SerialName("routeCode") val routeCode: String? = null,
    @SerialName("routeName") val routeName: String? = null,
    @SerialName("departureMins") val departureMins: Int? = null,
    val latitude: String? = null, // "0" = no GPS fix
    val longitude: String? = null,
)
