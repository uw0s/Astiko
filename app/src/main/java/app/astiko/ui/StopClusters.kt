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
        val name = normalizeStopName(stop.name)
        val existing =
            clusters.firstOrNull { cluster ->
                val ref = cluster.first()
                val distanceM = haversineKm(ref.lat, ref.lon, stop.lat, stop.lon) * 1000.0
                (normalizeStopName(ref.name) == name && distanceM < SAME_NAME_RADIUS_M) ||
                    distanceM < ANY_NAME_RADIUS_M
            }
        if (existing != null) existing.add(stop) else clusters.add(mutableListOf(stop))
    }
    return clusters
}

/** The headline a row shows for [cluster]: the distinct names joined. */
internal fun clusterLabel(cluster: List<Stop>): String = cluster.map { it.name }.distinct().joinToString(" / ")

/**
 * Labels shown by more than one row of [clusters]. Keys are normalized
 * like the clustering names: trim, uppercase, single spaces.
 */
fun duplicatedClusterLabels(clusters: List<List<Stop>>): Set<String> =
    clusters
        .groupingBy { normalizeStopName(clusterLabel(it)) }
        .eachCount()
        .filterValues { it > 1 }
        .keys

/** Whether another row of the same list shows this label. */
internal fun hasDuplicatedLabel(
    cluster: List<Stop>,
    duplicatedLabels: Set<String>,
): Boolean = normalizeStopName(clusterLabel(cluster)) in duplicatedLabels

/**
 * The code [cluster]'s row shows, or null when it shows none. A row of
 * several stops has no single code, the chooser sheet lists them all. A
 * code query badges every row.
 */
internal fun rowStopCode(
    cluster: List<Stop>,
    duplicatedLabels: Set<String>,
    codeQuery: Boolean = false,
): String? {
    val stop = cluster.singleOrNull() ?: return null
    return stop.id.takeIf { codeQuery || hasDuplicatedLabel(cluster, duplicatedLabels) }
}

private fun normalizeStopName(name: String) = name.trim().uppercase().replace(Regex("\\s+"), " ")
