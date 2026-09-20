package app.astiko.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FavoritesRepository tests, JVM with the REAL DataStore over a temp file
 * (not a fake: these cover the JSON decode/migration paths a fake would
 * bypass, the per-entry resilience contract and identity semantics).
 */
class FavoritesRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** On the test's scope: runTest cancels it, so no collector outlives the test. */
    private fun dataStore(
        scope: CoroutineScope,
        name: String = "favorites",
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.newFolder(), "$name.preferences_pb")
        }

    private val stop =
        Stop(
            provider = Provider.OASA,
            id = "60010",
            name = "ΝΑΥΑΡΙΝΟΥ",
            lat = 37.9837,
            lon = 23.7349,
        )

    // The stateIn cache updates asynchronously after an edit lands. Every
    // assertion AWAITS the expected state instead of reading the possibly
    // stale cached value (the previous assert pins the current value, so
    // the predicate-based waits are deterministic).

    @Test
    fun `toggle adds and removes a stop`() =
        runTest {
            val repository = FavoritesRepository(dataStore(backgroundScope), backgroundScope)
            assertTrue(repository.favoriteStops.value.isEmpty())

            repository.toggle(stop)
            assertEquals(listOf(stop), repository.favoriteStops.first { it.isNotEmpty() })

            repository.toggle(stop)
            assertTrue(repository.favoriteStops.first { it.isEmpty() }.isEmpty())
        }

    @Test
    fun `same id different provider are distinct favorites`() =
        runTest {
            val repository = FavoritesRepository(dataStore(backgroundScope), backgroundScope)
            val oasa = Stop(provider = Provider.OASA, id = "100", name = "Α", lat = 1.0, lon = 1.0)
            val citybus =
                Stop(provider = Provider.CITYBUS, id = "100", name = "Β", lat = 2.0, lon = 2.0)

            repository.toggle(oasa)
            repository.toggle(citybus)

            assertEquals(2, repository.favoriteStops.first { it.size == 2 }.size)

            // Toggling the OASA one again removes only it.
            repository.toggle(oasa)
            assertEquals(listOf(citybus), repository.favoriteStops.first { it.size == 1 })
        }

    @Test
    fun `replace updates the favorite in place`() =
        runTest {
            val repository = FavoritesRepository(dataStore(backgroundScope), backgroundScope)
            repository.toggle(stop)
            repository.favoriteStops.first { it.isNotEmpty() }

            // The enriched favorite (badges fetched) replaces the stored one.
            val enriched = stop.copy(servingLines = listOf("040", "10"))
            repository.replace(enriched)

            assertEquals(
                listOf(enriched),
                repository.favoriteStops.first { it[0].servingLines.isNotEmpty() },
            )
        }

    @Test
    fun `unreadable entries drop only themselves`() =
        runTest {
            // The per-entry resilience contract: one unknown provider (enum
            // drift across app versions) must not wipe the whole list.
            val store = dataStore(backgroundScope)
            val repository = FavoritesRepository(store, backgroundScope)
            store.edit {
                it[stringPreferencesKey("stops")] = """[
                {"provider":"CITYBUS_FUTURE_CITY","id":"x","name":"ΦΑΝΤΑΣΜΑ","lat":1.0,"lon":1.0},
                {"provider":"OASA","id":"60010","name":"ΝΑΥΑΡΙΝΟΥ","lat":37.9837,"lon":23.7349}
            ]"""
            }

            val favorites = repository.favoriteStops.first { it.isNotEmpty() }
            assertEquals(1, favorites.size)
            assertEquals("60010", favorites[0].id)
        }

    @Test
    fun `line variant toggle identity is provider lineId id shapeId`() =
        runTest {
            val repository = FavoritesRepository(dataStore(backgroundScope), backgroundScope)
            val variant =
                LineVariant(
                    provider = Provider.OSETh,
                    lineId = "01",
                    id = "01_7429_1_3",
                    shapeId = "s1",
                    label = "ΕΚΕΙ",
                    lineShortName = "01",
                )
            val sameRoute = variant.copy(label = "ΑΛΛΟ ΟΝΟΜΑ") // same identity, different label
            val otherRoute = variant.copy(id = "01_7429_2_3") // the other direction

            repository.toggle(variant)
            repository.favoriteLines.first { it.isNotEmpty() }

            // Same route under a different label toggles OFF (isSameRoute).
            repository.toggle(sameRoute)
            assertTrue(repository.favoriteLines.first { it.isEmpty() }.isEmpty())

            repository.toggle(variant)
            repository.toggle(otherRoute)
            assertEquals(2, repository.favoriteLines.first { it.size == 2 }.size)
        }
}
