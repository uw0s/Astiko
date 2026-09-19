package app.astiko.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.astiko.data.model.City
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class CityMode { AUTO, MANUAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MapThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage { SYSTEM, EL, EN }

data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val mapTheme: MapThemeMode = MapThemeMode.SYSTEM,
)

/**
 * Process-wide synchronous snapshot of the settings. DataStore values are
 * async, while the base-context locale and the first Compose frame both need
 * them earlier, so TransitApp snapshots the store at boot and MainActivity
 * refreshes the appearance fields before recreating on a change.
 */
object AppPrefs {
    @Volatile
    var theme: ThemeMode = ThemeMode.SYSTEM

    @Volatile
    var language: AppLanguage = AppLanguage.SYSTEM

    @Volatile
    var mapTheme: MapThemeMode = MapThemeMode.SYSTEM

    @Volatile
    var cityDecided: Boolean = false

    @Volatile
    var cityMode: CityMode = CityMode.AUTO

    @Volatile
    var city: City? = null
}

/**
 * City selection settings.
 * - `AUTO` = follow GPS. `city` holds the last city GPS resolved, the
 *   cold-start seed.
 * - `MANUAL` = fixed city: the user's choice, or the GPS-detected city
 *   pinned on first launch (decide once, then remember)
 */
data class CitySettings(
    val mode: CityMode = CityMode.AUTO,
    val city: City? = null,
    val autoDecided: Boolean = false,
)

/** Everything the store holds, from one read. */
data class SettingsSnapshot(
    val appearance: AppearanceSettings,
    val city: CitySettings,
)

interface SettingsStore {
    val settings: Flow<CitySettings>

    suspend fun setManual(city: City)

    suspend fun setAuto()

    /** Stores the city GPS resolved, leaves the mode and the onboarding flag alone. */
    suspend fun setAutoCity(city: City)
}

internal val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    private val modeKey = stringPreferencesKey("mode")
    private val cityKey = stringPreferencesKey("city")
    private val decidedKey = booleanPreferencesKey("auto_decided")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val languageKey = stringPreferencesKey("language")
    private val mapThemeModeKey = stringPreferencesKey("map_theme_mode")

    val appearance: Flow<AppearanceSettings> = dataStore.data.map { appearanceOf(it) }

    /** The whole store from a single read, for the cold-start snapshot. */
    suspend fun snapshot(): SettingsSnapshot =
        dataStore.data.first().let { SettingsSnapshot(appearanceOf(it), citySettingsOf(it)) }

    private fun appearanceOf(prefs: Preferences): AppearanceSettings =
        AppearanceSettings(
            theme =
                when (prefs[themeModeKey]) {
                    "light" -> ThemeMode.LIGHT
                    "dark" -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                },
            language =
                when (prefs[languageKey]) {
                    "el" -> AppLanguage.EL
                    "en" -> AppLanguage.EN
                    else -> AppLanguage.SYSTEM
                },
            mapTheme =
                when (prefs[mapThemeModeKey]) {
                    "light" -> MapThemeMode.LIGHT
                    "dark" -> MapThemeMode.DARK
                    else -> MapThemeMode.SYSTEM
                },
        )

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit {
            it[themeModeKey] =
                when (mode) {
                    ThemeMode.SYSTEM -> "system"
                    ThemeMode.LIGHT -> "light"
                    ThemeMode.DARK -> "dark"
                }
        }
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit {
            it[languageKey] =
                when (language) {
                    AppLanguage.SYSTEM -> "system"
                    AppLanguage.EL -> "el"
                    AppLanguage.EN -> "en"
                }
        }
    }

    suspend fun setMapThemeMode(mode: MapThemeMode) {
        dataStore.edit {
            it[mapThemeModeKey] =
                when (mode) {
                    MapThemeMode.SYSTEM -> "system"
                    MapThemeMode.LIGHT -> "light"
                    MapThemeMode.DARK -> "dark"
                }
        }
    }

    override val settings: Flow<CitySettings> = dataStore.data.map { citySettingsOf(it) }

    private fun citySettingsOf(prefs: Preferences): CitySettings =
        CitySettings(
            mode = if (prefs[modeKey] == "manual") CityMode.MANUAL else CityMode.AUTO,
            city = prefs[cityKey]?.let { name -> City.entries.firstOrNull { it.name == name } },
            autoDecided = prefs[decidedKey] ?: false,
        )

    override suspend fun setManual(city: City) {
        dataStore.edit {
            it[modeKey] = "manual"
            it[cityKey] = city.name
            it[decidedKey] = true
        }
    }

    override suspend fun setAuto() {
        dataStore.edit {
            it[modeKey] = "auto"
            it[decidedKey] = true
        }
    }

    override suspend fun setAutoCity(city: City) {
        dataStore.edit { it[cityKey] = city.name }
    }
}
