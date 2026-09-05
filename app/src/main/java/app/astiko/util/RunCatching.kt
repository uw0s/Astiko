package app.astiko.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that does not swallow [CancellationException]: a cancelled
 * coroutine must keep cancelling, not report the cancellation as a failure.
 *
 * A cancelled refresh races newer ones (re-tap, day switch, map move).
 * Reporting its cancellation as an error overwrites the newer fetch's
 * state, so an error screen flashes over live data or a stale day's
 * timetable lands after the day switch.
 */
suspend fun <T> runCatchingNotCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
