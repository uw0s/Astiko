package app.astiko.data.cache

import android.util.Log
import app.astiko.data.TransitRepository
import app.astiko.data.model.City
import app.astiko.data.model.LineVariant
import app.astiko.data.model.Provider
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the settings "download city" row shows. Every non-idle state
 *  is tagged with the provider it was started for. A download that
 *  finished in Larissa must not leak onto Patras's section. */
sealed interface PrefetchStatus {
    /** The city's provider this status belongs to; null only for [Idle]. */
    val provider: Provider?

    data object Idle : PrefetchStatus {
        override val provider: Provider? get() = null
    }

    data class Running(
        override val provider: Provider,
        val done: Int,
        val total: Int,
    ) : PrefetchStatus

    data class Done(
        override val provider: Provider,
    ) : PrefetchStatus

    data class NeedsWifi(
        override val provider: Provider,
    ) : PrefetchStatus

    data class Failed(
        override val provider: Provider,
        val message: String?,
    ) : PrefetchStatus
}

/**
 * The settings "download city" action. It walks the city's catalog
 * through the CachedTransitRepository decorators, so every fetched line,
 * variant, route and stop lands in the disk cache. The cache is the
 * download. Re-running it later only refetches entries that aged past
 * their TTL (fresh ones serve from cache, no network).
 *
 * Scope: lines, directions, route stops, geometries and the stop
 * catalog (route stop lists and a city-center nearby search). Timetables
 * stay on-demand per visited stop and day. Prefetching 7 days times
 * every stop would be hundreds of calls for data the user may never
 * open.
 *
 * OASA (Athens) is the exception. With ~191 lines and no bulk endpoint,
 * a full download is a long sequential run against an unofficial API.
 * Athens downloads the line catalog only. Routes, stops and geometry
 * fill in on-demand via the cache's refresh-on-access.
 *
 * Wi-Fi only (validated Wi-Fi required), bounded concurrency (6 in
 * flight, polite to the unofficial APIs), one run at a time.
 */
class OfflinePrefetcher(
    private val repository: (Provider) -> TransitRepository,
    /** Wi-Fi gate, injected. Production wires ConnectivityMonitor's
     *  validated-Wi-Fi check. Tests pass a lambda. */
    private val wifiCheck: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<PrefetchStatus>(PrefetchStatus.Idle)
    val status: StateFlow<PrefetchStatus> = _status.asStateFlow()

    /** Idle to Running is set synchronously, so two taps can never launch
     *  two concurrent runs (both arrive on the main thread). */
    fun start(city: City) {
        if (_status.value is PrefetchStatus.Running) return
        if (!wifiCheck()) {
            _status.value = PrefetchStatus.NeedsWifi(city.provider)
            return
        }
        _status.value = PrefetchStatus.Running(city.provider, done = 0, total = 0)
        scope.launch { run(city) }
    }

    private suspend fun run(city: City) {
        val repo = repository(city.provider)
        try {
            val lines =
                try {
                    repo.getLines()
                } catch (e: Exception) {
                    _status.value = PrefetchStatus.Failed(city.provider, e.message)
                    return
                }
            if (lines.isEmpty()) {
                _status.value = PrefetchStatus.Failed(city.provider, null)
                return
            }

            // OASA downloads the line catalog only (see class doc).
            val fullCatalog = city.provider != Provider.OASA

            var done = 0
            val variantsByLine = mutableMapOf<String, List<LineVariant>>()
            runCatchingNotCancelled { repo.getStopsNear(city.lat, city.lon, NEARBY_LIMIT) }
            done++
            // Pass 1: each line's directions. For Athens (line catalog only)
            // the exact total is known: nearby + one fetch per line. For full
            // downloads the variant count is unknown until pass 1 finishes, so
            // the bar is indeterminate here (total = 0) and pass 2 reports the
            // exact count. An estimated total would start the bar mid-way and
            // dip when the real count lands.
            val pass1Total = if (fullCatalog) 0 else 1 + lines.size
            lines.forEach { line ->
                val variants =
                    runCatchingNotCancelled {
                        repo.getLineVariants(
                            line,
                        )
                    }.getOrDefault(emptyList())
                variantsByLine[line.id] = variants
                done++
                _status.value = PrefetchStatus.Running(city.provider, done, pass1Total)
            }

            if (!fullCatalog) {
                _status.value = PrefetchStatus.Done(city.provider)
                Log.i(
                    TAG,
                    "offline prefetch of ${city.label} done (line catalog only): $done fetches",
                )
                return
            }

            // Pass 2: each direction's stops (which also upserts the catalog)
            // plus geometry, with the exact total. Bounded concurrency (6 in
            // flight) instead of strictly sequential. The OSETh endpoint is
            // slow server-side, so a sequential download drags. A few
            // concurrent requests stay polite to an unofficial API (browsers
            // open 6+ per host) and cut the wall time sharply. The bar
            // restarts at 0% with the exact total. Pass 1's steps are not
            // carried over, or the bar would start mid-way.
            val allVariants = variantsByLine.values.flatten()
            val total = 2 * allVariants.size
            var pass2Done = 0
            coroutineScope {
                allVariants.chunked(PREFETCH_CONCURRENCY).forEach { batch ->
                    batch
                        .map { variant ->
                            async {
                                runCatchingNotCancelled { repo.getVariantStops(variant) }
                                runCatchingNotCancelled { repo.getRouteGeometry(variant) }
                            }
                        }.forEach { job ->
                            job.await()
                            pass2Done += 2
                            _status.value = PrefetchStatus.Running(city.provider, pass2Done, total)
                        }
                }
            }
            _status.value = PrefetchStatus.Done(city.provider)
            Log.i(TAG, "offline prefetch of ${city.label} done: $total fetches")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = PrefetchStatus.Failed(city.provider, e.message)
        }
    }

    companion object {
        private const val NEARBY_LIMIT = 100

        // In-flight fetches, about what browsers open per host. The slow
        // OSETh endpoint makes 3 too timid.
        private const val PREFETCH_CONCURRENCY = 6
        private const val TAG = "OfflinePrefetcher"
    }
}
