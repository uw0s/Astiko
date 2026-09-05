package app.astiko.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class Provider {
    OASA,
    OSETh,
    CITYBUS,
    CITYBUS_XANTHI,
    CITYBUS_IRAKLIO,
    CITYBUS_IOANNINA,
    CITYBUS_PATRA,
    CITYBUS_CHANIA,
    CITYBUS_VOLOS,
    CITYBUS_CORFU,
    CITYBUS_SALAMINA,
    CITYBUS_KAVALA,
    CITYBUS_CHALKIDA,
    CITYBUS_SERRES,
    CITYBUS_KATERINI,
    CITYBUS_MITILINI,
    CITYBUS_ALEXANDROUPOLI,
    CITYBUS_PTOLEMAIDA,
    CITYBUS_KOZANI,
    CITYBUS_LAMIA,
    CITYBUS_AGRINIO,
    CITYBUS_CHIOS,
    CITYBUS_KOMOTINI,
    CITYBUS_ARTA,
    CITYBUS_VEROIA,
    CITYBUS_MESOLOGGI,
}

@Serializable
data class Stop(
    val provider: Provider,
    val id: String, // provider-specific stable id
    val name: String,
    val street: String? = null,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double? = null,
    val servingLines: List<String> = emptyList(), // line short names, for badges
)

@Serializable
data class Line(
    val provider: Provider,
    val id: String, // provider-specific route id
    val shortName: String, // line number
    val longName: String, // line description
    val destination: String = "",
)

@Serializable
data class LineVariant(
    val provider: Provider,
    val lineId: String, // parent line id
    val id: String,
    val shapeId: String? = null, // OSETh direction selector
    val label: String,
    val lineShortName: String = "", // parent line number
) {
    /** Whether two variants denote the same direction, the favorite identity.
     *  OASA's line_code is not unique (938 covers 040/550/Α2), so lineId alone
     *  is unsafe. The route id (plus OSETh shapeId for weekday/weekend duplicates)
     *  is the discriminator. */
    fun isSameRoute(other: LineVariant): Boolean =
        provider == other.provider &&
            lineId == other.lineId &&
            id == other.id &&
            shapeId == other.shapeId
}

@Serializable
data class TimetableEntry(
    val lineShortName: String,
    val lineName: String,
    val destination: String,
    val departureTime: String,
    val tripId: String? = null, // provider-unique trip id, when available (stable list key)
)

data class Arrival(
    val routeCode: String,
    val lineShortName: String,
    val lineName: String,
    val destination: String,
    val etaMinutes: Int?, // null: only scheduled time exists
    val scheduledTime: String? = null,
    val vehicleId: String? = null,
    val tripId: String? = null,
    /** OSETh-only: the trip's shape discriminator. One direction is one
     *  routeId with several shapes (weekday vs ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ), and the
     *  route geometry is per shape. The tap-to-route line must draw the
     *  shape the bus actually runs, not the weekday twin. Null elsewhere. */
    val shapeId: String? = null,
    val vehicle: VehiclePosition? = null,
    val isScheduled: Boolean = false, // true: this row is a cached-schedule estimate
) {
    /**
     * Whether this arrival and [other] denote the same tracked bus (or
     * trip), across polls. The stable identity is [tripId] when present
     * (CityBus vehicleCode, OSETh trip id). OASA's tripId is always null,
     * so the bus number ([vehicleId]) is the discriminator there. Rows
     * without either id cannot be re-identified, so they answer false.
     * A stale card is worse than a false dismissal, and real payloads
     * always carry one of the two.
     */
    fun isSameVehicle(other: Arrival): Boolean =
        if (tripId != null && other.tripId != null) {
            tripId == other.tripId
        } else {
            !vehicleId.isNullOrBlank() &&
                !other.vehicleId.isNullOrBlank() &&
                vehicleId == other.vehicleId
        }
}

@Serializable
data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

@Serializable
data class VehiclePosition(
    val vehicleId: String,
    val lat: Double,
    val lon: Double,
    val heading: Float? = null,
) {
    companion object {
        /**
         * Position from the provider's live data. A missing coordinate or
         * (0, 0) means no GPS fix: the caller keeps the arrival or trip
         * and drops the position, so a bogus bus marker never appears at
         * (0, 0). Same convention for every provider (OASA and CityBus
         * report "0"/"0", OSETh the same for its vehicles and arrivals).
         */
        fun orNull(
            vehicleId: String,
            lat: Double?,
            lon: Double?,
            heading: Float? = null,
        ): VehiclePosition? {
            if (lat == null || lon == null || lat == 0.0 || lon == 0.0) return null
            return VehiclePosition(vehicleId = vehicleId, lat = lat, lon = lon, heading = heading)
        }
    }
}
