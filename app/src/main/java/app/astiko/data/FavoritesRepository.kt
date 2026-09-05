package app.astiko.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Stop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/*
 * Favorites persisted locally with DataStore.
 * Stops are stored as JSON so they show offline without a fetch.
 * Line favorites are direction-level LineVariant snapshots (key "lines"),
 * complete enough to open the direction screen without a fetch.
 */

interface FavoritesStore {
    val favoriteStops: StateFlow<List<Stop>>

    suspend fun toggle(stop: Stop)

    suspend fun replace(stop: Stop)

    val favoriteLines: StateFlow<List<LineVariant>>

    suspend fun toggle(variant: LineVariant)
}

internal val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

class FavoritesRepository(
    private val dataStore: DataStore<Preferences>,
) : FavoritesStore {
    // Shared eagerly from app start so the first UI frame already knows the
    // stored favorites. A lazy read would land after the home screen composed
    // and the empty list would pop in late.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Lenient per-entry decoding. Unknown keys or providers drop only the
    // unreadable entries, never the whole favorites list.
    private val json = Json { ignoreUnknownKeys = true }

    private val stopsKey = stringPreferencesKey("stops")
    private val linesKey = stringPreferencesKey("lines")

    override val favoriteStops: StateFlow<List<Stop>> =
        dataStore.data
            .map { prefs -> prefs[stopsKey]?.let { decode(it, Stop.serializer()) } ?: emptyList() }
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override suspend fun toggle(stop: Stop) {
        toggle(
            key = stopsKey,
            serializer = Stop.serializer(),
            same = { a, b -> a.id == b.id && a.provider == b.provider },
            value = stop,
        )
    }

    /** Re-save a favorite in place. Persists enriched serving lines for next launch. */
    override suspend fun replace(stop: Stop) {
        dataStore.edit { prefs ->
            val current = prefs[stopsKey]?.let { decode(it, Stop.serializer()) } ?: return@edit
            val next =
                current.map {
                    if (it.id == stop.id && it.provider == stop.provider) stop else it
                }
            prefs[stopsKey] = json.encodeToString(ListSerializer(Stop.serializer()), next)
        }
    }

    override val favoriteLines: StateFlow<List<LineVariant>> =
        dataStore.data
            .map { prefs ->
                prefs[linesKey]?.let { decode(it, LineVariant.serializer()) } ?: emptyList()
            }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override suspend fun toggle(variant: LineVariant) {
        toggle(
            key = linesKey,
            serializer = LineVariant.serializer(),
            same = { a, b -> a.isSameRoute(b) },
            value = variant,
        )
    }

    /** One read-modify-write for both favorite kinds; the identity predicate
     *  and the serializer are the only differences. */
    private suspend fun <T> toggle(
        key: Preferences.Key<String>,
        serializer: KSerializer<T>,
        same: (T, T) -> Boolean,
        value: T,
    ) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { decode(it, serializer) } ?: emptyList()
            val next =
                if (current.any { same(it, value) }) {
                    current.filterNot { same(it, value) }
                } else {
                    current + value
                }
            prefs[key] = json.encodeToString(ListSerializer(serializer), next)
        }
    }

    /** Lenient per-entry decoding: one unreadable entry drops only itself,
     *  unknown keys or providers never lose the whole list. */
    private fun <T> decode(
        raw: String,
        serializer: KSerializer<T>,
    ): List<T> {
        val array =
            runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
                ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
        }
    }
}
