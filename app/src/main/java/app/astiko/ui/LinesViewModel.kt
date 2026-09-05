package app.astiko.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.FavoritesStore
import app.astiko.data.TransitRepository
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class LinesViewModel(
    private val provider: Provider,
    private val repository: TransitRepository,
    private val favoritesRepository: FavoritesStore,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Scroll of the list, kept with the session like the stop search's
     *  ([StopSearchViewModel.listState]): a drill-in round-trip rebuilds
     *  the composable, and the position must not snap back to the top. */
    val listState = LazyListState()

    /** The lines fetch is one-shot; a session-scoped instance holds it
     *  across tab switches and drill-ins, so returning never refetches. */
    private val lines = MutableStateFlow<List<Line>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Favorite directions of this provider, for the pinned section. Seeded
     *  from the repository's warmed value (the DataStore read starts at app
     *  start), so the section is correct from the first frame. */
    val favoriteLines: StateFlow<List<LineVariant>> =
        favoritesRepository.favoriteLines
            .filteredByProvider(provider, viewModelScope) { it.provider }

    val filteredLines: StateFlow<List<Line>> =
        combine(lines, _query) { lines, q -> filter(lines, q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun clearQuery() {
        _query.value = ""
    }

    fun toggleFavoriteLine(variant: LineVariant) {
        viewModelScope.launch { favoritesRepository.toggle(variant) }
    }

    fun retry() = load()

    private fun load() {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runCatchingNotCancelled { repository.getLines() }
                .onSuccess {
                    lines.value = it
                    _loading.value = false
                }.onFailure { e ->
                    _error.value = e.message
                    _loading.value = false
                }
        }
    }

    private fun filter(
        lines: List<Line>,
        q: String,
    ): List<Line> {
        // Locale.ROOT, since locale-sensitive uppercasing (Turkish "i" becomes
        // "İ") would make "1" queries miss line "1" matches in Greek names.
        val query = q.trim().uppercase(Locale.ROOT)
        if (query.isEmpty()) return lines
        return lines.filter {
            it.shortName.uppercase(Locale.ROOT).contains(query) ||
                it.longName.uppercase(Locale.ROOT).contains(query)
        }
    }

    companion object {
        fun factory(provider: Provider): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    LinesViewModel(
                        provider,
                        app.container.repository(provider),
                        app.container.favoritesRepository,
                    )
                }
            }
    }
}

class LineVariantsViewModel(
    private val repository: TransitRepository,
    private val line: Line,
) : ViewModel() {
    private val _variants = MutableStateFlow<List<LineVariant>>(emptyList())
    val variants: StateFlow<List<LineVariant>> = _variants.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            runCatchingNotCancelled { repository.getLineVariants(line) }
                .onSuccess {
                    _variants.value = it
                    _loading.value = false
                }.onFailure { e ->
                    _error.value = e.message
                    _loading.value = false
                }
        }
    }

    companion object {
        fun factory(line: Line): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    LineVariantsViewModel(app.container.repository(line.provider), line)
                }
            }
    }
}
