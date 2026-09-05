package app.astiko.ui

import app.astiko.data.model.Arrival
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FormatTest {
    private var originalLocale: Locale? = null

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // "%.1f" uses the decimal separator
    }

    @After
    fun tearDown() {
        originalLocale?.let(Locale::setDefault)
    }

    @Test
    fun distance_subKilometer_inMeters_noSpace() {
        assertEquals("830m", formatDistance(0.83))
        assertEquals("45m", formatDistance(0.045))
        assertEquals("1m", formatDistance(0.001))
    }

    @Test
    fun distance_roundsHalfUp() {
        assertEquals("830m", formatDistance(0.8296))
        assertEquals("1000m", formatDistance(0.9996)) // rounds into the km zone but stays "m"
    }

    @Test
    fun distance_kilometer_oneDecimal() {
        assertEquals("1.2km", formatDistance(1.2))
        assertEquals("12.3km", formatDistance(12.345))
        assertEquals("1.0km", formatDistance(1.0))
    }

    @Test
    fun eta_zeroOrNegative_isNowLabel() {
        assertEquals("Τώρα", formatEta(0, "Τώρα"))
        assertEquals("Now", formatEta(-1, "Now"))
    }

    @Test
    fun eta_positive_usesPrimeSuffix() {
        assertEquals("3′", formatEta(3, "Τώρα"))
        assertEquals("15′", formatEta(15, "Now"))
    }

    // arrivalTimeText: the "a schedule never reads as a live countdown"
    // rule shared by the arrivals rows and the tapped-bus card. It
    // drifted once (the card showed a countdown for scheduled rows), so
    // both call sites must go through this function.

    private fun arrival(
        etaMinutes: Int? = null,
        scheduledTime: String? = null,
        isScheduled: Boolean = false,
    ) = Arrival(
        routeCode = "01",
        lineShortName = "01",
        lineName = "01",
        destination = "",
        etaMinutes = etaMinutes,
        scheduledTime = scheduledTime,
        isScheduled = isScheduled,
    )

    @Test
    fun scheduled_alwaysClockTime_evenWhenMinutesAway() {
        // The trip is 5 minutes away, but a schedule is not a live
        // countdown. It must never read as one.
        assertEquals(
            "12:00",
            arrivalTimeText(
                arrival(etaMinutes = 5, scheduledTime = "12:00", isScheduled = true),
                "Now",
            ),
        )
    }

    @Test
    fun scheduled_withoutScheduledTime_isNull() {
        assertNull(arrivalTimeText(arrival(etaMinutes = 5, isScheduled = true), "Now"))
    }

    @Test
    fun live_imminent_countsDownInMinutes() {
        assertEquals("5′", arrivalTimeText(arrival(etaMinutes = 5, scheduledTime = "12:00"), "Now"))
        assertEquals(
            "90′",
            arrivalTimeText(arrival(etaMinutes = 90, scheduledTime = "12:00"), "Now"),
        ) // boundary
    }

    @Test
    fun live_far_showsClockTime() {
        assertEquals(
            "12:00",
            arrivalTimeText(arrival(etaMinutes = 120, scheduledTime = "12:00"), "Now"),
        )
    }

    @Test
    fun live_far_withoutScheduledTime_fallsBackToMinutes() {
        assertEquals(
            "120′",
            arrivalTimeText(arrival(etaMinutes = 120, scheduledTime = null), "Now"),
        )
    }

    @Test
    fun live_noTime_isNull() {
        assertNull(arrivalTimeText(arrival(etaMinutes = null, scheduledTime = null), "Now"))
    }

    // ---------- directionDestination (direction-switcher subtitle) ----------

    @Test
    fun directionDestination_lastDashSegment() {
        assertEquals("ΣΥΝΤΑΓΜΑ", directionDestination("ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ"))
        assertEquals("Κ.Τ.Ε.Λ.", directionDestination("Τ.Σ. ΕΥΚΑΡΠΙΑΣ - ΣΚΛΑΒΕΝΙΤΗΣ - Κ.Τ.Ε.Λ."))
        assertEquals("ΚΕΝΤΡΟ", directionDestination("ΑΣ ΙΚΕΑ - ΚΤΕΛ - ΚΕΝΤΡΟ"))
    }

    @Test
    fun directionDestination_stripsDaySuffixes() {
        assertEquals(
            "Κ.Τ.Ε.Λ.",
            directionDestination("Τ.Σ. ΕΥΚΑΡΠΙΑΣ - Κ.Τ.Ε.Λ. - ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ"),
        )
        assertEquals("AIRPORT", directionDestination("KTEL - AIRPORT - SATURDAY-SUNDAY"))
        assertEquals("ΚΕΝΤΡΟ", directionDestination("ΕΥΚΑΡΠΙΑ - ΚΕΝΤΡΟ - ΚΥΡΙΑΚΗ"))
        // A day-suffix-only label: falls back to the segment before it.
        assertEquals("Α", directionDestination("Α - ΣΑΒΒΑΤΟ"))
    }

    @Test
    fun directionDestination_stripsBracketedAnnotations() {
        assertEquals("ΣΥΝΤΑΓΜΑ", directionDestination("ΠΕΙΡΑΙΑΣ - ΣΥΝΤΑΓΜΑ [Προσωρινή λόγω έργων]"))
        assertNull(directionDestination("ΜΟΣΧΑΤΟ [Προσωρινή λόγω έργων]"))
    }

    @Test
    fun directionDestination_null_whenNoSeparatorOrNothingRemains() {
        assertNull(directionDestination("ΜΟΣΧΑΤΟ"))
        assertNull(directionDestination(""))
        assertNull(directionDestination("   "))
        assertNull(directionDestination("-"))
    }

    @Test
    fun directionDestination_null_whenDestinationTooLong() {
        assertNull(directionDestination("Α - " + "X".repeat(33)))
        assertEquals("X".repeat(32), directionDestination("Α - " + "X".repeat(32))) // boundary
    }
}
