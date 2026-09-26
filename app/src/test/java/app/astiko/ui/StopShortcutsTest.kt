package app.astiko.ui

import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload, tested through the pure Map seam. The Bundle half is a copy
 * on purpose, the mockable android.jar does not round-trip a Bundle. A
 * payload change that breaks these breaks icons pinned months ago.
 */
class StopShortcutsTest {
    private val stop =
        Stop(
            provider = Provider.OSETh,
            id = "1234",
            name = "Αριστοτέλους",
            street = "Αριστοτέλους",
            lat = 40.6329,
            lon = 22.9398,
        )

    @Test
    fun `round trip keeps every field`() {
        assertEquals(stop, stopFromShortcutExtras(stopShortcutExtras(stop)))
    }

    @Test
    fun `round trip keeps a stop without a street`() {
        val sparse = stop.copy(street = null)
        val restored = stopFromShortcutExtras(stopShortcutExtras(sparse))
        assertEquals(sparse, restored)
        assertNull(restored?.street)
    }

    @Test
    fun `query-relative fields stay out of the payload`() {
        val pinned = stop.copy(distanceKm = 0.42, servingLines = listOf("01", "12"))
        val restored = stopFromShortcutExtras(stopShortcutExtras(pinned))
        assertNull(restored?.distanceKm)
        assertEquals(emptyList<String>(), restored?.servingLines)
    }

    @Test
    fun `id is scoped to the provider`() {
        assertNotEquals(
            stopShortcutId(stop),
            stopShortcutId(stop.copy(provider = Provider.OASA)),
        )
        // Same provider and id, different name: the same shortcut.
        assertEquals(
            stopShortcutId(stop),
            stopShortcutId(stop.copy(name = "Apostolou Pavlou")),
        )
    }

    /** The extras are copied into a PersistableBundle, so an ArrayList
     *  there would make the pin call throw. */
    @Test
    fun `only extras the system can persist`() {
        stopShortcutExtras(stop).forEach { (_, value) ->
            val persistable = value == null || value is String || value is Double
            assertTrue("unpersistable extra: $value", persistable)
        }
    }

    @Test
    fun `unusable payloads are rejected`() {
        val extras = stopShortcutExtras(stop)
        assertNull(stopFromShortcutExtras(emptyMap()))
        assertNull(stopFromShortcutExtras(extras - EXTRA_ID))
        assertNull(stopFromShortcutExtras(extras - EXTRA_LAT))
        assertNull(stopFromShortcutExtras(extras + (EXTRA_LAT to "not a number")))
        assertNull(stopFromShortcutExtras(extras + (EXTRA_ID to "")))
        // Stop ids repeat across providers, so an unknown provider is fatal.
        assertNull(stopFromShortcutExtras(extras + (EXTRA_PROVIDER to "TRAM")))
    }

    /** A toast over the launcher's own dialog would talk over it. */
    @Test
    fun `only the silent outcomes get a message`() {
        assertNull(stopShortcutMessage(StopShortcutResult.REQUESTED))
        assertNotNull(stopShortcutMessage(StopShortcutResult.ALREADY_PINNED))
        assertNotNull(stopShortcutMessage(StopShortcutResult.UNSUPPORTED))
        assertNotEquals(
            stopShortcutMessage(StopShortcutResult.ALREADY_PINNED),
            stopShortcutMessage(StopShortcutResult.UNSUPPORTED),
        )
    }
}
