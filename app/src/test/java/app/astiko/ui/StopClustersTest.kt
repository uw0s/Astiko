package app.astiko.ui

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Test

class StopClustersTest {
    private fun stop(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
    ) = Stop(provider = Provider.OASA, id = id, name = name, lat = lat, lon = lon)

    // Syntagma as the anchor. ~0.0009° lat ≈ 100 m.
    private val anchor = stop("1", "ΣΥΝΤΑΓΜΑ", 37.9757, 23.7341)

    @Test
    fun sameNameClose_mergeIntoOneCluster() {
        val near = stop("2", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.00027, anchor.lon) // ~30 m
        val clusters = clusterStops(listOf(anchor, near))
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].size)
    }

    @Test
    fun sameNameFar_staySeparate() {
        // Same name but ~330 m apart, genuinely different boarding points.
        val far = stop("2", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.003, anchor.lon)
        val clusters = clusterStops(listOf(anchor, far))
        assertEquals(2, clusters.size)
    }

    @Test
    fun differentNameButVeryClose_merge() {
        // "ΣΥΝΤΑΓΜΑ" vs "ΠΛ.ΣΥΝΤΑΓΜΑΤΟΣ" within 60 m = same physical place.
        val renamed = stop("2", "ΠΛ.ΣΥΝΤΑΓΜΑΤΟΣ", anchor.lat + 0.00027, anchor.lon)
        val clusters = clusterStops(listOf(anchor, renamed))
        assertEquals(1, clusters.size)
    }

    @Test
    fun differentNameAndFar_staySeparate() {
        val other = stop("2", "ΜΟΝΑΣΤΗΡΑΚΙ", anchor.lat + 0.003, anchor.lon)
        val clusters = clusterStops(listOf(anchor, other))
        assertEquals(2, clusters.size)
    }

    @Test
    fun nameComparison_ignoresCaseAndWhitespace() {
        val messy = stop("2", "  συνταγμα ", anchor.lat + 0.00027, anchor.lon)
        val clusters = clusterStops(listOf(anchor, messy))
        assertEquals(1, clusters.size)
    }

    @Test
    fun threeStops_sameCorner_oneCluster() {
        val b = stop("2", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.00027, anchor.lon)
        val c = stop("3", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.00027, anchor.lon + 0.00027)
        val clusters = clusterStops(listOf(anchor, b, c))
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].size)
    }
}
