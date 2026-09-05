package app.astiko.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.astiko.R
import app.astiko.data.model.TimetableEntry
import app.astiko.util.compareLineShortNames
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The full-day schedule of a stop (or a line direction), grouped by line,
 * with a day-of-week chip row. One-time fetch per day. Schedules are
 * static, so unlike arrivals there is no polling (see
 * TimetableViewModel's per-day cache).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    target: TimetableTarget,
    onBack: () -> Unit,
    viewModel: TimetableViewModel =
        viewModel(
            key = "timetable-${target.key}",
            factory = TimetableViewModel.factory(target),
        ),
) {
    val selectedDay by viewModel.selectedDay.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    val title =
        when (target) {
            is TimetableTarget.StopTarget -> target.stop.name
            is TimetableTarget.LineVariantTarget -> target.variant.label
        }
    val isToday = selectedDay == LocalDate.now().dayOfWeek
    // The next-departure highlight and passed-trip dimming must follow the
    // wall clock while the screen stays open. A `now` frozen at composition
    // goes stale the instant a departure passes. A minute ticker keeps both
    // fresh, and re-evaluates isToday past midnight.
    var now by remember(selectedDay) { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(selectedDay) {
        while (true) {
            delay(60_000)
            now = LocalTime.now()
        }
    }

    // Grouped once per emission. Lines in natural order, times ascending.
    val groups =
        remember(entries) {
            entries
                .groupBy { it.lineShortName }
                .toList()
                .sortedWith { (a, _), (b, _) -> compareLineShortNames(a, b) }
                .map { (line, trips) -> line to trips.sortedBy { it.departureTime } }
        }
    val nextIndexByLine =
        remember(groups, isToday, now) {
            if (!isToday) {
                emptyMap()
            } else {
                groups
                    .mapNotNull { (line, trips) ->
                        nextDepartureIndex(trips, now)?.let { line to it }
                    }.toMap()
            }
        }

    // Chips row, ordered starting at the selected day (today, tomorrow,
    // ...). The selected chip is always first, so no initial scroll is
    // needed. The order is fixed for the screen's lifetime. Tapping a day
    // selects it in place without re-rotating the row.
    val dayList = remember { weekDaysStartingAt(LocalDate.now().dayOfWeek) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
                OfflineBanner(target.provider)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Timetables are per weekday, not per date.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(dayList, key = { it }) { day ->
                    FilterChip(
                        selected = day == selectedDay,
                        onClick = { viewModel.selectDay(day) },
                        label = { Text(dayLabel(day)) },
                    )
                }
            }

            // Crossfade between loading / error / empty / list, like the
            // other screens. The spinner doesn't cut into the timetable.
            AnimatedContent(
                targetState =
                    when {
                        loading -> TimetableScreenState.LOADING
                        error != null -> TimetableScreenState.ERROR
                        groups.isEmpty() -> TimetableScreenState.EMPTY
                        else -> TimetableScreenState.CONTENT
                    },
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                label = "timetable-state",
            ) { state ->
                when (state) {
                    TimetableScreenState.LOADING -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    TimetableScreenState.ERROR -> {
                        EmptyState(
                            icon = Icons.Filled.Warning,
                            title = stringResource(R.string.error_generic),
                            body = error ?: stringResource(R.string.unknown_error),
                            error = true,
                            center = true,
                            actionLabel = stringResource(R.string.retry),
                            onAction = viewModel::retry,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    TimetableScreenState.EMPTY -> {
                        EmptyState(
                            icon = Icons.Filled.DateRange,
                            title = stringResource(R.string.no_trips_this_day),
                            center = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    TimetableScreenState.CONTENT -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            groups.forEach { (line, trips) ->
                                item(key = "line-$line") {
                                    TimetableLineHeader(line, trips.first().lineName)
                                }
                                // A line can have two trips at the same minute (two
                                // routes of the same line), so the index keeps keys
                                // unique. Always index-suffixed. A duplicated
                                // non-null tripId would crash the LazyColumn.
                                itemsIndexed(trips, key = { index, t ->
                                    "${t.tripId ?: "$line-${t.departureTime}"}-$index"
                                }) { index, trip ->
                                    TimetableRow(
                                        trip = trip,
                                        isToday = isToday,
                                        now = now,
                                        // By index, not by time. Two trips at the
                                        // same minute must not both light up.
                                        isNext = index == nextIndexByLine[line],
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
            }
        }
    }
}

private enum class TimetableScreenState { LOADING, ERROR, EMPTY, CONTENT }

@Composable
private fun TimetableLineHeader(
    lineShortName: String,
    lineName: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(lineShortName)
        Spacer(Modifier.width(12.dp))
        Text(
            lineName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimetableRow(
    trip: TimetableEntry,
    isToday: Boolean,
    now: LocalTime,
    isNext: Boolean,
    modifier: Modifier = Modifier,
) {
    val time = runCatching { LocalTime.parse(trip.departureTime) }.getOrNull()
    val passed = isToday && time != null && time.isBefore(now)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            trip.departureTime,
            style = MaterialTheme.typography.titleMedium,
            color =
                when {
                    isNext -> MaterialTheme.colorScheme.primary
                    passed -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.width(64.dp),
        )
        Text(
            trip.destination.ifEmpty { trip.lineName },
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (passed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isNext) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    stringResource(R.string.next_departure),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * The index of the line's first trip that hasn't left yet (today's
 * schedule. [trips] must be time-ascending), or null when every trip has
 * passed. The "next" highlight is by index, not by time: two trips at
 * the same minute (two routes of one line) must not both render as the
 * next departure.
 */
fun nextDepartureIndex(
    trips: List<TimetableEntry>,
    now: LocalTime,
): Int? =
    trips
        .indexOfFirst { trip ->
            val time = runCatching { LocalTime.parse(trip.departureTime) }.getOrNull()
            time != null && !time.isBefore(now)
        }.takeIf { it >= 0 }

/** The week's days ordered starting at [start]. The timetable chips row
 *  opens with the selected day first, so the selected chip is always in
 *  view at the left edge with no initial scroll needed. */
internal fun weekDaysStartingAt(start: DayOfWeek): List<DayOfWeek> = (0..6).map { start.plus(it.toLong()) }

/** Localized weekday label ("Δευτέρα"/"Monday") from resources, so the
 *  chips follow the in-app language rather than the device locale.
 *  Locale.getDefault() formatting would show English chips on a Greek UI
 *  forced over an English device. */
@Composable
private fun dayLabel(day: DayOfWeek): String =
    when (day) {
        DayOfWeek.MONDAY -> stringResource(R.string.day_monday)
        DayOfWeek.TUESDAY -> stringResource(R.string.day_tuesday)
        DayOfWeek.WEDNESDAY -> stringResource(R.string.day_wednesday)
        DayOfWeek.THURSDAY -> stringResource(R.string.day_thursday)
        DayOfWeek.FRIDAY -> stringResource(R.string.day_friday)
        DayOfWeek.SATURDAY -> stringResource(R.string.day_saturday)
        DayOfWeek.SUNDAY -> stringResource(R.string.day_sunday)
    }
