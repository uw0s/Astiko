package app.astiko.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MapStyleTest {
    @Test
    fun mapStyleUri_light_usesBright() {
        assertEquals(BRIGHT_STYLE, mapStyleUri(dark = false))
    }

    @Test
    fun mapStyleUri_dark_usesFiord() {
        assertEquals(FIORD_STYLE, mapStyleUri(dark = true))
    }

    @Test
    fun mapStyleUri_lightAndDarkDiffer() {
        assertNotEquals(mapStyleUri(dark = false), mapStyleUri(dark = true))
    }
}
