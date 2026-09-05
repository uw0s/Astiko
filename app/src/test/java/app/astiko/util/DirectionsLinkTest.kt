package app.astiko.util

import app.astiko.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DirectionsLinkTest {
    @Test
    fun mapsDirectionsUrl_carriesDestinationAndWalkingMode() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=37.9837,23.7349&travelmode=walking",
            mapsDirectionsUrl(GeoPoint(37.9837, 23.7349)),
        )
    }

    @Test
    fun mapsDirectionsUrl_neverCarriesOriginOrStopName() {
        // Destination only: the maps app supplies the origin itself.
        val url = mapsDirectionsUrl(GeoPoint(40.6267, 22.9639))
        assertFalse(url.contains("origin="))
        assertFalse(url.contains("query="))
        assertFalse(url.contains("place="))
    }

    @Test
    fun mapsDirectionsUrl_keepsDotDecimalSeparator_inAnyLocale() {
        // A String.format with the default locale would emit commas
        // instead of '.'.
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "https://www.google.com/maps/dir/?api=1&destination=40.6267,22.9639&travelmode=walking",
                mapsDirectionsUrl(GeoPoint(40.6267, 22.9639)),
            )
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun mapsDirectionsUrl_handlesNegativeAndZeroCoordinates() {
        // Not Greece-bound: a minus sign and a zero must survive.
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=-33.8688,151.2093&travelmode=walking",
            mapsDirectionsUrl(GeoPoint(-33.8688, 151.2093)),
        )
        assertTrue(mapsDirectionsUrl(GeoPoint(0.0, 0.0)).contains("destination=0.0,0.0"))
    }
}
