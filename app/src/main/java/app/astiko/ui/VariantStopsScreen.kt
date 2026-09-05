package app.astiko.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.astiko.R
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Stop

/** Ordered stops of one direction. Tap a stop to see its live arrivals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariantStopsScreen(
    variant: LineVariant,
    onBack: () -> Unit,
    onStopClick: (Stop) -> Unit,
    onTimetableClick: ((LineVariant) -> Unit)? = null,
    viewModel: VariantStopsViewModel =
        viewModel(
            key = "stops-${variant.provider}-${variant.id}",
            factory = VariantStopsViewModel.factory(variant),
        ),
) {
    val stops by viewModel.stops.collectAsState()
    val geometry by viewModel.geometry.collectAsState()
    val vehicles by viewModel.vehicles.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val favorites by viewModel.favoriteStops.collectAsState()
    val favoriteLines by viewModel.favoriteLines.collectAsState()
    val activeVariant by viewModel.activeVariant.collectAsState()
    val variants by viewModel.variants.collectAsState()
    val isFavoriteLine = favoriteLines.any { it.isSameRoute(activeVariant) }
    // The swap target when the line has exactly two directions. Use
    // firstOrNull, not first. OSETh can report two identical snapshots
    // (same routeId + shapeId + headsign for distinct services), and with
    // no real "other" the sheet is the graceful fallback. A plain first{}
    // would crash with NoSuchElementException on every swap tap.
    val other =
        if (variants.size ==
            2
        ) {
            variants.firstOrNull { !it.isSameRoute(activeVariant) }
        } else {
            null
        }
    // Direction switcher sheet. The same list as the Lines tab's, with a ✓
    // on the shown direction (lines with more than two directions).
    var showDirectionSheet by remember { mutableStateOf(false) }
    // The stop selected on the map (pin tap). The list below scrolls to
    // it and highlights the row. Lifted here so the card's ✕/map-tap
    // clears both together, the map card and the list share one state.
    var selectedStop by remember { mutableStateOf<Stop?>(null) }
    // LazyListState keyed by the variant. A direction switch must open the
    // new direction's list at the top. Keeping the previous direction's
    // scroll offset would clamp to the end/middle when the lists differ
    // in length. Same-routeId variants (OSETh weekday/weekend shapes, the
    // same stop list) keep their position.
    val listState = remember(activeVariant.id) { LazyListState() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        // The title carries the swap target as a tappable
                        // subtitle ("Προς Κ.Τ.Ε.Λ."), so the swap is never
                        // blind. Lines with more than two directions open
                        // the sheet instead.
                        if (variants.size > 1) {
                            val otherDestination = other?.let { directionDestination(it.label) }
                            val currentDestination = directionDestination(activeVariant.label)
                            val subtitle =
                                when {
                                    otherDestination != null &&
                                        otherDestination != currentDestination -> {
                                        stringResource(R.string.towards) + " " + otherDestination
                                    }

                                    other != null -> {
                                        other.label
                                    }

                                    else -> {
                                        stringResource(R.string.choose_direction)
                                    }
                                }
                            Column {
                                Text(
                                    activeVariant.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    Modifier.clickable {
                                        if (other !=
                                            null
                                        ) {
                                            viewModel.switchVariant(other)
                                        } else {
                                            showDirectionSheet =
                                                true
                                        }
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Text(
                                activeVariant.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        // Per-direction schedule, hidden until a provider
                        // reports supportsLineTimetable (CityBus has no
                        // per-line endpoint).
                        if (viewModel.supportsTimetable) {
                            onTimetableClick?.let { click ->
                                IconButton(onClick = { click(activeVariant) }) {
                                    Icon(
                                        Icons.Filled.DateRange,
                                        contentDescription = stringResource(R.string.schedule),
                                    )
                                }
                            }
                        }
                        // The swap action flips to the other direction
                        // directly. Lines with more than two directions (or
                        // duplicate snapshots with no real "other") open the
                        // sheet instead.
                        if (variants.size > 1) {
                            IconButton(onClick = {
                                if (other != null) {
                                    viewModel.switchVariant(other)
                                } else {
                                    showDirectionSheet = true
                                }
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_swap_direction),
                                    contentDescription = stringResource(R.string.swap_direction),
                                )
                            }
                        }
                        // Favorite the direction. The top bar heart mirrors the
                        // arrivals screen's stop heart, unfavoriting happens
                        // here too, and the pinned list has no heart.
                        FavoriteIcon(
                            isFavorite = isFavoriteLine,
                            onClick = { viewModel.toggleFavoriteLine(activeVariant) },
                            // Same 40.dp state layer as the calendar's IconButton
                            // (48.dp reserved for the touch target).
                            modifier = Modifier.minimumInteractiveComponentSize().size(40.dp),
                        )
                    },
                )
                OfflineBanner(variant.provider)
            }
        },
    ) { padding ->
        // Crossfade between loading / error / empty / content, so the
        // spinner does not cut straight into the map card.
        AnimatedContent(
            targetState =
                when {
                    loading -> VariantStopsScreenState.LOADING
                    error != null -> VariantStopsScreenState.ERROR
                    stops.isEmpty() -> VariantStopsScreenState.EMPTY
                    else -> VariantStopsScreenState.CONTENT
                },
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "variant-stops-state",
        ) { state ->
            when (state) {
                VariantStopsScreenState.LOADING -> {
                    Row(
                        Modifier.fillMaxSize().padding(padding).padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }

                VariantStopsScreenState.ERROR -> {
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.load_stops_failed),
                        body = error ?: stringResource(R.string.unknown_error),
                        error = true,
                        center = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = viewModel::retry,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }

                VariantStopsScreenState.EMPTY -> {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = stringResource(R.string.no_stops_in_direction),
                        center = true,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }

                VariantStopsScreenState.CONTENT -> {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        // Live line map. Route polyline plus stop pins plus
                        // buses in transit, refreshed with the same 15 s poll.
                        LineMapCard(
                            variant = activeVariant,
                            geometry = geometry,
                            stops = stops,
                            vehicles = vehicles,
                            onStopClick = onStopClick,
                            selectedStop = selectedStop,
                            onSelectStop = { selectedStop = it },
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    horizontal = 16.dp,
                                    vertical = 8.dp,
                                ),
                        )
                        LazyColumn(Modifier.weight(1f), state = listState) {
                            // A route may visit the same stop twice (loops).
                            // The index keeps keys unique.
                            itemsIndexed(stops, key = {
                                index,
                                s,
                                ->
                                "${s.id}-$index"
                            }) { index, stop ->
                                // Map-tap highlight: soft blue wash, rounded like
                                // the row. Clip before the background so the
                                // corners stay round. Clears with the card
                                // (✕ / map tap).
                                val selected = stop.id == selectedStop?.id
                                TwoLineRow(
                                    onClick = { onStopClick(stop) },
                                    modifier =
                                        Modifier
                                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                            .then(
                                                if (selected) {
                                                    Modifier
                                                        .clip(ListRowShape)
                                                        .background(
                                                            MaterialTheme.colorScheme.primaryContainer
                                                                .copy(
                                                                    alpha = 0.4f,
                                                                ),
                                                        )
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    headline = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            SmallBadge(
                                                "${index + 1}",
                                                color =
                                                    routeEndRole(stops, stop.id)
                                                        .badgeArgb()
                                                        ?.let { Color(it) },
                                            )
                                            Text(
                                                stop.name,
                                                Modifier.padding(start = 12.dp),
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    supporting =
                                        stop.street?.let { street ->
                                            {
                                                Text(
                                                    street,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                    trailing = {
                                        val isFavorite = favorites.any { it.id == stop.id }
                                        FavoriteIcon(
                                            isFavorite = isFavorite,
                                            onClick = { viewModel.toggleFavorite(stop) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Map-pin to list sync: scroll the tapped stop into view. Loop routes
    // visit a stop twice, so scrollTargetIndex resolves the first
    // occurrence. A stop already visible is left alone (no re-scroll on
    // every tap).
    LaunchedEffect(selectedStop?.id, stops) {
        val id = selectedStop?.id ?: return@LaunchedEffect
        val index = scrollTargetIndex(stops, id)
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }

    // A direction switch swaps the whole stop list. The old stop's
    // selection (card + highlight) must not linger on a foreign route.
    LaunchedEffect(activeVariant) { selectedStop = null }

    // Direction switcher sheet: the same sheet as the initial route pick
    // (line badge, loading/error states, fresh fetch per visit), with a ✓
    // on the direction currently shown. Used when a line has more than
    // two directions.
    if (showDirectionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDirectionSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ScopedViewModelStore {
                LineVariantsSheet(
                    line = viewModel.parentLine,
                    onPick = { v ->
                        showDirectionSheet = false
                        viewModel.switchVariant(v)
                    },
                    favorites = favoriteLines,
                    onToggleFavorite = viewModel::toggleFavoriteLine,
                    selectedVariant = activeVariant,
                )
            }
        }
    }
}

private enum class VariantStopsScreenState { LOADING, ERROR, EMPTY, CONTENT }
