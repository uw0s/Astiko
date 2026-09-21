package app.astiko.util

import app.astiko.data.model.Stop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun haversineKm(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial bearing from point 1 to point 2, in compass degrees (0 = north, clockwise). */
fun bearingDegrees(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Two same-name stops are "across the road" when close and the line
 * between them crosses the street (roughly opposite bearings).
 */
fun isAcrossRoad(
    a: Stop,
    b: Stop,
    maxDistanceM: Double = 60.0,
): Boolean {
    val distanceM = haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000.0
    if (distanceM > maxDistanceM) return false
    val b1 = bearingDegrees(a.lat, a.lon, b.lat, b.lon)
    val b2 = bearingDegrees(b.lat, b.lon, a.lat, a.lon)
    val diff = ((b1 - b2) % 360.0 + 360.0) % 360.0
    return diff in 140.0..220.0
}

/** Compass degrees to Greek cardinal (8 sectors). */
fun cardinalGreek(degrees: Double): String {
    val sectors = listOf("Β", "ΒΑ", "Α", "ΝΑ", "Ν", "ΝΔ", "Δ", "ΒΔ")
    val index = ((degrees + 22.5) / 45.0).toInt() % 8
    return sectors[index]
}

/**
 * The same stops with their distance from a fix, in km. Search results
 * come from the catalog without one, and same-name stops need it to be
 * told apart. Nearby results already carry it.
 */
fun List<Stop>.withDistanceFrom(
    lat: Double,
    lon: Double,
): List<Stop> = map { it.copy(distanceKm = haversineKm(lat, lon, it.lat, it.lon)) }

/**
 * The consecutive stops the bus runs between, as indices into the
 * route-ordered stop list. The leg closest to the bus wins. A bus before
 * the first stop or past the last clamps to the end leg. Null when fewer
 * than two stops are known.
 */
fun busBetweenStops(
    stops: List<Stop>,
    lat: Double,
    lon: Double,
): Pair<Int, Int>? {
    if (stops.size < 2) return null
    val kmPerDegLat = 111.32
    val kmPerDegLon = 111.32 * cos(Math.toRadians(lat))

    fun x(s: Stop) = (s.lon - lon) * kmPerDegLon

    fun y(s: Stop) = (s.lat - lat) * kmPerDegLat
    var best: Pair<Int, Int>? = null
    var bestDistSq = Double.MAX_VALUE
    for (i in 0 until stops.size - 1) {
        val a = stops[i]
        val b = stops[i + 1]
        val ax = x(a)
        val ay = y(a)
        val dx = x(b) - ax
        val dy = y(b) - ay
        val lenSq = dx * dx + dy * dy
        // The bus is the plane origin. The clamp keeps a bus past the
        // stop on the leg it is traveling.
        val t = if (lenSq > 0.0) (-(ax * dx + ay * dy)) / lenSq else 0.0
        val tc = t.coerceIn(0.0, 1.0)
        val px = ax + tc * dx
        val py = ay + tc * dy
        val distSq = px * px + py * py
        if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = i to i + 1
        }
    }
    return best
}
