package app.astiko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.astiko.R
import app.astiko.data.model.City
import app.astiko.data.model.Stop
import kotlinx.coroutines.delay

/**
 * Dedicated stop-search drill-in screen (city-picker pattern: back arrow
 * + auto-focused search field + results). Empty query shows a prompt.
 * Results are the same clustered rows as the nearby list (twins across
 * the road merge, tap a cluster to pick the direction). A tap on a row
 * opens the arrivals screen, same as every other stop row.
 *
 * The ViewModel comes from the root's search session, not from this
 * entry's store, so an arrivals round-trip keeps the query, results
 * and scroll. The screen auto-focuses only when the query is empty:
 * a fresh search wants the keyboard, a restored one does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopSearchScreen(
    city: City,
    viewModel: StopSearchViewModel,
    onBack: () -> Unit,
    onStopClick: (Stop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val favorites by viewModel.favoriteStops.collectAsState()
    // The chooser opens through the IME gate (ImeGatedSheet.kt): the row
    // tap drops focus, and the sheet is composed only after the keyboard
    // is fully gone. Composing it immediately slid the sheet up behind
    // the still-visible keyboard.
    val chooserGate = rememberImeGatedSheet<List<Stop>>()

    // The first catalog build (Thessaloniki, 4 pages) takes a while. The
    // spinner alone looks hung, so after a short wait it gets a hint that
    // the stop list is downloading (first time. Subsequent searches are
    // instant from the disk cache).
    var showSlowHint by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is StopSearchViewModel.SearchState.Loading) {
            delay(SLOW_HINT_DELAY_MS)
            showSlowHint = true
        } else {
            showSlowHint = false
        }
    }

    // Clusters computed outside the LazyColumn DSL (remember needs a composable scope).
    val clusters =
        remember(
            state,
        ) {
            (state as? StopSearchViewModel.SearchState.Ready)
                ?.let { clusterStops(it.stops) }
                ?: emptyList()
        }

    // The field takes focus on open, but only when the query is empty:
    // a fresh search exists to type, a restored session exists to browse.
    // (The keyboard is already closed by the time you return from
    // arrivals. Popping it over the results was the annoying part.)
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (query.isEmpty()) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // Same slot as every other drill-in screen: the banner sits
            // under the top bar (offline search serves the cached catalog,
            // so the screen must say so instead of silently working).
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.stop_search_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                // A pending request must not open the
                                // sheet over the exiting screen.
                                chooserGate.dismiss()
                                onBack()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
                OfflineBanner(city.provider)
            }
        },
    ) { padding ->
        Column(
            // Edge-to-edge + adjustResize does not resize Compose content
            // for the IME: without imePadding the screen kept its full
            // height, so the keyboard covered the centered prompt (and
            // the icon) on tall keyboards. imePadding shrinks the content
            // area by the keyboard height. Visible content is everything
            // above the keys, and the full area returns when it closes.
            // The navbar is consumed first because the IME inset already
            // contains the navbar region when the keyboard is up. Padding
            // by both leaves an empty navbar-sized strip above the keys.
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(WindowInsets.navigationBars)
                .imePadding(),
        ) {
            // Same tonal pill as the city picker and the Γραμμές search.
            TextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.stop_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
            )

            when (val s = state) {
                is StopSearchViewModel.SearchState.Idle -> {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.stop_search_prompt),
                        modifier = Modifier.weight(1f),
                        center = true,
                    )
                }

                is StopSearchViewModel.SearchState.Loading -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        if (showSlowHint) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.stop_search_loading_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                is StopSearchViewModel.SearchState.Error -> {
                    // Same error styling as the other screens: warning icon in
                    // the error container, raw ViewModel message with the
                    // resource fallback, retry action.
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.load_stops_failed),
                        body = s.message ?: stringResource(R.string.unknown_error),
                        error = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = viewModel::retry,
                        modifier = Modifier.weight(1f),
                        center = true,
                    )
                }

                is StopSearchViewModel.SearchState.Ready -> {
                    if (clusters.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.no_stop_matches),
                            modifier = Modifier.weight(1f),
                            center = true,
                        )
                    } else {
                        LazyColumn(state = viewModel.listState, modifier = Modifier.weight(1f)) {
                            items(clusters, key = { it.first().id }) { cluster ->
                                StopClusterRow(
                                    cluster = cluster,
                                    onClick = {
                                        if (cluster.size == 1) {
                                            // Direct push, no dialog: dropping
                                            // focus now is safe, the keyboard
                                            // collapses once.
                                            focusManager.clearFocus()
                                            onStopClick(cluster.first())
                                        } else {
                                            chooserGate.request(cluster)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Same chooser as the nearby list: pick the direction, or heart a
    // specific stop. Same-name twins across the road merge into the row.
    chooserGate.shown?.let { cluster ->
        ModalBottomSheet(
            onDismissRequest = { chooserGate.dismiss() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            StopChooserSheet(
                cluster = cluster,
                favorites = favorites,
                onToggleFavorite = viewModel::toggleFavorite,
                onPick = {
                    // Clear again while the dialog still holds focus.
                    // Nothing re-shows the IME.
                    focusManager.clearFocus()
                    onStopClick(it)
                    chooserGate.dismiss()
                },
            )
        }
    }
}

/** While the first catalog build is running (Thessaloniki: 4 pages), the
 *  spinner alone reads as "hung". After this the hint appears below it.
 *  Short enough that CityBus's instant local search never shows it. */
private const val SLOW_HINT_DELAY_MS = 1_500L
