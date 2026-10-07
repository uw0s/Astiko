package app.astiko.ui

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun duplicatedClusterLabels_marksRepeatedNames() {
        val far = stop("2", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.003, anchor.lon) // separate row
        val other = stop("3", "ΜΟΝΑΣΤΗΡΑΚΙ", anchor.lat + 0.006, anchor.lon)
        val labels = duplicatedClusterLabels(listOf(listOf(anchor), listOf(far), listOf(other)))
        assertEquals(setOf("ΣΥΝΤΑΓΜΑ"), labels)
        assertTrue(hasDuplicatedLabel(listOf(anchor), labels))
        assertFalse(hasDuplicatedLabel(listOf(other), labels))
    }

    @Test
    fun duplicatedClusterLabels_ignoreCaseAndWhitespace() {
        val far = stop("2", "  συνταγμα ", anchor.lat + 0.003, anchor.lon)
        assertEquals(
            setOf("ΣΥΝΤΑΓΜΑ"),
            duplicatedClusterLabels(listOf(listOf(anchor), listOf(far))),
        )
    }

    @Test
    fun duplicatedClusterLabels_useTheJoinedRowHeadline() {
        // A merged row (different names, same corner) and a lone stop with
        // one of those names: the row headlines differ, so neither is
        // ambiguous. Only the collapsed row's label counts.
        val renamed = stop("2", "ΠΛ.ΣΥΝΤΑΓΜΑΤΟΣ", anchor.lat + 0.00027, anchor.lon)
        val far = stop("3", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.003, anchor.lon)
        val clusters = clusterStops(listOf(anchor, renamed, far))
        assertEquals(2, clusters.size)
        assertTrue(duplicatedClusterLabels(clusters).isEmpty())
    }

    // --- the code a row shows ---------------------------------------------

    @Test
    fun rowStopCode_onlyWhenTheLabelRepeats() {
        val far = stop("2", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.003, anchor.lon)
        val other = stop("3", "ΜΟΝΑΣΤΗΡΑΚΙ", anchor.lat + 0.006, anchor.lon)
        val clusters = listOf(listOf(anchor), listOf(far), listOf(other))
        val labels = duplicatedClusterLabels(clusters)
        assertEquals("1", rowStopCode(clusters[0], labels))
        assertEquals("2", rowStopCode(clusters[1], labels))
        assertEquals(null, rowStopCode(clusters[2], labels))
    }

    @Test
    fun rowStopCode_isNullForAMergedRow() {
        val near = stop("2", "ΣΥΝΤΑΓΜΑ", anchor.lat + 0.00027, anchor.lon)
        val cluster = clusterStops(listOf(anchor, near)).single()
        // A code query badges every row, but a merged row still has no
        // single code to show.
        assertEquals(null, rowStopCode(cluster, emptySet(), codeQuery = true))
    }

    @Test
    fun rowStopCode_coversACodeQuery() {
        assertEquals("1", rowStopCode(listOf(anchor), emptySet(), codeQuery = true))
        assertEquals(null, rowStopCode(listOf(anchor), emptySet()))
    }
}
