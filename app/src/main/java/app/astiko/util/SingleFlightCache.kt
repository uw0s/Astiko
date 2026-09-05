package app.astiko.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Catalog access with a shared in-flight fetch, one cache per instance.
 * Callers use it for their static catalogs (the route and stop lists),
 * so concurrent cold-start callers (the lines tab, the direction sheet,
 * the offline prefetcher) await one shared fetch instead of each
 * hitting the API.
 *
 * Invariants:
 * - The mutex guards only the cache and in-flight maps. The network
 *   fetch runs on [scope], outside the lock, so a catalo stall never
 *   blocks the calls queued behind it.
 * - The fetch runs on the app-lifetime [scope], never the caller's
 *   context. A caller popping a screen mid-fetch cannot cancel it, and
 *   the result still warms the cache.
 * - [cacheIt] keeps only successful results. A failed or cancelled
 *   fetch is never cached, so a transient error retries on the next
 *   access instead of pinning an empty catalog for the process.
 * - The in-flight entry is dropped on settle, success or failure, so a
 *   failed fetch never blocks retries. Every awaiter's finally runs,
 *   and the compare-and-remove keeps a stale awaiter from evicting a
 *   newer fetch registered in the meantime.
 */
class SingleFlightCache {
    private val mutex = Mutex()

    /** App-lifetime scope for catalog fetches (see class doc). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = mutableMapOf<String, Any?>()
    private val inFlight = mutableMapOf<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(
        key: String,
        cacheIt: (T) -> Boolean = { true },
        fetch: suspend () -> T,
    ): T {
        mutex.withLock { cache[key]?.let { return it as T } }
        val deferred =
            mutex.withLock {
                cache[key]?.let { return it as T }
                inFlight[key]
                    ?: scope
                        .async {
                            val fetched = fetch()
                            // Cache and in-flight maps are only ever touched
                            // under the mutex.
                            mutex.withLock { if (cacheIt(fetched)) cache[key] = fetched }
                            fetched
                        }.also { inFlight[key] = it }
            }
        return try {
            deferred.await() as T
        } finally {
            // Compare-and-remove: only this awaiter's finally may drop the
            // entry, so a stale awaiter cannot evict a newer fetch.
            mutex.withLock { if (inFlight[key] === deferred) inFlight.remove(key) }
        }
    }
}
