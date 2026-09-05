package app.astiko.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LineVariant.isSameRoute`, the direction-switcher / favorites identity.
 * OASA's line_code is not unique (938 covers 040/550/Α2) and OSETh reuses
 * route ids across weekday/weekend shapes, so the discriminator is the
 * full (provider, lineId, id, shapeId) tuple.
 */
class LineVariantTest {
    private fun variant(
        provider: Provider = Provider.OSETh,
        lineId: String = "01",
        id: String = "r1",
        shapeId: String? = "s1",
    ) = LineVariant(provider, lineId, id, shapeId, "ΛΕΙΤΟΥΡΓΙΑ - ΤΕΡΜΑ", "01")

    @Test
    fun identicalVariants_sameRoute() {
        assertTrue(variant().isSameRoute(variant()))
    }

    @Test
    fun differentShapeId_weekdayVsWeekend_isDifferentRoute() {
        // OSETh: same route id "r1", different shape, different stop lists.
        assertFalse(variant(shapeId = "s1").isSameRoute(variant(shapeId = "s2")))
    }

    @Test
    fun nullVsNonNullShapeId_isDifferentRoute() {
        assertFalse(variant(shapeId = null).isSameRoute(variant(shapeId = "s1")))
    }

    @Test
    fun differentId_isDifferentRoute() {
        // OASA merged line_codes: the public number is the same but the
        // internal code differs (040/550/Α2 under 938).
        assertFalse(variant(id = "r1").isSameRoute(variant(id = "r2")))
    }

    @Test
    fun differentLineId_isDifferentRoute() {
        assertFalse(variant(lineId = "01").isSameRoute(variant(lineId = "02")))
    }

    @Test
    fun differentProvider_isDifferentRoute() {
        assertFalse(
            variant(provider = Provider.OSETh).isSameRoute(variant(provider = Provider.OASA)),
        )
    }
}
