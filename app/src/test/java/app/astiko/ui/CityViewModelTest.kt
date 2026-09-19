package app.astiko.ui

import android.location.Location
import app.astiko.data.AppPrefs
import app.astiko.data.CityMode
import app.astiko.data.CitySettings
import app.astiko.data.SettingsStore
import app.astiko.data.model.City
import app.astiko.util.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeSettingsRepository(
    initial: CitySettings = CitySettings(),
) : SettingsStore {
    private val _settings = MutableStateFlow(initial)
    val manualCalls = mutableListOf<City>()
    val autoCityCalls = mutableListOf<City>()
    var autoCalls = 0

    override val settings: Flow<CitySettings> = _settings

    override suspend fun setManual(city: City) {
        manualCalls += city
        _settings.value = CitySettings(CityMode.MANUAL, city, autoDecided = true)
    }

    override suspend fun setAuto() {
        autoCalls += 1
        // Leaves the stored city alone, same as the real store.
        _settings.value = _settings.value.copy(mode = CityMode.AUTO, autoDecided = true)
    }

    override suspend fun setAutoCity(city: City) {
        autoCityCalls += city
        _settings.value = _settings.value.copy(city = city)
    }
}

/** In-memory LocationTracker for ViewModel tests; [fix] is what the
 * provider reports (null = no fix available. The GPS to city math is
 * covered by CityTest). */
class FakeLocationProvider(
    var fix: Location? = null,
) : LocationTracker {
    var trackingStarted = false
    var closed = false

    override suspend fun currentLocationOrNull(timeoutMs: Long): Location? = fix

    override fun lastKnownOrNull(): Location? = fix

    override fun startTracking(
        minTimeMs: Long,
        minDistanceM: Float,
        onFix: (Location) -> Unit,
    ): AutoCloseable {
        trackingStarted = true
        return AutoCloseable { closed = true }
    }
}

/** A [Location] that reports real coordinates. The mockable android.jar in
 * unit tests returns 0.0 from the coordinate getters. */
private class FixLocation(
    latDeg: Double,
    lonDeg: Double,
) : Location("gps") {
    private val latDeg = latDeg
    private val lonDeg = lonDeg

    override fun getLatitude(): Double = latDeg

    override fun getLongitude(): Double = lonDeg
}

