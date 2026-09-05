package app.astiko.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.astiko.data.model.City
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** SettingsRepository tests, the real DataStore over a temp file. */
class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.newFolder(), "settings.preferences_pb")
            }
        return SettingsRepository(dataStore)
    }

    @Test
    fun `defaults are auto mode undecided`() =
        runTest {
            val settings = repo().settings.first()
            assertEquals(CityMode.AUTO, settings.mode)
            assertNull(settings.city)
            assertFalse(settings.autoDecided)
        }

    @Test
    fun `setManual persists the city and marks decided`() =
        runTest {
            val repository = repo()
            repository.setManual(City.PATRA)

            val settings = repository.settings.first()
            assertEquals(CityMode.MANUAL, settings.mode)
            assertEquals(City.PATRA, settings.city)
            assertTrue(settings.autoDecided)
        }

    @Test
    fun `setAuto persists auto mode`() =
        runTest {
            val repository = repo()
            repository.setAuto()

            val settings = repository.settings.first()
            assertEquals(CityMode.AUTO, settings.mode)
            assertTrue(settings.autoDecided)
        }

    @Test
    fun `unknown stored city name resolves to null city`() =
        runTest {
            // Resilience: a renamed/removed City enum (app downgrade, drift)
            // must not crash the settings read. The city resolves to null and
            // the CityViewModel falls back to GPS-follow.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStore =
                PreferenceDataStoreFactory.create(scope = scope) {
                    File(tmp.newFolder(), "settings.preferences_pb")
                }
            dataStore.edit {
                it[stringPreferencesKey("mode")] = "manual"
                it[stringPreferencesKey("city")] = "ΑΤΛΑΝΤΙΔΑ"
                it[booleanPreferencesKey("auto_decided")] = true
            }

            val settings = SettingsRepository(dataStore).settings.first()
            assertEquals(CityMode.MANUAL, settings.mode)
            assertNull(settings.city)
            assertTrue(settings.autoDecided)
        }

    @Test
    fun `appearance round-trips all three modes`() =
        runTest {
            val repository = repo()
            repository.setThemeMode(ThemeMode.DARK)
            repository.setLanguage(AppLanguage.EN)
            repository.setMapThemeMode(MapThemeMode.LIGHT)

            val appearance = repository.appearance.first()
            assertEquals(ThemeMode.DARK, appearance.theme)
            assertEquals(AppLanguage.EN, appearance.language)
            assertEquals(MapThemeMode.LIGHT, appearance.mapTheme)

            // And back to defaults.
            repository.setThemeMode(ThemeMode.SYSTEM)
            repository.setLanguage(AppLanguage.SYSTEM)
            repository.setMapThemeMode(MapThemeMode.SYSTEM)
            val reset = repository.appearance.first()
            assertEquals(ThemeMode.SYSTEM, reset.theme)
            assertEquals(AppLanguage.SYSTEM, reset.language)
            assertEquals(MapThemeMode.SYSTEM, reset.mapTheme)
        }
}
