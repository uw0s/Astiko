package app.astiko.data.citybus

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Adds `Authorization: Bearer <JWT>` to every rest.citybus.gr request.
 *
 * The token is embedded in the city app's page HTML (`const token = '…'`),
 * an HS256 JWT with only an `exp` claim (~48 h validity), not bound to
 * an agency. One token serves every city on the platform. There is no
 * login, no API key and no token endpoint. The app pages are the only
 * source.
 *
 * Strategy: fetch lazily from the first reachable token page on the
 * first platform request, cache in memory for the process lifetime, and
 * on HTTP 401 (expired token) invalidate, re-fetch and retry the
 * request once.
 *
 * Token fetches go through `tokenClient`, a plain client without this
 * interceptor, so the token request can never re-enter it. The host
 * check below is a second guard.
 */
class CityBusAuthInterceptor(
    private val tokenClient: OkHttpClient,
    private val tokenPageUrls: List<String>,
    /** Negative-cache window after a failed walk: fail fast instead of
     *  re-walking up to 24 pages (~6 min) on every 15 s poll tick.
     *  Injectable for tests. */
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
    /** Budget for one whole walk, applied as each page's callTimeout, so a
     *  dead page can only burn the remaining time (otherwise 24 × 15 s ≈
     *  6 min of stall before the backoff engages). Injectable for tests. */
    private val tokenFetchDeadlineMs: Long = TOKEN_FETCH_DEADLINE_MS,
    /** Injectable clock (tests drive it deterministically). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Per-page fetch, injectable for tests. Production uses a per-call
     *  client with the remaining deadline as callTimeout. Per-call
     *  overhead is irrelevant (the walk runs once per process). */
    private val pageCall: (url: String, timeoutMs: Long) -> String = { url, timeoutMs ->
        tokenClient
            .newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(Request.Builder().url(url).build())
            .execute()
            .use { it.body.string().orEmpty() }
    },
) : Interceptor {
    @Volatile
    private var token: String? = null
    private val lock = Any()

    /** In-flight walk: concurrent requests await it instead of re-walking
     *  (no lock across network I/O). */
    private var pending: CompletableFuture<String>? = null

    @Volatile
    private var lastFailureAt = 0L

    @Volatile
    private var lastFailureMessage: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only the platform API gets the header, token pages and other
        // providers pass through untouched.
        if (request.url.host != API_HOST) return chain.proceed(request)

        // Capture the token this request carries: a 401 invalidates exactly
        // that one (compare-and-set).
        val usedToken = token()
        val response = chain.proceed(authorized(request, usedToken))
        if (response.code == HTTP_UNAUTHORIZED) {
            // Expired JWT (48 h validity). Fetch a fresh one and retry once.
            response.close()
            refreshToken(usedToken)
            return chain.proceed(authorized(request))
        }
        return response
    }

    private fun authorized(request: Request): Request =
        request.newBuilder().header("Authorization", "Bearer ${token()}").build()

    private fun authorized(
        request: Request,
        token: String,
    ): Request = request.newBuilder().header("Authorization", "Bearer $token").build()

    private fun token(): String = token ?: fetchToken()

    /**
     * Invalidate the failed token (compare-and-set: a stale 401 must not
     * discard a concurrent refresh, and two 401s must not double-fetch),
     * then fetch a fresh one.
     */
    private fun refreshToken(failed: String) {
        synchronized(lock) {
            if (token == failed) token = null
        }
        fetchToken()
    }

    /**
     * Shared in-flight walk: the lock guards only the token/future
     * bookkeeping, the network runs outside it. A lock across up to 24
     * page fetches would block every request, and interceptor execution
     * is not covered by OkHttp timeouts.
     */
    private fun fetchToken(): String {
        token?.let { return it }
        // Failure backoff: fail fast with the previous error instead of
        // re-walking every page per request (24 × 15 s serialized,
        // re-triggered by the 15 s poll).
        if (now() - lastFailureAt < failureBackoffMs) {
            throw IOException(lastFailureMessage ?: "Telematics token fetch failed recently")
        }
        var leader: CompletableFuture<String>? = null
        val future =
            synchronized(lock) {
                token?.let { return it }
                pending ?: CompletableFuture<String>().also {
                    pending = it
                    leader = it
                }
            }
        if (leader != null) {
            // Leader: walk the pages (network outside the lock), then
            // publish the token.
            try {
                val fetched = fetchFromPages()
                synchronized(lock) { token = fetched }
                lastFailureAt = 0
                lastFailureMessage = null
                leader.complete(fetched)
                return fetched
            } catch (t: Throwable) {
                // Any failure must release the waiters, a joiner stuck in
                // future.get() would otherwise hang forever (no OkHttp
                // timeout coverage). CancellationException can't occur here
                // (no coroutines), but rethrowing is correct either way.
                lastFailureAt = now()
                lastFailureMessage = t.message
                leader.completeExceptionally(t)
                throw t
            } finally {
                synchronized(lock) { pending = null }
            }
        }
        // Joiner: await the leader's walk (any token works). A failure
        // propagates and the next request leads.
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the telematics token", e)
        }
    }

    private fun fetchFromPages(): String {
        // Bounded by [tokenFetchDeadlineMs]: checked before every page and
        // applied as that page's callTimeout, so a dead page only burns the
        // remaining budget (never 24 × 15 s). Any outcome is a normal walk
        // result. The caller records it and releases the waiters.
        val deadline = now() + tokenFetchDeadlineMs
        // Any city's page works (the token is not agency-bound), so a down
        // page only costs that city.
        for (url in tokenPageUrls) {
            val remaining = deadline - now()
            if (remaining <= 0) {
                throw IOException("Telematics token fetch timed out")
            }
            // A failing page (network, 5xx, no token) must not abort the chain.
            val found =
                runCatching {
                    pageCall(url, remaining)
                }.getOrNull()
                    ?.let { TOKEN_REGEX.find(it)?.groupValues?.get(1) }
            if (!found.isNullOrEmpty()) {
                return found
            }
        }
        // Raw message for the UI, language-neutral. The URL list is the
        // actionable part.
        throw IOException("Telematics token not found (tried: ${tokenPageUrls.joinToString()})")
    }

    companion object {
        private const val API_HOST = "rest.citybus.gr"
        private const val HTTP_UNAUTHORIZED = 401
        private const val FAILURE_BACKOFF_MS = 5L * 60 * 1000

        /** Whole-walk budget: per-call timeouts alone would allow
         *  24 × 15 s ≈ 6 min. */
        private const val TOKEN_FETCH_DEADLINE_MS = 20L * 1000
        private val TOKEN_REGEX = Regex("const token = '([^']+)'")
    }
}
