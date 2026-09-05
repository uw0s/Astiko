package app.astiko.ui

import app.astiko.data.model.Stop

/*
 * Origin/terminus logic for the line map's endpoint markers and the
 * stop list's numbered badges.
 *
 * The stops of a variant arrive in route order, so the first is the
 * origin and the last is the terminus, except on loop routes, where
 * the first stop is also the last (one marker, not two overlapping).
 * Ids, not references, are the identity: a route can visit a stop
 * twice, and the markers/selection must agree with the drawn dots.
 */

/** Origin marker color (ARGB). Map dots and list badges share it. */
const val ROUTE_START_ARGB = 0xFF2E7D32.toInt()

/** Terminus marker color (ARGB). Map dots and list badges share it. */
const val ROUTE_END_ARGB = 0xFF263238.toInt()

/** The role a stop plays at a route's ends. Drives marker colors. */
enum class RouteEndRole(
    val geoJson: String,
) {
    START("start"),

    END("end"),

    STOP("stop"),
}

/**
 * The origin and terminus of an ordered stop list.
 *
 * @return `(start, end)`. `end` is null when the list is empty, has a
 *   single stop, or loops (first stop == last stop).
 */
fun routeEndpoints(stops: List<Stop>): Pair<Stop?, Stop?> {
    val first = stops.firstOrNull() ?: return null to null
    val last = stops.lastOrNull()
    return first to last?.takeIf { it.id != first.id }
}

/**
 * Which route-end role `stopId` plays in the ordered stop list.
 * Loop routes resolve to START (the first branch wins), so the
 * selection highlight stays green on the shared origin/terminus stop.
 */
fun routeEndRole(
    stops: List<Stop>,
    stopId: String,
): RouteEndRole =
    when (stopId) {
        stops.firstOrNull()?.id -> RouteEndRole.START
        stops.lastOrNull()?.id -> RouteEndRole.END
        else -> RouteEndRole.STOP
    }

/**
 * The marker color for a role, or null for plain stops (the badge then
 * keeps the theme's neutral chip color). Same values the map dots use.
 */
fun RouteEndRole.badgeArgb(): Int? =
    when (this) {
        RouteEndRole.START -> ROUTE_START_ARGB
        RouteEndRole.END -> ROUTE_END_ARGB
        RouteEndRole.STOP -> null
    }

/**
 * Which list index the map-pin selection scrolls to.
 *
 * A route may visit a stop twice (loops): the first occurrence wins, so
 * the list lands on the outbound visit, not the return leg. Returns -1
 * when the stop is not on the route (a stale selection after a reload).
 */
fun scrollTargetIndex(
    stops: List<Stop>,
    stopId: String,
): Int = stops.indexOfFirst { it.id == stopId }
