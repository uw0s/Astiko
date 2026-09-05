package app.astiko.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.astiko.R
import app.astiko.data.model.City
import app.astiko.data.model.Line
import app.astiko.data.model.LineVariant

/**
 * Lines tab: every line of the selected city, searchable.
 * Tap a line to open a bottom sheet with its directions, consistent with
 * the cluster chooser and the city selector.
 *
 * The ViewModel comes from the root's search session (same as the stop
 * search), so the query, results and scroll survive tab switches and
 * drill-in round-trips without re-fetching the lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinesScreen(
    city: City,
    viewModel: LinesViewModel,
    onVariantClick: (LineVariant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsState()
    val lines by viewModel.filteredLines.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val favoriteLines by viewModel.favoriteLines.collectAsState()
    // Same gate as the stop search chooser: the row tap drops focus, the
    // sheet opens once the keyboard is gone.
    val sheetGate = rememberImeGatedSheet<Line>()

    // Clearing the query re-inserts the favorites above the first visible
    // row. That row stays anchored, so the favorites land scrolled out of
    // view. Snap back to the top for the browse view.
    val listState = viewModel.listState
    LaunchedEffect(query.isEmpty()) {
        if (query.isEmpty()) listState.scrollToItem(0)
    }

    // Focus is dropped on direct pushes (favorite rows) and in the pick
    // handler. The sheet gate drops it on tap before opening.
    val focusManager = LocalFocusManager.current

    fun dropFocus() {
        focusManager.clearFocus()
    }

    Column(modifier.fillMaxSize()) {
        // Filled tonal pill, the Material 3 search pattern
        TextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.line_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearQuery) {
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

        when {
            // Search found nothing. Same centered empty state as the city picker.
            // The block sits mid-way down the space below the search bar.
            query.isNotEmpty() && !loading && error == null && lines.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.no_lines_match),
                    modifier = Modifier.weight(1f),
                    center = true,
                )
            }

            else -> {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    // Pinned favorites between the search and the results, hidden
                    // while searching. The section stays usable even when the
                    // lines fetch below it fails.
                    if (query.isEmpty()) {
                        item(key = "favorites-header") {
                            Text(
                                stringResource(R.string.favorite_lines),
                                Modifier.padding(
                                    start = 16.dp,
                                    end = 8.dp,
                                    top = 16.dp,
                                    bottom = 4.dp,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (favoriteLines.isEmpty()) {
                            // Same empty state as the stops tab's favorites
                            // section. Icon + title only, no body.
                            item(key = "favorites-empty") {
                                EmptyState(
                                    icon = Icons.Filled.FavoriteBorder,
                                    title = stringResource(R.string.no_favorite_lines),
                                )
                            }
                        } else {
                            // One row per favorited direction, tap goes straight
                            // to its stops screen (no sheet). Keys: the favorite
                            // identity is provider + lineId + id + shapeId, and
                            // the index is always appended. A null-shapeId variant
                            // at index 3 would otherwise collide with a
                            // shapeId="3" variant at index 0 ("Key was already
                            // used" crash).
                            itemsIndexed(favoriteLines, key = { index, variant ->
                                "fav-${variant.provider}-${variant.lineId}-${variant.id}-${variant.shapeId ?: ""}-$index"
                            }) { _, variant ->
                                ListItem(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            LineBadge(variant.lineShortName)
                                            Text(
                                                variant.label,
                                                Modifier.padding(start = 12.dp),
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .clip(
                                                ListRowShape,
                                            ).clickable {
                                                dropFocus()
                                                onVariantClick(variant)
                                            },
                                    trailingContent = { RowChevron() },
                                )
                            }
                        }
                    }

                    when {
                        loading -> {
                            item(key = "lines-loading") {
                                Row(
                                    Modifier.fillMaxWidth().padding(24.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) { CircularProgressIndicator() }
                            }
                        }

                        error != null -> {
                            item(key = "lines-error") {
                                EmptyState(
                                    icon = Icons.Filled.Warning,
                                    title = stringResource(R.string.load_lines_failed),
                                    body = error ?: stringResource(R.string.unknown_error),
                                    error = true,
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = viewModel::retry,
                                )
                            }
                        }

                        // Only reachable with an empty query, since searching with
                        // no results is caught by the centered state above. A city
                        // with no lines at all, shown under the favorites hint.
                        lines.isEmpty() -> {
                            item(key = "lines-empty") {
                                EmptyState(
                                    icon = Icons.Filled.Search,
                                    title = stringResource(R.string.no_lines_match),
                                )
                            }
                        }

                        else -> {
                            // Section separator between the pinned favorites and the
                            // full list, hidden while searching. Filtered results
                            // sit right under the pill. Same header style as the
                            // stops tab's section headers.
                            if (query.isEmpty()) {
                                item(key = "all-lines-header") {
                                    Text(
                                        stringResource(R.string.all_lines),
                                        Modifier.padding(
                                            start = 16.dp,
                                            end = 8.dp,
                                            top = 16.dp,
                                            bottom = 4.dp,
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                            // line_code is not unique in OASA's master lines. One
                            // internal code covers several public lines (938 =
                            // 040/550/Α2), so id alone is an unsafe LazyColumn key.
                            // A fast fling that composes two same-code rows in one
                            // pass crashes with "Key was already used". The public
                            // number plus index keeps keys unique.
                            itemsIndexed(lines, key = {
                                index,
                                line,
                                ->
                                "${line.id}-${line.shortName}-$index"
                            }) { index, line ->
                                ListItem(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            LineBadge(line.shortName)
                                            Text(
                                                line.longName,
                                                Modifier.padding(start = 12.dp),
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .animateItem(
                                                fadeInSpec = null,
                                                fadeOutSpec = null,
                                            ).clip(ListRowShape)
                                            .clickable {
                                                sheetGate.request(line)
                                            },
                                    trailingContent = { RowChevron() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Directions sheet: same content-sized open as the stop chooser. A
    // line with many routes is taller than the half-screen resting position.
    // Stock gestures: the variant list scrolls freely, a swipe past its top
    // collapses the sheet, the pill/fixed header drag and scrim tap dismiss.
    sheetGate.shown?.let { line ->
        ModalBottomSheet(
            onDismissRequest = { sheetGate.dismiss() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            // Own ViewModel store per sheet visit: a reopened sheet fetches
            // fresh directions in the current language instead of reusing
            // the previous visit's (stale, possibly old-language) ones.
            ScopedViewModelStore {
                LineVariantsSheet(
                    line = line,
                    onPick = { variant ->
                        // Clear while the dialog still holds window focus.
                        // Nothing re-shows the IME.
                        focusManager.clearFocus()
                        sheetGate.dismiss()
                        onVariantClick(variant)
                    },
                    favorites = favoriteLines,
                    onToggleFavorite = viewModel::toggleFavoriteLine,
                )
            }
        }
    }
}

/** Bottom sheet with the directions of a line, like the cluster chooser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineVariantsSheet(
    line: Line,
    onPick: (LineVariant) -> Unit,
    favorites: List<LineVariant> = emptyList(),
    onToggleFavorite: (LineVariant) -> Unit = {},
    selectedVariant: LineVariant? = null,
    viewModel: LineVariantsViewModel =
        viewModel(
            key = "variants-${line.provider}-${line.id}",
            factory = LineVariantsViewModel.factory(line),
        ),
) {
    val variants by viewModel.variants.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineBadge(line.shortName)
            Text(
                line.longName,
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(R.string.choose_direction),
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            error != null -> {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.load_directions_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                }
            }

            else -> {
                if (variants.isEmpty()) {
                    // A genuinely dead line (suspended, no routes in the API):
                    // say so instead of a blank sheet.
                    Text(
                        stringResource(R.string.no_directions),
                        Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LineVariantsSheetContent(
                        line = line,
                        variants = variants,
                        onPick = onPick,
                        favorites = favorites,
                        onToggleFavorite = onToggleFavorite,
                        selectedVariant = selectedVariant,
                    )
                }
            }
        }
    }
}

/**
 * The direction list itself: no fetching, no ViewModel. Shared by
 * [LineVariantsSheet] (Lines tab) and the VariantStopsScreen direction
 * switcher, which already holds its variants in its own ViewModel.
 * A ✓ marks the currently shown direction when [selectedVariant] is set.
 */
@Composable
fun LineVariantsSheetContent(
    line: Line,
    variants: List<LineVariant>,
    onPick: (LineVariant) -> Unit,
    favorites: List<LineVariant> = emptyList(),
    onToggleFavorite: (LineVariant) -> Unit = {},
    selectedVariant: LineVariant? = null,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        // Duplicate routeIds exist (e.g. weekday/weekend shapes), so the
        // key must include shapeId, and the index is always appended: two
        // headsigns sharing a non-null (routeId, shapeId) would otherwise
        // collide ("Key was already used").
        itemsIndexed(variants, key = {
            index,
            v,
            ->
            "${v.id}-${v.shapeId ?: "nov"}-$index"
        }) { _, variant ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ListRowShape)
                    .clickable { onPick(variant) }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedVariant?.isSameRoute(variant) == true) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.current_direction),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    variant.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                // Heart a specific direction: the child clickable
                // wins over the row's onPick (same as the stop chooser).
                val isFavorite = favorites.any { it.isSameRoute(variant) }
                FavoriteIcon(
                    isFavorite = isFavorite,
                    onClick = { onToggleFavorite(variant) },
                )
            }
        }
    }
}
