package app.astiko.util

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * [block] over [items] with at most [concurrency] in flight, the next batch
 * starting as the previous one settles. The result keeps the input order.
 *
 * Bounded because these calls go to unofficial APIs: a nearby list, a
 * favorite list or a dozen routes must not turn into dozens of simultaneous
 * requests. Cancellation propagates, and a block that must not fail its
 * batch swallows its own errors (see runCatchingNotCancelled).
 */
suspend fun <T, R> Iterable<T>.mapBounded(
    concurrency: Int,
    block: suspend (T) -> R,
): List<R> =
    coroutineScope {
        chunked(concurrency).flatMap { batch ->
            batch.map { async { block(it) } }.map { it.await() }
        }
    }
