package app.astiko.ui

import app.astiko.data.model.Arrival
import app.astiko.data.model.VehiclePosition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-location icon condition on arrivals rows: a LIVE arrival whose
 * bus has no GPS fix (CityBus "0"/"0". OASA/OSETh missing vehicle in the
 * live feed) shows the pin+slash icon. Scheduled (offline) rows never do.
 * they carry the calendar icon instead.
 */
class ArrivalNoLocationIconTest {
    private fun arrival(
        vehicle: VehiclePosition? = null,
        isScheduled: Boolean = false,
        vehicleId: String? = "123",
    ) = Arrival(
        routeCode = "route-1",
        lineShortName = "01",
        lineName = "ΚΕΝΤΡΟ",
        destination = "ΚΕΝΤΡΙΚΗ ΠΛΑΤΕΙΑ",
        etaMinutes = 5,
        vehicleId = vehicleId,
        vehicle = vehicle,
        isScheduled = isScheduled,
    )

    private val positioned =
        VehiclePosition(
            vehicleId = "123",
            lat = 39.6389,
            lon = 22.4156,
        )

    @Test
    fun liveArrivalWithoutVehicle_showsIcon() {
        assertTrue(arrival(vehicle = null).showsNoLocationIcon())
    }

    @Test
    fun liveArrivalWithVehicle_noIcon() {
        assertFalse(arrival(vehicle = positioned).showsNoLocationIcon())
    }

    @Test
    fun scheduledArrivalWithoutVehicle_noIcon() {
        // Offline estimate: the calendar icon marks the row instead
        // (vehicle is null here too. The isScheduled flag wins).
        assertFalse(arrival(vehicle = null, isScheduled = true).showsNoLocationIcon())
    }

    @Test
    fun scheduledArrivalWithVehicle_noIcon() {
        // Defensive: a synthetic row must never claim a live bus exists.
        assertFalse(arrival(vehicle = positioned, isScheduled = true).showsNoLocationIcon())
    }

    @Test
    fun vehicleIdAloneDoesNotCountAsPosition() {
        // OASA carries vehicleId (from getStopArrivals) even when the live
        // position join (getBusLocation) found nothing. The icon must not
        // be suppressed by the id.
        assertTrue(arrival(vehicle = null, vehicleId = "42").showsNoLocationIcon())
    }
}
