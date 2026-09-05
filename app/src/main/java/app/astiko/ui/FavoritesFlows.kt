package app.astiko.ui

import app.astiko.data.model.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Favorites of one provider as a StateFlow, for the screens' provider-
 * scoped sections (arrivals heart, pinned lines, row hearts). Seeded
 * from the store's warmed value (the DataStore read starts at app
 * start), so the section is correct on the first frame.
 */
fun <T> StateFlow<List<T>>.filteredByProvider(
    provider: Provider,
    scope: CoroutineScope,
    providerOf: (T) -> Provider,
): StateFlow<List<T>> =
    map { favorites -> favorites.filter { providerOf(it) == provider } }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            value.filter { providerOf(it) == provider },
        )
