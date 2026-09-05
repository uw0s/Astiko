package app.astiko.ui

import app.astiko.data.model.Stop
import app.astiko.util.haversineKm

/**
 * Groups nearby stops into "one place" rows.
 *
 * Two-tier rule:
 * - same name and within 150 m: twins (across the road, same corner)
 * - any name within 60 m: the same physical place even if the data
 *   calls it differently ("ΣΥΝΤΑΓΜΑ" vs "ΠΛ.ΣΥΝΤΑΓΜΑΤΟΣ")
 *
 * Same-name stops further apart (long corridors) stay separate rows.
 * They are genuinely different boarding points.
 */
private const val SAME_NAME_RADIUS_M = 150.0
private const val ANY_NAME_RADIUS_M = 60.0

fun clusterStops(stops: List<Stop>): List<List<Stop>> {
    val clusters = mutableListOf<MutableList<Stop>>()
    for (stop in stops.sortedBy { it.distanceKm ?: Double.MAX_VALUE }) {
        val name = normalize(stop.name)
        val existing =
            clusters.firstOrNull { cluster ->
                val ref = cluster.first()
                val distanceM = haversineKm(ref.lat, ref.lon, stop.lat, stop.lon) * 1000.0
                (normalize(ref.name) == name && distanceM < SAME_NAME_RADIUS_M) ||
                    distanceM < ANY_NAME_RADIUS_M
            }
        if (existing != null) existing.add(stop) else clusters.add(mutableListOf(stop))
    }
    return clusters
}

private fun normalize(name: String) = name.trim().uppercase().replace(Regex("\\s+"), " ")
