package app.astiko.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.astiko.TransitApp
import app.astiko.data.TransitRepository
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop
import app.astiko.data.model.TimetableEntry
import app.astiko.util.mapBounded
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

sealed interface TimetableTarget {
    val provider: Provider
    val key: String // unique ViewModel key

    data class StopTarget(
        val stop: Stop,
    ) : TimetableTarget {
        override val provider get() = stop.provider
        override val key get() = "stop-${stop.provider}-${stop.id}"
    }

    data class LineVariantTarget(
        val variant: LineVariant,
    ) : TimetableTarget {
        override val provider get() = variant.provider
        override val key get() = "line-${variant.provider}-${variant.id}"
    }
}

class TimetableViewModel(
    private val repository: TransitRepository,
    private val target: TimetableTarget,
) : ViewModel() {
    private val _selectedDay = MutableStateFlow(LocalDate.now().dayOfWeek)
    val selectedDay: StateFlow<DayOfWeek> = _selectedDay.asStateFlow()

    private val _entries = MutableStateFlow<List<TimetableEntry>>(emptyList())
    val entries: StateFlow<List<TimetableEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val cache = mutableMapOf<DayOfWeek, List<TimetableEntry>>()

    private var fetchJob: Job? = null

    private var warmed = false

    init {
        load()
    }

    fun selectDay(day: DayOfWeek) {
        if (day == _selectedDay.value) return
        _selectedDay.value = day
        load()
    }

    fun retry() = load()

    private fun load() {
        val day = _selectedDay.value
        // Cancel the previous day's fetch first. The cache-hit path below
        // returns early, and an orphaned fetch for the abandoned day must
        // not stay alive to land its response (success or failure) over
        // the day the user is viewing.
        fetchJob?.cancel()
        cache[day]?.let {
            _entries.value = it
            _loading.value = false
            _error.value = null
            return
        }
        _loading.value = true
        _error.value = null
        fetchJob =
            viewModelScope.launch {
                runCatchingNotCancelled { fetchTimetable(day) }
                    .onSuccess { trips ->
                        // Belt and braces. A fetch that somehow survives the
                        // cancellation must never show under a different day.
                        if (_selectedDay.value == day) {
                            cache[day] = trips
                            _entries.value = trips
                            _loading.value = false
                            // First successful fetch. Warm the whole week in the
                            // background so the timetable works offline after one
                            // visit.
                            if (!warmed) {
                                warmed = true
                                warmAllDays()
                            }
                        }
                    }.onFailure { e ->
                        // Same day guard as the success path. A failure for an
                        // abandoned day (the user switched back to a cached day
                        // without cancelling this fetch) must never replace the
                        // viewed day's content with an error screen.
                        if (_selectedDay.value == day) {
                            _error.value = e.message
                            _loading.value = false
                        }
                    }
            }
    }

    private suspend fun fetchTimetable(day: DayOfWeek): List<TimetableEntry> =
        when (target) {
            is TimetableTarget.StopTarget -> repository.getStopTimetable(target.stop.id, day)
            is TimetableTarget.LineVariantTarget -> repository.getLineTimetable(target.variant, day)
        }

    /**
     * Background warm-up of all 7 weekdays of the target. Opening the
     * schedule is the explicit interest signal, so the full week lands in
     * the disk cache and works offline. The selected day was just written
     * and serves from cache without a network call. Failures are ignored.
     * The job is separate from [fetchJob], so a day switch does not cancel
     * it, and leaving the screen cancels it via the scope.
     */
    private fun warmAllDays() {
        val supported =
            when (target) {
                is TimetableTarget.StopTarget -> repository.supportsStopTimetable
                is TimetableTarget.LineVariantTarget -> repository.supportsLineTimetable
            }
        if (!supported) return
        viewModelScope.launch {
            // Bounded, to stay polite to the unofficial APIs.
            DayOfWeek.entries.mapBounded(WARM_CONCURRENCY) { day ->
                runCatchingNotCancelled { fetchTimetable(day) }
            }
        }
    }

    companion object {
        private const val WARM_CONCURRENCY = 4 // in-flight warm-up fetches per batch

        fun factory(target: TimetableTarget): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TransitApp
                    TimetableViewModel(
                        repository = app.container.repository(target.provider),
                        target = target,
                    )
                }
            }
    }
}
