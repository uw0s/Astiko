package app.astiko.data.oasa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Raw JSON shapes of the OASA API (field names as they come off the wire).

@Serializable
data class OasaStopDto(
    @SerialName("StopCode") val stopCode: String = "",
    @SerialName("StopDescr") val stopDescr: String? = null,
    @SerialName("StopDescrEng") val stopDescrEng: String? = null,
    @SerialName("StopStreet") val stopStreet: String? = null,
    @SerialName("StopStreetEng") val stopStreetEng: String? = null,
    @SerialName("StopLat") val stopLat: String? = null,
    @SerialName("StopLng") val stopLng: String? = null,
)

@Serializable
data class OasaRouteDto(
    @SerialName("RouteCode") val routeCode: String = "",
    @SerialName("LineID") val lineId: String? = null,
    @SerialName("LineDescr") val lineDescr: String? = null,
    @SerialName("LineDescrEng") val lineDescrEng: String? = null,
    @SerialName("RouteDescr") val routeDescr: String? = null,
    @SerialName("RouteDescrEng") val routeDescrEng: String? = null,
    @SerialName("hidden") val hidden: String? = null,
)

@Serializable
data class OasaArrivalDto(
    @SerialName("route_code") val routeCode: String = "",
    @SerialName("veh_code") val vehicleCode: String? = null,
    @SerialName("btime2") val minutes: String? = null,
)

@Serializable
data class OasaMasterLineDto(
    @SerialName("ml_descr") val mlDescr: String? = null,
    @SerialName("ml_descr_eng") val mlDescrEng: String? = null,
    @SerialName("ml_id") val mlId: String? = null,
    @SerialName("line_code") val lineCode: String? = null,
)

@Serializable
data class OasaLineDto(
    @SerialName("LineCode") val lineCode: String = "",
    @SerialName("LineID") val lineId: String? = null,
    @SerialName("LineDescr") val lineDescr: String? = null,
)

@Serializable
data class OasaRouteForLineDto(
    @SerialName("route_code") val routeCode: String = "",
    @SerialName("route_active") val routeActive: String? = null,
    @SerialName("route_descr") val routeDescr: String? = null,
    @SerialName("route_descr_eng") val routeDescrEng: String? = null,
)

@Serializable
data class OasaRoutePointDto(
    @SerialName("routed_x") val x: String? = null, // lon
    @SerialName("routed_y") val y: String? = null, // lat
)

@Serializable
data class OasaVehicleDto(
    @SerialName("VEH_NO") val vehicleNo: String? = null,
    @SerialName("CS_LAT") val lat: String? = null,
    @SerialName("CS_LNG") val lng: String? = null,
    @SerialName("VEH_HEADING") val heading: String? = null,
)
