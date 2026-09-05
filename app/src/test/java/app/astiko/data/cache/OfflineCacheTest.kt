package app.astiko.data.cache

import app.astiko.data.model.Line
import app.astiko.data.model.Provider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * OfflineCache store tests, plain JVM and temp-dir backed (the store takes
 * a File, so no Android context is needed).
 */
class OfflineCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lines =
        listOf(Line(provider = Provider.OSETh, id = "01", shortName = "01", longName = "TEST"))

    private fun cache(
        dir: File = tmp.newFolder(),
        quotaBytes: Long = 25L * 1024 * 1024,
        evictIntervalMs: Long = 5_000L,
    ) = OfflineCache(dir, quotaBytes, evictIntervalMs)

    @Test
    fun `write then read round-trips with savedAt`() =
        runTest {
            val cache = cache()
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))

            val read = cache.read("oseth-el-lines", ListSerializer(Line.serializer()))
            assertNotNull(read)
            assertEquals(lines, read!!.value)
            assertTrue(read.savedAt > 0)
        }

    @Test
    fun `missing key reads null`() =
        runTest {
            val cache = cache()
            assertNull(cache.read("oseth-el-lines", ListSerializer(Line.serializer())))
        }

    @Test
    fun `corrupt file drops only itself`() =
        runTest {
            val dir = tmp.newFolder()
            val cache = cache(dir)
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-el-stops-1", lines, ListSerializer(Line.serializer()))
            // A truncated/overwritten file must drop only itself.
            File(dir, "oseth-el-lines.json").writeText("garbage{{{")

            assertNull(cache.read("oseth-el-lines", ListSerializer(Line.serializer())))
            // The rest of the cache still works.
            assertEquals(
                lines,
                cache.read("oseth-el-stops-1", ListSerializer(Line.serializer()))!!.value,
            )
        }

    @Test
    fun `wrapper version mismatch reads null`() =
        runTest {
            val dir = tmp.newFolder()
            val cache = cache(dir)
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            // Plant a wrapper with a future format version (the store omits the
            // default v:1 from its own output, so an in-place replace wouldn't
            // find anything to replace).
            File(dir, "oseth-el-lines.json").writeText("""{"v":99,"savedAt":123,"data":[]}""")

            assertNull(cache.read("oseth-el-lines", ListSerializer(Line.serializer())))
        }

    @Test
    fun `contains reflects existence`() =
        runTest {
            val cache = cache()
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            assertTrue(cache.contains("oseth-el-lines"))
            assertFalse(cache.contains("oseth-el-lines-other"))
        }

    @Test
    fun `readAll matches the key prefix only`() =
        runTest {
            val cache = cache()
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-el-stops-1", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-en-lines", lines, ListSerializer(Line.serializer()))

            val el = cache.readAll("oseth-el-", ListSerializer(Line.serializer()))
            assertEquals(2, el.size)
        }

    /** One entry's on-disk size (wrapper + JSON). The eviction tests
     *  derive their quotas from this instead of hardcoding byte counts,
     *  so a model change can't silently break them. */
    private suspend fun entrySizeBytes(): Long {
        val probe = cache(tmp.newFolder(), quotaBytes = Long.MAX_VALUE, evictIntervalMs = 0)
        probe.write("oseth-el-probe", lines, ListSerializer(Line.serializer()))
        return probe.sizeBytes()
    }

    /** Pin a past mtime. Rapid writes can tie mtimes on Linux (jiffy
     *  granularity, seen on CI), leaving the LRU order arbitrary. */
    private fun File.pinAge(ms: Long) {
        assertTrue("setLastModified on $name", setLastModified(ms))
    }

    @Test
    fun `eviction drops oldest first and keeps the newest`() =
        runTest {
            val dir = tmp.newFolder()
            val size = entrySizeBytes()
            // Quota fits exactly 3 entries: the 4th write evicts the oldest.
            val cache = cache(dir, quotaBytes = 3 * size, evictIntervalMs = 0)
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-el-lineVariants-1", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-el-lineVariants-2", lines, ListSerializer(Line.serializer()))
            // Distinct ages for the first three. The 4th write is newest.
            File(dir, "oseth-el-lines.json").pinAge(1_000)
            File(dir, "oseth-el-lineVariants-1.json").pinAge(2_000)
            File(dir, "oseth-el-lineVariants-2.json").pinAge(3_000)
            cache.write("oseth-el-lineVariants-3", lines, ListSerializer(Line.serializer()))

            // The oldest entry must be gone and the newest must survive, and
            // it must not be a wipe of the whole prefix (the takeWhile regression
            // deleted everything once the quota was exceeded).
            assertNull(cache.read("oseth-el-lines", ListSerializer(Line.serializer())))
            assertNotNull(cache.read("oseth-el-lineVariants-3", ListSerializer(Line.serializer())))
            assertEquals(3, cache.entryCount())
        }

    @Test
    fun `eviction only touches the overflowing prefix`() =
        runTest {
            val dir = tmp.newFolder()
            val size = entrySizeBytes()
            // Quota fits exactly one entry per prefix.
            val cache = cache(dir, quotaBytes = size + 1, evictIntervalMs = 0)
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-en-lines", lines, ListSerializer(Line.serializer()))
            // oseth-en's oldest entry, so the evicted one.
            File(dir, "oseth-en-lines.json").pinAge(1_000)
            cache.write("oseth-en-stops-1", lines, ListSerializer(Line.serializer()))

            // oseth-en overflowed (2 entries > quota) and lost its OLDEST entry.
            assertNull(cache.read("oseth-en-lines", ListSerializer(Line.serializer())))
            assertNotNull(cache.read("oseth-en-stops-1", ListSerializer(Line.serializer())))
            // The oseth-el prefix never overflowed, so it stays untouched.
            assertNotNull(cache.read("oseth-el-lines", ListSerializer(Line.serializer())))
        }

    @Test
    fun `statsFor reports bytes entries and newest write`() =
        runTest {
            val cache = cache()
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            cache.write("oseth-el-stops-1", lines, ListSerializer(Line.serializer()))

            val stats = cache.statsFor("oseth-el")
            assertEquals(2, stats.entries)
            assertTrue(stats.bytes > 0)
            assertNotNull(stats.newestAt)
            assertEquals(0, cache.statsFor("citybus-en").entries)
        }

    @Test
    fun `clear wipes everything`() =
        runTest {
            val cache = cache()
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            cache.write("citybus-en-lines", lines, ListSerializer(Line.serializer()))

            cache.clear()

            assertEquals(0, cache.entryCount())
            assertEquals(0, cache.sizeBytes())
        }

    @Test
    fun `generation bumps on write and clear, stable on read`() =
        runTest {
            val cache = cache()
            val g0 = cache.generation.value
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))
            assertTrue(cache.generation.value > g0)

            // Reads must not notify observers (no state change).
            val g1 = cache.generation.value
            cache.read("oseth-el-lines", ListSerializer(Line.serializer()))
            assertEquals(g1, cache.generation.value)

            cache.clear()
            assertTrue(cache.generation.value > g1)
        }

    @Test
    fun `atomic writes never leave tmp files behind`() =
        runTest {
            val cache = cache()
            cache.write("oseth-el-lines", lines, ListSerializer(Line.serializer()))

            val leftovers =
                tmp.root
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.endsWith(".tmp") }
            assertTrue(leftovers.isEmpty())
        }

    @Test
    fun `leftover tmp file is neither served nor counted`() =
        runTest {
            val dir = tmp.newFolder()
            val cache = cache(dir)
            cache.write("oseth-el-stops-1", lines, ListSerializer(Line.serializer()))
            // Simulate a crash between the atomic write and the rename: the
            // tmp file holds a COMPLETE, decodable wrapper (the write finished,
            // the move never ran).
            val json = File(dir, "oseth-el-stops-1.json")
            assertTrue(json.renameTo(File(dir, "oseth-el-stops-1.tmp.json")))

            // The leftover must not be served as data (a decoded duplicate
            // would crash the offline stops list with duplicate keys)...
            assertTrue(
                cache.readAll("oseth-el-stops-", ListSerializer(Line.serializer())).isEmpty(),
            )
            assertNull(cache.read("oseth-el-stops-1", ListSerializer(Line.serializer())))
            // ...nor counted in the stats.
            assertEquals(0, cache.entryCount())
            assertEquals(0, cache.sizeBytes())
            assertEquals(0, cache.statsFor("oseth-el").entries)
        }

    @Test
    fun `rewriting a key consumes its stale tmp file`() =
        runTest {
            val dir = tmp.newFolder()
            val cache = cache(dir)
            cache.write("oseth-el-stops-1", lines, ListSerializer(Line.serializer()))
            val json = File(dir, "oseth-el-stops-1.json")
            assertTrue(json.renameTo(File(dir, "oseth-el-stops-1.tmp.json")))

            // A successful write of the same key overwrites the stale tmp and
            // moves it into place, so no leftover survives.
            cache.write("oseth-el-stops-1", lines, ListSerializer(Line.serializer()))
            assertFalse(File(dir, "oseth-el-stops-1.tmp.json").exists())
            assertEquals(
                lines,
                cache.read("oseth-el-stops-1", ListSerializer(Line.serializer()))!!.value,
            )
        }
}
