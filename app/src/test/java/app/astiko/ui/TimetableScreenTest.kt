package app.astiko.ui

import app.astiko.data.model.TimetableEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** Pure logic of the timetable screen: the "next departure" highlight. */
class TimetableScreenTest {
    private fun trip(time: String) = TimetableEntry("01", "ΓΡΑΜΜΗ", "ΚΕΝΤΡΟ", time)

    @Test
    fun nextDepartureIndex_firstTripNotBeforeNow() {
        val trips = listOf(trip("06:00"), trip("06:20"), trip("06:40"))
        assertEquals(2, nextDepartureIndex(trips, LocalTime.of(6, 25)))
    }

    @Test
    fun nextDepartureIndex_tripExactlyAtNowCountsAsNext() {
        val trips = listOf(trip("06:00"), trip("06:20"))
        assertEquals(1, nextDepartureIndex(trips, LocalTime.of(6, 20)))
    }

    @Test
    fun nextDepartureIndex_duplicateMinute_highlightsOnlyTheFirst() {
        // Two routes of the same line depart at the same minute. Only the
        // first row may render as "the next departure".
        val trips = listOf(trip("06:40"), trip("06:40"))
        assertEquals(0, nextDepartureIndex(trips, LocalTime.of(6, 35)))
    }

    @Test
    fun nextDepartureIndex_allPassed_returnsNull() {
        val trips = listOf(trip("06:00"), trip("06:20"))
        assertNull(nextDepartureIndex(trips, LocalTime.of(7, 0)))
    }

    @Test
    fun nextDepartureIndex_empty_returnsNull() {
        assertNull(nextDepartureIndex(emptyList(), LocalTime.of(6, 0)))
    }

    // ---------- weekDaysStartingAt (chips row order) ----------

    @Test
    fun weekDays_startsAtTheSelectedDay() {
        // Saturday first: the row opens with the selected chip at the
        // left edge for any day of the week.
        assertEquals(
            listOf(
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY,
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            ),
            weekDaysStartingAt(DayOfWeek.SATURDAY),
        )
    }

    @Test
    fun weekDays_coversAllSevenDaysForAnyStart() {
        assertEquals(7, weekDaysStartingAt(DayOfWeek.MONDAY).size)
        assertEquals(DayOfWeek.entries.toSet(), weekDaysStartingAt(DayOfWeek.WEDNESDAY).toSet())
        assertEquals(DayOfWeek.THURSDAY, weekDaysStartingAt(DayOfWeek.FRIDAY).last())
    }

    @Test
    fun nextDepartureIndex_unparseableTimesSkipped() {
        val trips = listOf(trip("junk"), trip("07:00"))
        assertEquals(1, nextDepartureIndex(trips, LocalTime.of(6, 0)))
    }
}
