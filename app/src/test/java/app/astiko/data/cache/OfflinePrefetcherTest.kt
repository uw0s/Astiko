package app.astiko.data.cache

import app.astiko.data.TransitRepository
import app.astiko.data.model.Arrival
import app.astiko.data.model.City
import app.astiko.data.model.GeoPoint
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.data.model.VehiclePosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import kotlin.time.Duration.Companion.seconds

/**
 * OfflinePrefetcher tests: status progression, the per-city provider
 * tagging (another city's Done must never leak), the Athens lines-only
 * scope, and the Wi-Fi gate.
 */
class OfflinePrefetcherTest {
    private val line =
        Line(provider = Provider.OSETh, id = "01_7429_1_3", shortName = "01", longName = "TEST")
    private val variant =
        LineVariant(
            provider = Provider.OSETh,
            lineId = line.id,
            id = "01_7429_1_3",
            shapeId = "5304",
            label = "ΚΑΤΕΥΘΥΝΣΗ",
            lineShortName = "01",
        )

    private fun prefetcher(
        delegate: FakeRepo,
        wifi: Boolean = true,
    ) = OfflinePrefetcher(
        repository = { delegate },
        wifiCheck = { wifi },
    )

    private suspend fun awaitTerminal(prefetcher: OfflinePrefetcher) {
        withTimeout(10.seconds) {
            while (prefetcher.status.value is PrefetchStatus.Running) {
                delay(10)
            }
        }
    }

    @Test
    fun `full run fetches the catalog and ends Done tagged to the provider`() =
        runTest {
            val delegate = FakeRepo(lines = listOf(line), variants = listOf(variant))
            val prefetcher = prefetcher(delegate)

            prefetcher.start(City.THESSALONIKI)
            awaitTerminal(prefetcher)

            assertEquals(PrefetchStatus.Done(Provider.OSETh), prefetcher.status.value)
            assertEquals(1, delegate.linesCalls)
            assertEquals(1, delegate.variantsCalls)
            assertEquals(1, delegate.routeStopsCalls)
            assertEquals(1, delegate.geometryCalls)
            assertEquals(1, delegate.nearbyCalls)
        }

    @Test
    fun `athens prefetch is lines-only`() =
        runTest {
            val delegate = FakeRepo(lines = listOf(line), variants = listOf(variant))
            val prefetcher = prefetcher(delegate)

            prefetcher.start(City.ATHENS)
            awaitTerminal(prefetcher)

            assertEquals(PrefetchStatus.Done(Provider.OASA), prefetcher.status.value)
            assertEquals(1, delegate.linesCalls)
            assertEquals(1, delegate.variantsCalls)
            assertEquals(0, delegate.routeStopsCalls)
            assertEquals(0, delegate.geometryCalls)
        }

    @Test
    fun `no wifi yields NeedsWifi tagged to the provider and no fetches`() =
        runTest {
            val delegate = FakeRepo(lines = listOf(line), variants = listOf(variant))
            val prefetcher = prefetcher(delegate, wifi = false)

            prefetcher.start(City.THESSALONIKI)

            assertEquals(PrefetchStatus.NeedsWifi(Provider.OSETh), prefetcher.status.value)
            assertEquals(0, delegate.linesCalls)
        }

    @Test
    fun `getLines failure yields Failed with the message`() =
        runTest {
            val delegate = FakeRepo(linesError = IOException("boom"))
            val prefetcher = prefetcher(delegate)

            prefetcher.start(City.THESSALONIKI)
            awaitTerminal(prefetcher)

            assertEquals(PrefetchStatus.Failed(Provider.OSETh, "boom"), prefetcher.status.value)
        }

    @Test
    fun `a second start while running is ignored`() =
        runTest {
            val delegate = FakeRepo(lines = listOf(line), variants = listOf(variant))
            val gate = CompletableDeferred<Unit>()
            delegate.linesGate = gate
            val prefetcher = prefetcher(delegate)

            prefetcher.start(City.THESSALONIKI)
            // Wait until the first run is actually inside getLines (it launches
            // on Dispatchers.IO, so the call may not have started yet).
            withTimeout(10.seconds) {
                while (delegate.linesCalls == 0) delay(10)
            }
            prefetcher.start(City.THESSALONIKI) // blocked by the guard

            assertEquals(1, delegate.linesCalls)
            gate.complete(Unit)
            awaitTerminal(prefetcher)
            assertEquals(PrefetchStatus.Done(Provider.OSETh), prefetcher.status.value)
        }

    @Test
    fun `a run after Done starts again`() =
        runTest {
            val delegate = FakeRepo(lines = listOf(line), variants = listOf(variant))
            val prefetcher = prefetcher(delegate)

            prefetcher.start(City.THESSALONIKI)
            awaitTerminal(prefetcher)
            assertEquals(PrefetchStatus.Done(Provider.OSETh), prefetcher.status.value)

            prefetcher.start(City.THESSALONIKI)
            awaitTerminal(prefetcher)
            assertEquals(2, delegate.linesCalls)
            assertEquals(PrefetchStatus.Done(Provider.OSETh), prefetcher.status.value)
        }

    @Test
    fun `empty lines yield Failed`() =
        runTest {
            val delegate = FakeRepo(lines = emptyList())
            val prefetcher = prefetcher(delegate)

            prefetcher.start(City.THESSALONIKI)
            awaitTerminal(prefetcher)

            assertEquals(PrefetchStatus.Failed(Provider.OSETh, null), prefetcher.status.value)
        }

    /** Minimal delegate; the prefetcher only walks the catalog. */
    class FakeRepo(
        var lines: List<Line> = emptyList(),
        var variants: List<LineVariant> = emptyList(),
        var linesError: IOException? = null,
    ) : TransitRepository {
        override val provider: Provider = Provider.OSETh

        var nearbyCalls = 0
        var linesCalls = 0
        var variantsCalls = 0
        var routeStopsCalls = 0
        var geometryCalls = 0

        /** When set, getLines suspends until released, holding a run open. */
        var linesGate: CompletableDeferred<Unit>? = null

        override suspend fun getStopsNear(
            lat: Double,
            lon: Double,
            limit: Int,
        ): List<Stop> {
            nearbyCalls++
            return emptyList()
        }

        override suspend fun getStopRoutes(stopId: String): List<Line> = emptyList()

        override fun stopLinesForDisplay(lines: List<Line>): List<Line> = lines

        override suspend fun getLines(): List<Line> {
            linesCalls++
            linesGate?.await()
            linesError?.let { throw it }
            return lines
        }

        override suspend fun getLineVariants(line: Line): List<LineVariant> {
            variantsCalls++
            return variants
        }

        override suspend fun getVariantStops(variant: LineVariant): List<Stop> {
            routeStopsCalls++
            return emptyList()
        }

        override suspend fun getStopCatalog(): List<Stop> = emptyList()

        override suspend fun getRouteGeometry(variant: LineVariant): List<GeoPoint> {
            geometryCalls++
            return emptyList()
        }

        override fun observeVehicles(variant: LineVariant): Flow<List<VehiclePosition>> = flow { emit(emptyList()) }

        override fun observeArrivals(
            stopId: String,
            lines: List<Line>,
        ): Flow<List<Arrival>> = flow { emit(emptyList()) }

        override suspend fun getStopTimetable(
            stopId: String,
            day: DayOfWeek,
        ): List<TimetableEntry> = emptyList()

        override suspend fun getLineTimetable(
            variant: LineVariant,
            day: DayOfWeek,
        ): List<TimetableEntry> = emptyList()
    }
}
