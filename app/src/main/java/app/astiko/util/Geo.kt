package app.astiko.util

import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Stop
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
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
): Pair<Int, Int>? =
    nearestSegmentIndex(
        pointCount = stops.size,
        lat = lat,
        lon = lon,
        coordLon = { stops[it].lon },
        coordLat = { stops[it].lat },
    )?.let { it to it + 1 }

// A route can travel the same street twice. Passes closer than this to the
// closest one compete for the bus, and the heading picks the winner.
private const val OPPOSITE_PASS_TOLERANCE_KM = 0.025

// Without a heading, two passes this close together cannot be told apart.
private const val RETRACE_TIE_KM = 0.005

/**
 * The route split at the bus's projection, as the part behind it and the
 * part ahead. A route that runs out and back over the same street projects
 * the bus onto both passes: the heading picks the pass it is driving, and
 * a missing heading leaves the route unsplit rather than guessing.
 */
fun splitAtNearest(
    points: List<GeoPoint>,
    lat: Double,
    lon: Double,
    heading: Float? = null,
): Pair<List<GeoPoint>, List<GeoPoint>>? {
    if (points.size < 2) return null
    val kmPerDegLat = 111.32
    val kmPerDegLon = 111.32 * cos(Math.toRadians(lat))
    val projections =
        (0 until points.size - 1).map { i ->
            val (t, distSq) =
                closestOnSegment(
                    ax = (points[i].lon - lon) * kmPerDegLon,
                    ay = (points[i].lat - lat) * kmPerDegLat,
                    bx = (points[i + 1].lon - lon) * kmPerDegLon,
                    by = (points[i + 1].lat - lat) * kmPerDegLat,
                )
            RouteProjection(
                index = i,
                t = t,
                distanceKm = sqrt(distSq),
                bearing =
                    bearingDegrees(
                        points[i].lat,
                        points[i].lon,
                        points[i + 1].lat,
                        points[i + 1].lon,
                    ),
            )
        }
    val nearest = projections.minByOrNull { it.distanceKm } ?: return null
    val chosen =
        if (heading == null) {
            val retraced =
                projections.any {
                    it.index != nearest.index &&
                        abs(it.distanceKm - nearest.distanceKm) < RETRACE_TIE_KM
                }
            if (retraced) return null
            nearest
        } else {
            projections
                .filter { it.distanceKm <= nearest.distanceKm + OPPOSITE_PASS_TOLERANCE_KM }
                .minBy { headingDifference(it.bearing, heading.toDouble()) }
        }
    val point =
        GeoPoint(
            lat =
                points[chosen.index].lat +
                    (points[chosen.index + 1].lat - points[chosen.index].lat) * chosen.t,
            lon =
                points[chosen.index].lon +
                    (points[chosen.index + 1].lon - points[chosen.index].lon) * chosen.t,
        )
    val before = points.take(chosen.index + 1)
    val after = points.drop(chosen.index + 1)
    val traveled = if (chosen.t > 0.0) before + point else before
    val remaining = if (chosen.t < 1.0) listOf(point) + after else after
    return traveled to remaining
}

/** A polyline segment under a fix, with the projection's fraction along it. */
private data class RouteProjection(
    val index: Int,
    val t: Double,
    val distanceKm: Double,
    val bearing: Double,
)

/**
 * The nearest segment of a polyline to a fix, as the index of its first
 * point. The projection is local and flat, good enough at street scale.
 */
private fun nearestSegmentIndex(
    pointCount: Int,
    lat: Double,
    lon: Double,
    coordLon: (Int) -> Double,
    coordLat: (Int) -> Double,
): Int? {
    if (pointCount < 2) return null
    val kmPerDegLat = 111.32
    val kmPerDegLon = 111.32 * cos(Math.toRadians(lat))
    var best = 0
    var bestDistSq = Double.MAX_VALUE
    for (i in 0 until pointCount - 1) {
        val (_, distSq) =
            closestOnSegment(
                ax = (coordLon(i) - lon) * kmPerDegLon,
                ay = (coordLat(i) - lat) * kmPerDegLat,
                bx = (coordLon(i + 1) - lon) * kmPerDegLon,
                by = (coordLat(i + 1) - lat) * kmPerDegLat,
            )
        if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = i
        }
    }
    return best
}

/**
 * The closest point on a segment to the fix at the plane origin, as the
 * clamped fraction along the segment and the squared distance.
 */
private fun closestOnSegment(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
): Pair<Double, Double> {
    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    val t = if (lenSq > 0.0) (-(ax * dx + ay * dy)) / lenSq else 0.0
    val clamped = t.coerceIn(0.0, 1.0)
    val px = ax + clamped * dx
    val py = ay + clamped * dy
    return clamped to (px * px + py * py)
}

/** Smallest angle between two compass bearings, 0..180 degrees. */
private fun headingDifference(
    a: Double,
    b: Double,
): Double {
    val diff = abs(a - b) % 360.0
    return min(diff, 360.0 - diff)
}
