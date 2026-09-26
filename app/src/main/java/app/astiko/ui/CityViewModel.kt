package app.astiko.ui

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.AppPrefs
import app.astiko.data.CityMode
import app.astiko.data.SettingsStore
import app.astiko.data.model.City
import app.astiko.util.LocationTracker
import app.astiko.util.startTrackingWithPoll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Resolves the effective city from the settings:
 * - Not decided yet: the first-launch onboarding screen is shown.
 * - MANUAL: the chosen city.
 * - AUTO: follows GPS live ("Χρήση τοποθεσίας" on onboarding, or the
 *   "Αυτόματα από GPS" choice in the top-bar sheet). The resolved city is
 *   remembered, so a cold start paints it on the first frame.
 *
 * Initial values come from the boot snapshot, the settings flow lands a frame
 * later.
 */
class CityViewModel(
    private val settingsRepository: SettingsStore,
    private val locationProvider: LocationTracker,
) : ViewModel() {
    private val _city = MutableStateFlow(AppPrefs.city ?: City.ATHENS)
    val city: StateFlow<City> = _city.asStateFlow()

    private val _mode = MutableStateFlow(AppPrefs.cityMode)
    val mode: StateFlow<CityMode> = _mode.asStateFlow()

    private val _decided = MutableStateFlow(AppPrefs.cityDecided)
    val decided: StateFlow<Boolean> = _decided.asStateFlow()

    private var trackingHandle: AutoCloseable? = null

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
                        // top-bar sheet).
                        startFollowingGps()
                        locationProvider.currentLocationOrNull()?.let {
                            applyResolvedCity(nearestCity(it))
                        }
                    }
                }
            }
        }
    }

    /**
     * The resolved city is stored in AUTO mode, so the next cold start seeds
     * from it. The snapshot compare keeps a repeat fix from writing the file.
     */
    private fun applyResolvedCity(city: City) {
        _city.value = city
        if (_mode.value != CityMode.AUTO || AppPrefs.city == city) return
        AppPrefs.city = city
        viewModelScope.launch { settingsRepository.setAutoCity(city) }
    }

    private fun startFollowingGps() {
        if (trackingHandle != null) return
        trackingHandle =
            locationProvider.startTrackingWithPoll(viewModelScope) { location ->
                applyResolvedCity(nearestCity(location))
            }
    }

    private fun stopFollowingGps() {
        trackingHandle?.close()
        trackingHandle = null
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
        fun factory(): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    CityViewModel(app.container.settingsRepository, app.container.locationProvider)
                }
            }
    }
}
