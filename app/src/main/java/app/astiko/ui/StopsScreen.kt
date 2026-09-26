package app.astiko.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.astiko.R
import app.astiko.data.model.City
import app.astiko.data.model.Stop
import app.astiko.util.hasLocationPermission
import app.astiko.util.isAcrossRoad

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopsScreen(
    city: City,
    onStopClick: (Stop) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StopsViewModel =
        viewModel(
            key = "stops-${city.name}",
            factory = StopsViewModel.factory(city),
        ),
) {
    val nearby by viewModel.nearby.collectAsState()
    val favorites by viewModel.favoriteStops.collectAsState()
    val favoriteRows by viewModel.favoriteRows.collectAsState()
    var chooser by remember { mutableStateOf<List<Stop>?>(null) }
    val context = LocalContext.current

    // Clusters computed outside the LazyColumn DSL (remember needs a composable scope)
    val clusters =
        remember(nearby) {
            (nearby as? StopsViewModel.NearbyState.Ready)
                ?.let { clusterStops(it.stops) }
                ?: emptyList()
        }

    // Requested only on demand (the permission card in "Κοντά μου"), never
    // on screen open, so a manual city choice stays permission-free. Two
    // denials flip Android 11+ into "don't ask again", and the request
    // would then silently no-op forever. The card swaps its button for an
    // app-settings shortcut, detected via shouldShowRequestPermissionRationale.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val activity = context as? Activity
            permanentlyDenied = !granted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            viewModel.onLocationPermissionResult(granted)
        }

    // On open, load nearby immediately if permission is already granted.
    // Never auto-ask, the card's button does that. FINE or COARSE counts,
    // since Android 12+ "Approximate location" grants only COARSE.
    LaunchedEffect(city) {
        if (hasLocationPermission(context)) viewModel.onLocationPermissionResult(true)
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.favorites)) {} }

        when {
            // The DataStore is warmed at app start and the ViewModel seeds
            // from its current value, so the section is correct on the first
            // frame. Onboarding users have no favorites by definition, and
            // returning users' list is already in memory.
            favorites.isEmpty() -> {
                item {
                    EmptyState(
                        icon = Icons.Filled.FavoriteBorder,
                        title = stringResource(R.string.no_favorites),
                    )
                }
            }

            favoriteRows.isEmpty() -> {
                // Rows are emitted only with their badges (one-time fetch,
                // then persisted). Don't flash a badge-less list.
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            else -> {
                // Same card as the nearby list. A favorite is a
                // single-stop cluster (same title style, badges). Unfavorite
                // happens on the arrivals screen (top-bar heart).
                items(favoriteRows, key = { it.provider.name + it.id }) { stop ->
                    StopClusterRow(
                        cluster = listOf(stop),
                        onClick = { onStopClick(stop) },
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            SectionHeader(stringResource(R.string.nearby)) {
                IconButton(onClick = { viewModel.refreshNearby(force = true) }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                    )
                }
            }
        }

        when (val state = nearby) {
            is StopsViewModel.NearbyState.Loading -> {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            is StopsViewModel.NearbyState.NeedsPermission -> {
                item {
                    PermissionCard(
                        onRequest = {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onOpenSettings =
                            if (permanentlyDenied) {
                                {
                                    // No deep link to the per-app permission page
                                    // exists for third-party apps.
                                    // MANAGE_APP_PERMISSIONS is gated by the
                                    // system-only GRANT_RUNTIME_PERMISSIONS, and
                                    // APP_PERMISSION_DETAILS_SETTINGS was dropped
                                    // from Android 16's permission controller.
                                    // App info > Permissions is the closest public
                                    // destination (one tap).
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            }

            is StopsViewModel.NearbyState.Error -> {
                item {
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.load_stops_failed),
                        body = state.message ?: stringResource(R.string.unknown_error),
                        error = true,
                    )
                }
            }

            is StopsViewModel.NearbyState.Ready -> {
                when {
                    state.locationUnavailable -> {
                        item {
                            // No GPS fix. Show a hint, not a fake "nearby"
                            // list from the city center. Styled like the
                            // permission card, same failure family, same card
                            // style. The button jumps to the system location
                            // settings (master switch), and GPS tracking picks the
                            // fix up automatically on return.
                            LocationStateCard(
                                icon = Icons.Filled.LocationOn,
                                title = stringResource(R.string.location_unavailable),
                                body = stringResource(R.string.location_unavailable_hint),
                                actionLabel = stringResource(R.string.open_location_settings),
                                onAction = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                    )
                                },
                            )
                        }
                    }

                    clusters.isEmpty() -> {
                        item {
                            EmptyState(
                                icon = Icons.Filled.LocationOn,
                                title = stringResource(R.string.no_stops_nearby),
                            )
                        }
                    }

                    else -> {
                        items(clusters, key = { it.first().id }) { cluster ->
                            StopClusterRow(
                                cluster = cluster,
                                onClick = {
                                    if (cluster.size == 1) {
                                        onStopClick(cluster.first())
                                    } else {
                                        chooser = cluster
                                    }
                                },
                                modifier =
                                    Modifier.animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    // Chooser: same-name / same-place stops, pick the direction you want.
    // Opened content-sized (skipPartiallyExpanded). A 5+ stop cluster is
    // taller than the sheet's half-screen resting position, so opening
    // partially would clip the last rows until the user drags.
    chooser?.let { cluster ->
        ModalBottomSheet(
            onDismissRequest = { chooser = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            StopChooserSheet(
                cluster = cluster,
                favorites = favorites,
                onToggleFavorite = viewModel::toggleFavorite,
                onPick = {
                    onStopClick(it)
                    chooser = null
                },
            )
        }
    }
}

/**
 * One row per "place": one stop, or a group of twins across the road.
 * Shows distances and route badges. Tapping a
 * group opens the chooser. The heart lives in the chooser (per stop) and
 * on the arrivals screen, not here.
 */
@Composable
internal fun StopClusterRow(
    cluster: List<Stop>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ref = cluster.first()
    val names = cluster.map { it.name }.distinct()
    val distances = cluster.mapNotNull { it.distanceKm }
    val badges = cluster.flatMap { it.servingLines }.distinct()

    TwoLineRow(
        onClick = onClick,
        modifier = modifier,
        headline = {
            Text(
                names.joinToString(" / "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supporting = {
            Column {
                val sub =
                    listOfNotNull(
                        ref.street,
                        distances.joinToString("/") { formatDistance(it) },
                    ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    RouteBadges(badges)
                }
            }
        },
        trailing = {
            RowChevron()
        },
    )
}

/**
 * Bottom sheet listing every stop of a cluster. Pick the one you mean,
 * or heart a specific direction. */
@Composable
internal fun StopChooserSheet(
    cluster: List<Stop>,
    favorites: List<Stop>,
    onToggleFavorite: (Stop) -> Unit,
    onPick: (Stop) -> Unit,
) {
    val sorted = cluster.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
    val ref = sorted.first()

    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ref.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            SmallBadge(pluralStringResource(R.plurals.stops_count, sorted.size, sorted.size))
        }
        Text(
            stringResource(R.string.choose_direction_hint),
            Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Bounded scrollable list (same pattern as the line direction
        // sheet). A 5+-stop cluster must not push the sheet past its
        // partial height and clip the last rows, the list scrolls
        // internally instead.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            itemsIndexed(sorted, key = { index, stop -> "${stop.id}-$index" }) { index, stop ->
                val across = index > 0 && isAcrossRoad(ref, stop)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ListRowShape)
                        .clickable { onPick(stop) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (stop.name != ref.name) stop.name else (stop.street ?: stop.name),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Search clusters carry no distance, and only the
                        // across-the-road twin gets a label: an empty join
                        // must not render a blank line under the name.
                        val subtitle =
                            listOfNotNull(
                                stop.distanceKm?.let(::formatDistance),
                                if (across) stringResource(R.string.across) else null,
                            ).joinToString(" · ")
                        if (subtitle.isNotEmpty()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (stop.servingLines.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            RouteBadges(stop.servingLines)
                        }
                    }
                    // Heart a specific stop (direction). The child clickable
                    // wins over the row's onPick.
                    val isFavorite = favorites.any { it.id == stop.id }
                    FavoriteIcon(
                        isFavorite = isFavorite,
                        onClick = { onToggleFavorite(stop) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteBadges(
    shortNames: List<String>,
    max: Int = 5,
) {
    val shown = shortNames.take(max)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { Badge(it) }
        if (shortNames.size > max) Badge("+${shortNames.size - max}")
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** One card for the "Κοντά μου" location failures: explanation plus a
 *  filled action button, shared by the permission-needed and the
 *  location-off states so both look identical. */
@Composable
private fun LocationStateCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** "Κοντά μου" without location permission: explanation + an allow
 *  button (or an app-settings shortcut once "don't ask again" kicked in). */
@Composable
private fun PermissionCard(
    onRequest: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val openSettings = onOpenSettings != null
    LocationStateCard(
        icon = Icons.Filled.LocationOn,
        title = stringResource(R.string.location_permission_title),
        body = stringResource(R.string.location_permission_body),
        actionLabel =
            stringResource(
                if (openSettings) R.string.open_settings else R.string.allow_location,
            ),
        onAction = if (openSettings) onOpenSettings else onRequest,
    )
}