@OptIn(ExperimentalCoroutinesApi::class)
class CityViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // The boot snapshot is process-wide. Reset it so one test's city
        // cannot seed the next test's ViewModel.
        AppPrefs.cityDecided = false
        AppPrefs.cityMode = CityMode.AUTO
        AppPrefs.city = null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        location: FakeLocationProvider = FakeLocationProvider(),
    ) = CityViewModel(settings, location)

    @Test
    fun onboarding_notDecided_doesNotTrackGps() =
        runTest(mainDispatcher.scheduler) {
            val location = FakeLocationProvider()
            val vm = viewModel(location = location)
            runCurrent()

            assertFalse(vm.decided.value)
            assertEquals(CityMode.AUTO, vm.mode.value)
            assertEquals(City.ATHENS, vm.city.value) // nothing resolved yet
            assertFalse(location.trackingStarted)
        }

    @Test
    fun manualMode_usesChosenCity_noGps() =
        runTest(mainDispatcher.scheduler) {
            val location = FakeLocationProvider()
            val settings =
                FakeSettingsRepository(
                    CitySettings(CityMode.MANUAL, City.LAMIA, autoDecided = true),
                )
            val vm = viewModel(settings, location)
            runCurrent()

            assertTrue(vm.decided.value)
            assertEquals(CityMode.MANUAL, vm.mode.value)
            assertEquals(City.LAMIA, vm.city.value)
            assertFalse(location.trackingStarted)
        }

    @Test
    fun manualModeWithStaleCity_keepsManualWithoutGpsTracking() =
        runTest(mainDispatcher.scheduler) {
            // A stored city name that no longer resolves (enum renamed after
            // an update) must not silently flip the user into GPS-follow mode.
            // their choice was a city, and AUTO without permission/fix would
            // strand them on the default city with no explanation.
            val location = FakeLocationProvider()
            val settings =
                FakeSettingsRepository(CitySettings(CityMode.MANUAL, null, autoDecided = true))
            val vm = viewModel(settings, location)
            runCurrent()

            assertTrue(vm.decided.value)
            assertEquals(CityMode.MANUAL, vm.mode.value)
            assertEquals(City.ATHENS, vm.city.value) // default, not GPS-followed
            assertFalse(location.trackingStarted)
            assertFalse(location.closed) // nothing started, nothing to stop
        }

    @Test
    fun autoMode_startsTracking_cityStaysDefaultWithoutFix() =
        runTest(mainDispatcher.scheduler) {
            val location = FakeLocationProvider()
            val settings =
                FakeSettingsRepository(CitySettings(CityMode.AUTO, null, autoDecided = true))
            val vm = viewModel(settings, location)
            runCurrent()

            assertTrue(vm.decided.value)
            assertEquals(CityMode.AUTO, vm.mode.value)
            assertTrue(location.trackingStarted)
            // No location fix available: default city, GPS will resolve it live.
            assertEquals(City.ATHENS, vm.city.value)

            // Cleanup: leave AUTO mode so the GPS poll loop stops. A running
            // poll loop would keep the test scheduler busy forever.
            settings.setManual(City.ATHENS)
            runCurrent()
        }

    @Test
    fun coldStart_seedsDecidedAndCityFromTheSnapshot() =
        runTest(mainDispatcher.scheduler) {
            // Seeded from the boot snapshot: the store's first value lands a
            // frame later, when the onboarding screen would already be drawn.
            AppPrefs.cityDecided = true
            AppPrefs.cityMode = CityMode.MANUAL
            AppPrefs.city = City.LARISSA

            val vm =
                viewModel(
                    FakeSettingsRepository(CitySettings(CityMode.MANUAL, City.LARISSA, true)),
                )

            assertTrue(vm.decided.value)
            assertEquals(CityMode.MANUAL, vm.mode.value)
            assertEquals(City.LARISSA, vm.city.value)
        }

    @Test
    fun autoMode_remembersTheResolvedCityOnce() =
        runTest(mainDispatcher.scheduler) {
            val settings =
                FakeSettingsRepository(CitySettings(CityMode.AUTO, null, autoDecided = true))
            val location = FakeLocationProvider(fix = FixLocation(40.6329, 22.9398))
            val vm = viewModel(settings, location)
            runCurrent()

            assertEquals(City.THESSALONIKI, vm.city.value)
            assertEquals(listOf(City.THESSALONIKI), settings.autoCityCalls)

            // A later fix in the same city must not write the file again.
            advanceTimeBy(15_001)
            runCurrent()
            assertEquals(listOf(City.THESSALONIKI), settings.autoCityCalls)

            // Cleanup: back to MANUAL so the GPS poll loop stops (see above).
            settings.setManual(City.THESSALONIKI)
            runCurrent()
        }

    @Test
    fun selectCity_persistsAndApplies() =
        runTest(mainDispatcher.scheduler) {
            val settings =
                FakeSettingsRepository(CitySettings(CityMode.AUTO, null, autoDecided = true))
            val vm = viewModel(settings)
            runCurrent()

            vm.selectCity(City.LARISSA)
            runCurrent()

            assertEquals(listOf(City.LARISSA), settings.manualCalls)
            assertEquals(CityMode.MANUAL, vm.mode.value)
            assertEquals(City.LARISSA, vm.city.value)
        }

    @Test
    fun selectAuto_persists() =
        runTest(mainDispatcher.scheduler) {
            val settings =
                FakeSettingsRepository(
                    CitySettings(CityMode.MANUAL, City.PATRA, autoDecided = true),
                )
            val vm = viewModel(settings)
            runCurrent()

            vm.selectAuto()
            runCurrent()

            assertEquals(1, settings.autoCalls)
            assertEquals(CityMode.AUTO, vm.mode.value)

            // Cleanup: back to MANUAL so the GPS poll loop stops (see above).
            settings.setManual(City.PATRA)
            runCurrent()
        }

    @Test
    fun settingsChange_manualStopsGpsTracking() =
        runTest(mainDispatcher.scheduler) {
            val settings =
                FakeSettingsRepository(CitySettings(CityMode.AUTO, null, autoDecided = true))
            val location = FakeLocationProvider()
            val vm = viewModel(settings, location)
            runCurrent()
            assertTrue(location.trackingStarted)

            // The user picks a city in the sheet while in AUTO. The repo
            // persists MANUAL and the VM must stop following GPS.
            settings.setManual(City.KOZANI)
            runCurrent()

            assertTrue(location.closed)
            assertEquals(City.KOZANI, vm.city.value)
        }

    @Test
    fun settingsChange_onboardingToManual() =
        runTest(mainDispatcher.scheduler) {
            val settings = FakeSettingsRepository() // not decided yet
            val vm = viewModel(settings)
            runCurrent()
            assertFalse(vm.decided.value)

            // First-launch choice: Larissa, manual.
            settings.setManual(City.LARISSA)
            runCurrent()

            assertTrue(vm.decided.value)
            assertEquals(City.LARISSA, vm.city.value)
        }
}
