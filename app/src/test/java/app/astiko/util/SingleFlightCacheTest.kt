package app.astiko.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provider catalogs' shared-fetch invariants: one network call for
 * concurrent cold-start callers, failures never cached (the next access
 * retries), and cacheIt keeping only results with data.
 */
class SingleFlightCacheTest {
    @Test
    fun concurrentCallersShareOneFetch() =
        runTest {
            val cache = SingleFlightCache()
            val fetches = AtomicInteger()
            val gate = CompletableDeferred<String>()
            val fetch: suspend () -> String = {
                fetches.incrementAndGet()
                gate.await()
            }
            val results =
                (1..4).map {
                    async(start = CoroutineStart.UNDISPATCHED) { cache.get(key = "k", fetch = fetch) }
                }
            gate.complete("value")
            results.forEach { assertEquals("value", it.await()) }
            assertEquals(1, fetches.get())
        }

    @Test
    fun failureIsNotCachedAndNextAccessRetries() =
        runTest {
            val cache = SingleFlightCache()
            val fetches = AtomicInteger()
            val error =
                try {
                    cache.get(key = "k") {
                        fetches.incrementAndGet()
                        throw IOException("boom")
                    }
                    null
                } catch (e: IOException) {
                    e
                }
            assertEquals(IOException::class.java, error?.javaClass)
            assertEquals(1, fetches.get())
            assertEquals(
                "ok",
                cache.get(key = "k") {
                    fetches.incrementAndGet()
                    "ok"
                },
            )
            assertEquals(2, fetches.get())
        }

    @Test
    fun resultRejectedByCacheItIsNotCached() =
        runTest {
            val cache = SingleFlightCache()
            val fetches = AtomicInteger()
            assertEquals(
                "",
                cache.get(
                    key = "k",
                    cacheIt = { it.isNotEmpty() },
                ) {
                    fetches.incrementAndGet()
                    ""
                },
            )
            assertEquals(
                "",
                cache.get(
                    key = "k",
                    cacheIt = { it.isNotEmpty() },
                ) {
                    fetches.incrementAndGet()
                    ""
                },
            )
            assertEquals(2, fetches.get())
        }

    @Test
    fun successIsCached() =
        runTest {
            val cache = SingleFlightCache()
            val fetches = AtomicInteger()
            val first =
                cache.get(key = "k") {
                    fetches.incrementAndGet()
                    "a"
                }
            assertSame(
                first,
                cache.get(key = "k") {
                    fetches.incrementAndGet()
                    "b"
                },
            )
            assertEquals(1, fetches.get())
        }
}
