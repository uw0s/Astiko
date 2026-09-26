package app.astiko.data.cache

import android.content.Context
import android.util.Log
import app.astiko.data.model.Provider
import app.astiko.util.runCatchingNotCancelled
import app.astiko.util.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * The offline cache: one JSON file per entry in
 * `filesDir/offline_cache/`, keyed by filename.
 *
 * Design:
 * - No central index. Filenames carry the key, `savedAt` lives in the
 *   wrapper. Eviction and status scans list the directory, which is
 *   fine for a few thousand small files.
 * - Atomic writes (write `*.tmp`, rename). A crash mid-write can never
 *   leave a truncated entry that then serves as valid data.
 * - Tolerant decode. Unknown keys are ignored (schema drift must never
 *   wipe the cache) and one unreadable file drops only itself.
 * - App-private (cleared on uninstall). It is a cache, not user data.
 *
 * Everything is Mutex-guarded and runs on Dispatchers.IO. The cache is
 * only ever touched by the CachedTransitRepository decorator, plus the
 * settings "clear cache" action.
 */
class OfflineCache(
    private val dir: File,
    /** Per-provider+lang quota. Tests use tiny limits to force eviction
     *  (production: 25 MB). */
    private val quotaBytes: Long = QUOTA_BYTES,
    /** Eviction scan throttle, injectable for tests (production: 5 s). */
    private val evictCheckIntervalMs: Long = EVICT_CHECK_INTERVAL_MS,
) {
    /** App wiring. The cache lives in the app-private files dir. */
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EntryWrapper(
        val v: Int = FORMAT_VERSION,
        val savedAt: Long,
        val data: JsonElement,
    )

    data class CachedValue<T>(
        val value: T,
        val savedAt: Long,
    )

    private val mutex = Mutex()

    /**
     * Monotonic change counter, bumped on every write and on clear. The
     * offline banner re-reads the store on changes instead of polling.
     * Eviction only ever runs inside `write`, so its deletions are
     * covered by that write's bump.
     */
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /**
     * Read one entry. Null on miss, on version mismatch, or when the
     * file is unreadable (a corrupt file is a miss, dropped on the next
     * write or eviction pass, never a whole-cache failure). See
     * [decodeFile] for the shared decode path.
     */
    suspend fun <T> read(
        key: String,
        serializer: KSerializer<T>,
    ): CachedValue<T>? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val file = fileOf(key)
                if (!file.isFile) return@withContext null
                decodeFile(file, serializer)
            }
        }

    /** Whether an entry exists (a cheap existence check, used to avoid
     *  rewriting stop-catalog entries that are still on disk). */
    suspend fun contains(key: String): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) { fileOf(key).isFile }
        }

    /**
     * Write one entry atomically. A failed write (disk full, and so on)
     * must never fail the fetch that produced the data, so it is
     * swallowed. A cancelled write must keep cancelling, since runCatching
     * would swallow the cancellation and the write would report success
     * and bump the generation counter.
     */
    suspend fun <T> write(
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ) {
        mutex.withLock {
            val written =
                withContext(Dispatchers.IO) {
                    runCatchingNotCancelled {
                        val wrapper =
                            EntryWrapper(
                                v = FORMAT_VERSION,
                                savedAt = System.currentTimeMillis(),
                                data = json.encodeToJsonElement(serializer, value),
                            )
                        writeAtomically(
                            target = fileOf(key),
                            bytes =
                                json
                                    .encodeToString(EntryWrapper.serializer(), wrapper)
                                    .toByteArray(),
                            tmp = fileOf("$key.tmp"),
                        )
                    }.isSuccess
                }
            // A failed write contributed nothing to the quota, so skip the
            // eviction scan and the generation bump. The offline banner
            // must not be told a mutation happened when nothing landed.
            if (written) {
                evictIfOverQuota(key.prefix())
                _generation.value = _generation.value + 1
            }
        }
    }

    /** All decoded entries whose filename starts with [keyPrefix]
     *  (`"citybus-el-stops-"`), used by the offline nearby computation
     *  over the stop catalog. */
    suspend fun <T> readAll(
        keyPrefix: String,
        serializer: KSerializer<T>,
    ): List<CachedValue<T>> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                listFiles(keyPrefix).mapNotNull { decodeFile(it, serializer) }
            }
        }

    suspend fun entryCount(): Int =
        mutex.withLock {
            withContext(Dispatchers.IO) { listFiles().size }
        }

    /** Per-provider+lang snapshot (offline banner wording + settings
     *  "cached data" / "last sync" rows). */
    data class PrefixStats(
        val bytes: Long,
        val entries: Int,
        val newestAt: Long?,
    )

    suspend fun statsFor(prefix: String): PrefixStats =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val files = listFiles("$prefix-")
                PrefixStats(
                    bytes = files.sumOf { it.length() },
                    entries = files.size,
                    // Atomic rename stamps the write time, so mtime is the sync moment.
                    newestAt = files.maxOfOrNull { it.lastModified() },
                )
            }
        }

    suspend fun sizeBytes(): Long =
        mutex.withLock {
            withContext(Dispatchers.IO) { listFiles().sumOf { it.length() } }
        }

    suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatchingNotCancelled {
                    runCatching { dir.listFiles() }.getOrNull().orEmpty().forEach { it.delete() }
                }
            }
        }
        _generation.value = _generation.value + 1
    }

    /**
     * Per-provider+lang quota of 25 MB per prefix. A full "download city"
     * prefetch lands around 12 MB. LRU by file modification time (the
     * atomic rename stamps the write time). The oldest entries of the
     * overflowing prefix are dropped until it fits again.
     *
     * The check is throttled to once per 5 s per prefix. The scan costs a
     * full directory listing, and a prefetch makes thousands of writes
     * (route entries plus force-upserted stops). Scanning on every write
     * would serialize the cache mutex behind O(files) work that grows
     * with the cache. The quota is a soft safety net, so a 5 s delay
     * before evicting is irrelevant.
     */
    private suspend fun evictIfOverQuota(prefix: String) {
        val now = System.currentTimeMillis()
        val last = synchronized(lastEvictCheck) { lastEvictCheck[prefix] ?: 0L }
        if (now - last < evictCheckIntervalMs) return
        synchronized(lastEvictCheck) { lastEvictCheck[prefix] = now }
        withContext(Dispatchers.IO) {
            val files = listFiles("$prefix-").sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            if (total <= quotaBytes) return@withContext
            Log.w(TAG, "cache prefix $prefix at ${total / 1024} KB, evicting oldest entries")
            // Plain loop with a break. A takeWhile here would evaluate
            // `total > quotaBytes` against the unchanged sum (the
            // decrements happen below) and delete the entire prefix.
            for (file in files) {
                if (total <= quotaBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }

    private val lastEvictCheck = mutableMapOf<String, Long>()

    /** Decode one cache file. Null on miss, version mismatch, or unreadable
     *  content (a corrupt file is a miss, dropped on the next write or
     *  eviction pass, never a whole-cache failure). */
    private fun <T> decodeFile(
        file: File,
        serializer: KSerializer<T>,
    ): CachedValue<T>? {
        val wrapper =
            runCatching {
                json.decodeFromString(EntryWrapper.serializer(), file.readText())
            }.getOrNull() ?: return null
        if (wrapper.v != FORMAT_VERSION) return null
        val value =
            runCatching {
                json.decodeFromJsonElement(serializer, wrapper.data)
            }.getOrNull() ?: return null
        return CachedValue(value, wrapper.savedAt)
    }

    /** The cache's entry files, optionally by key prefix. Leftover temp
     *  files are always excluded: they hold complete wrappers (the crash
     *  happens between write and rename), so serving or counting one would
     *  duplicate the entry. Both suffixes are checked, the cache's own
     *  `<key>.tmp.json` and the plain `<name>.tmp` any other writer may
     *  leave behind. */
    private fun listFiles(prefix: String? = null): List<File> =
        runCatching { dir.listFiles() }
            .getOrNull()
            .orEmpty()
            .filter { f ->
                f.isFile && !f.name.endsWith(TMP_SUFFIX) && !f.name.endsWith(PLAIN_TMP_SUFFIX) &&
                    (prefix == null || f.name.startsWith(prefix))
            }

    private fun fileOf(key: String): File = File(dir, sanitize(key) + ".json")

    /** Keys become filenames, so drop anything that could escape the cache
     *  directory or collide with the key separators. */
    private fun sanitize(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The quota prefix of a key. Its first two segments (`provider-lang`). */
    private fun String.prefix(): String = split('-').take(2).joinToString("-")

    companion object {
        /** Every cache key starts with `${provider}-${lang}`, the quota
         *  and status namespace. */
        fun prefix(
            provider: Provider,
            lang: String,
        ): String = "${provider.name.lowercase()}-$lang"

        /** Atomic-write temp files are `key.tmp.json` (fileOf appends the
         *  `.json` to the `$key.tmp` name). A crash between the write and
         *  the rename leaves one behind, and every scan must exclude it.
         *  It is a complete wrapper. Serving it would duplicate the entry
         *  and counting it would inflate the stats. */
        private const val TMP_SUFFIX = ".tmp.json"

        /** A temp file another writer may name `<target>.tmp` (see writeAtomically). */
        private const val PLAIN_TMP_SUFFIX = ".tmp"

        private const val DIR_NAME = "offline_cache"
        private const val FORMAT_VERSION = 1

        // 25 MB per provider+lang. A full "download city" prefetch lands
        // around 11 MB (Thessaloniki), so 25 MB leaves room to grow.
        private const val QUOTA_BYTES = 25L * 1024 * 1024

        // The scan is O(files). A prefetch writes thousands of entries.
        private const val EVICT_CHECK_INTERVAL_MS = 5_000L
        private const val TAG = "OfflineCache"
    }
}
