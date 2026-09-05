package app.astiko.ui

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.CityMode
import app.astiko.data.SettingsStore
import app.astiko.data.model.City
import app.astiko.util.LocationTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Resolves the effective city from the settings:
 * - Not decided yet: the first-launch onboarding screen is shown.
 * - MANUAL: the chosen city.
 * - AUTO: follows GPS live ("Χρήση τοποθεσίας" on onboarding, or the
 *   "Αυτόματα από GPS" choice in the top-bar sheet).
 */
class CityViewModel(
    private val settingsRepository: SettingsStore,
    private val locationProvider: LocationTracker,
) : ViewModel() {
    private val _city = MutableStateFlow(City.ATHENS)
    val city: StateFlow<City> = _city.asStateFlow()

    private val _mode = MutableStateFlow(CityMode.AUTO)
    val mode: StateFlow<CityMode> = _mode.asStateFlow()

    private val _decided = MutableStateFlow(false)
    val decided: StateFlow<Boolean> = _decided.asStateFlow()

    private var trackingHandle: AutoCloseable? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _mode.value = settings.mode
                _decided.value = settings.autoDecided
                when {
                    // Onboarding still open. Resolve nothing until the user
                    // picks a city or opts into location.
                    !settings.autoDecided -> {
                        stopFollowingGps()
                    }

                    settings.mode == CityMode.MANUAL && settings.city != null -> {
                        stopFollowingGps()
                        _city.value = settings.city
                    }

                    // Stale or corrupt stored city name (an enum renamed after
                    // an update). The user's choice was a CITY, not "follow GPS",
                    // so keep the manual mode and the current city instead of
                    // silently starting GPS tracking. Tracking would also no-op
                    // without permission and strand the user on the default city.
                    settings.mode == CityMode.MANUAL -> {
                        stopFollowingGps()
                    }

                    else -> {
                        // Explicit "follow GPS" (onboarding choice or the
                        // top-bar sheet). Track live, don't persist each move.
                        startFollowingGps()
                        locationProvider.currentLocationOrNull()?.let {
                            _city.value =
                                nearestCity(it)
                        }
                    }
                }
            }
        }
    }

    private fun startFollowingGps() {
        if (trackingHandle != null) return
        trackingHandle =
            locationProvider.startTracking { location ->
                _city.value = nearestCity(location)
            }
        // Polled fallback: a fix can update the cache without reaching
        // listeners.
        if (pollJob == null) {
            pollJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(CITY_POLL_MS)
                        locationProvider.lastKnownOrNull()?.let { _city.value = nearestCity(it) }
                    }
                }
        }
    }

    private fun stopFollowingGps() {
        trackingHandle?.close()
        trackingHandle = null
        pollJob?.cancel()
        pollJob = null
    }

    fun selectCity(city: City) {
        viewModelScope.launch { settingsRepository.setManual(city) }
    }

    fun selectAuto() {
        viewModelScope.launch { settingsRepository.setAuto() }
    }

    private fun nearestCity(location: Location?): City {
        if (location == null) return City.ATHENS
        return City.nearestCity(location.latitude, location.longitude)
    }

    override fun onCleared() {
        stopFollowingGps()
    }

    companion object {
        private const val CITY_POLL_MS = 15_000L

        fun factory(): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    CityViewModel(app.container.settingsRepository, app.container.locationProvider)
                }
            }
    }
}
