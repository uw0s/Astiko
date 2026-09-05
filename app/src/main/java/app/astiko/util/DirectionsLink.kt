package app.astiko.util

import app.astiko.data.model.GeoPoint

/**
 * Google Maps directions URL, opened with ACTION_VIEW. The maps app
 * handles it when installed, a browser otherwise.
 *
 * No SDK and no permission: the link carries only the destination, and
 * the maps app asks for the traveler's own location. Walking mode: the
 * user is going to the stop to catch the bus. Coordinates, not the stop
 * name, so the pin lands exactly.
 */
fun mapsDirectionsUrl(destination: GeoPoint): String =
    "https://www.google.com/maps/dir/?api=1&destination=${destination.lat},${destination.lon}&travelmode=walking"
