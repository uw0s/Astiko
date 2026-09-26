package app.astiko.ui.map

import org.maplibre.geojson.FeatureCollection

/**
 * Empty collection for clearing a GeoJSON source. Clearing map state means
 * writing an empty collection to every source that fed it, not cancelling
 * the poll behind it (a rendered line or pin outlives its fetch).
 */
internal fun emptyFeatures(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())
