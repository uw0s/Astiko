package app.astiko.data.citybus

import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.reflect.KClass

class CityBusAuthInterceptorTest {
    // ---------- helpers ----------

    /** A token client that fabricates responses; no network ever happens. */
    private fun tokenClient(
        vararg pages: Pair<String, String>, // url fragment -> page body
        failures: List<String> = emptyList(), // url fragments that throw
    ): OkHttpClient {
        val responses = mapOf(*pages)
        return OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                failures.firstOrNull { url.contains(it) }?.let { throw IOException("network down") }
                val body =
                    responses.entries.firstOrNull { url.contains(it.key) }?.value
                        ?: "<html>no token here</html>"
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }.build()
    }

    private fun pageWithToken(token: String) = "<html><script>const token = '$token'</script></html>"

    /**
     * Minimal Chain implementation: hands out queued responses, records requests.
     * OkHttp 5 made the whole client-config surface abstract. Mirror the real
     * client defaults, only request()/proceed() carry test logic.
     */
    private class FakeChain(
        val initialRequest: Request,
        var responses: ArrayDeque<Response>,
    ) : Interceptor.Chain {
        val requests = mutableListOf<Request>()

        override fun request(): Request = requests.lastOrNull() ?: initialRequest

        override fun proceed(request: Request): Response {
            requests += request
            return responses.removeFirst()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = DummyCall

        override fun connectTimeoutMillis(): Int = 10_000

        override fun readTimeoutMillis(): Int = 10_000

        override fun writeTimeoutMillis(): Int = 10_000

        override fun withConnectTimeout(
            timeout: Int,
            unit: TimeUnit,
        ): Interceptor.Chain = this

        override fun withReadTimeout(
            timeout: Int,
            unit: TimeUnit,
        ): Interceptor.Chain = this

        override fun withWriteTimeout(
            timeout: Int,
            unit: TimeUnit,
        ): Interceptor.Chain = this

        override val followSslRedirects: Boolean = true
        override val followRedirects: Boolean = true
        override val dns: Dns = Dns.SYSTEM
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override val retryOnConnectionFailure: Boolean = true
        override val authenticator: Authenticator = Authenticator.NONE
        override val cookieJar: CookieJar = CookieJar.NO_COOKIES
        override val cache: Cache? = null
        override val proxy: Proxy? = null
        override val proxySelector: ProxySelector = ProxySelector.getDefault()
        override val proxyAuthenticator: Authenticator = Authenticator.NONE
        override val sslSocketFactoryOrNull: SSLSocketFactory? = null
        override val x509TrustManagerOrNull: X509TrustManager? = null
        override val hostnameVerifier: HostnameVerifier =
            HttpsURLConnection
                .getDefaultHostnameVerifier()
        override val certificatePinner: CertificatePinner = CertificatePinner.DEFAULT
        override val connectionPool: ConnectionPool = ConnectionPool()
        override val eventListener: EventListener = EventListener.NONE

        override fun withDns(dns: Dns): Interceptor.Chain = this

        override fun withSocketFactory(socketFactory: SocketFactory): Interceptor.Chain = this

        override fun withRetryOnConnectionFailure(retryOnConnectionFailure: Boolean): Interceptor.Chain = this

        override fun withAuthenticator(authenticator: Authenticator): Interceptor.Chain = this

        override fun withCookieJar(cookieJar: CookieJar): Interceptor.Chain = this

        override fun withCache(cache: Cache?): Interceptor.Chain = this

        override fun withProxy(proxy: Proxy?): Interceptor.Chain = this

        override fun withProxySelector(proxySelector: ProxySelector): Interceptor.Chain = this

        override fun withProxyAuthenticator(proxyAuthenticator: Authenticator): Interceptor.Chain = this

        override fun withSslSocketFactory(
            sslSocketFactory: SSLSocketFactory?,
            x509TrustManager: X509TrustManager?,
        ): Interceptor.Chain = this

        override fun withHostnameVerifier(hostnameVerifier: HostnameVerifier): Interceptor.Chain = this

        override fun withCertificatePinner(certificatePinner: CertificatePinner): Interceptor.Chain = this

        override fun withConnectionPool(connectionPool: ConnectionPool): Interceptor.Chain = this
    }

    private object DummyCall : Call {
        override fun request() = throw UnsupportedOperationException()

        override fun execute() = throw UnsupportedOperationException()

        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()

        override fun cancel() {}

        override fun isExecuted() = false

        override fun isCanceled() = false

        override fun timeout() = throw UnsupportedOperationException()

        override fun addEventListener(eventListener: EventListener) = throw UnsupportedOperationException()

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(
            type: KClass<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun <T : Any> tag(
            type: Class<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun clone() = throw UnsupportedOperationException()
    }

    private fun response(code: Int): Response =
        Response
            .Builder()
            .request(Request.Builder().url("https://rest.citybus.gr/api/v1").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body("".toResponseBody(null))
            .build()

    private val platformRequest =
        Request
            .Builder()
            .url("https://rest.citybus.gr/api/v1/el/102/stops")
            .build()

    // ---------- tests ----------

    @Test
    fun nonPlatformHost_passesThrough_NoAuthHeader() {
        val chain =
            FakeChain(
                initialRequest =
                    Request
                        .Builder()
                        .url(
                            "https://oseth.com.gr/en/telematics-api/route",
                        ).build(),
                responses = ArrayDeque(listOf(response(200))),
            )

        val result =
            CityBusAuthInterceptor(
                tokenClient("larissa.citybus.gr" to pageWithToken("T1")),
                listOf("https://larissa.citybus.gr/en/stops"),
            ).intercept(chain)

        assertEquals(200, result.code)
        assertEquals(1, chain.requests.size)
        assertNull(chain.requests[0].header("Authorization"))
    }

    @Test
    fun platformHost_fetchesTokenAndAttachesBearer() {
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(200))))

        CityBusAuthInterceptor(
            tokenClient("larissa.citybus.gr" to pageWithToken("T1")),
            listOf("https://larissa.citybus.gr/en/stops"),
        ).intercept(chain)

        assertEquals(1, chain.requests.size)
        assertEquals("Bearer T1", chain.requests[0].header("Authorization"))
    }

    @Test
    fun http401_refetchesTokenAndRetriesOnce() {
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(401), response(200))))
        val interceptor =
            CityBusAuthInterceptor(
                tokenClient("larissa.citybus.gr" to pageWithToken("T1")),
                listOf("https://larissa.citybus.gr/en/stops"),
            )

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        assertEquals(2, chain.requests.size)
        // Both the original and the retried request carry the (fresh) token.
        assertEquals("Bearer T1", chain.requests[0].header("Authorization"))
        assertEquals("Bearer T1", chain.requests[1].header("Authorization"))
    }

    @Test
    fun http401_withSecondPageToken_retriesWithFreshToken() {
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(401), response(200))))
        // The page serves a new token on each fetch, like a ~48 h expiry.
        var served = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { c ->
                    val token = if (served++ == 0) "OLD" else "NEW"
                    Response
                        .Builder()
                        .request(c.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(pageWithToken(token).toResponseBody("text/html".toMediaType()))
                        .build()
                }.build()

        val result =
            CityBusAuthInterceptor(
                client,
                listOf("https://larissa.citybus.gr/en/stops"),
            ).intercept(chain)

        assertEquals(200, result.code)
        assertEquals("Bearer OLD", chain.requests[0].header("Authorization"))
        assertEquals("Bearer NEW", chain.requests[1].header("Authorization"))
    }

    @Test
    fun non401Errors_passThrough_NoRetry() {
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(500))))

        val result =
            CityBusAuthInterceptor(
                tokenClient("larissa.citybus.gr" to pageWithToken("T1")),
                listOf("https://larissa.citybus.gr/en/stops"),
            ).intercept(chain)

        assertEquals(500, result.code)
        assertEquals(1, chain.requests.size)
    }

    @Test
    fun fallbackChain_triesNextPageWhenFirstFails() {
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(200))))
        val interceptor =
            CityBusAuthInterceptor(
                tokenClient(
                    "ok.citybus.gr" to pageWithToken("T2"),
                    failures = listOf("broken.citybus.gr"),
                ),
                listOf(
                    "https://broken.citybus.gr/en/stops",
                    "https://ok.citybus.gr/en/stops",
                ),
            )

        interceptor.intercept(chain)

        assertEquals("Bearer T2", chain.requests[0].header("Authorization"))
    }

    @Test
    fun fallbackChain_triesNextPageWhenFirstHasNoToken() {
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(200))))
        val interceptor =
            CityBusAuthInterceptor(
                tokenClient(
                    "empty.citybus.gr" to "<html>no script here</html>",
                    "ok.citybus.gr" to pageWithToken("T2"),
                ),
                listOf(
                    "https://empty.citybus.gr/en/stops",
                    "https://ok.citybus.gr/en/stops",
                ),
            )

        interceptor.intercept(chain)

        assertEquals("Bearer T2", chain.requests[0].header("Authorization"))
    }

    @Test
    fun allPagesDown_throwsLanguageNeutralError() {
        val interceptor =
            CityBusAuthInterceptor(
                tokenClient("larissa.citybus.gr" to "<html>nothing</html>"),
                listOf("https://larissa.citybus.gr/en/stops"),
            )

        val e =
            assertThrows(IOException::class.java) {
                interceptor.intercept(FakeChain(platformRequest, ArrayDeque(listOf(response(200)))))
            }
        assertTrue(e.message!!.contains("Telematics token not found"))
        assertTrue(e.message!!.contains("larissa.citybus.gr"))
    }

    @Test
    fun allPagesDown_failsFastWithinBackoffWindow() {
        val pageFetches = AtomicInteger(0)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { c ->
                    pageFetches.incrementAndGet()
                    throw IOException("network down")
                }.build()
        val interceptor =
            CityBusAuthInterceptor(client, listOf("https://larissa.citybus.gr/en/stops"))

        assertThrows(IOException::class.java) {
            interceptor.intercept(FakeChain(platformRequest, ArrayDeque(listOf(response(200)))))
        }
        assertEquals(1, pageFetches.get())

        // A second request inside the backoff window fails fast with the
        // previous error, without a second page walk. Without the backoff
        // every request would walk up to 24 pages (15 s timeouts each),
        // turning an outage into permanent hammering.
        val e =
            assertThrows(IOException::class.java) {
                interceptor.intercept(FakeChain(platformRequest, ArrayDeque(listOf(response(200)))))
            }
        assertEquals(1, pageFetches.get())
        assertTrue(e.message!!.contains("Telematics token not found"))
    }

    @Test
    fun afterBackoffWindowExpired_refetchesToken() {
        var served = 0
        var fakeNow = 1_000_000L // baseline above 0. The backoff check treats 0 as "never failed"
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { c ->
                    served++
                    if (served == 1) throw IOException("network down")
                    Response
                        .Builder()
                        .request(c.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(pageWithToken("T1").toResponseBody("text/html".toMediaType()))
                        .build()
                }.build()
        val interceptor =
            CityBusAuthInterceptor(
                client,
                listOf("https://larissa.citybus.gr/en/stops"),
                failureBackoffMs = 50,
                now = { fakeNow },
            )

        assertThrows(IOException::class.java) {
            interceptor.intercept(FakeChain(platformRequest, ArrayDeque(listOf(response(200)))))
        }
        // Move the clock past the backoff window (50 ms).
        fakeNow += 80
        val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(200))))
        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        assertEquals("Bearer T1", chain.requests[0].header("Authorization"))
    }

    @Test
    fun concurrentRequests_shareOneTokenFetch() {
        val pageFetches = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        // The walk waits on tokenReady until all five threads are
        // inside intercept.
        val entered = CountDownLatch(5)
        val tokenReady = CountDownLatch(1)
        val interceptor =
            CityBusAuthInterceptor(
                tokenClient = OkHttpClient(), // unused, pageCall is injected
                tokenPageUrls = listOf("https://larissa.citybus.gr/en/stops"),
                pageCall = { _, _ ->
                    pageFetches.incrementAndGet()
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    tokenReady.await(10, TimeUnit.SECONDS)
                    inFlight.decrementAndGet()
                    pageWithToken("T1")
                },
            )

        val start = CountDownLatch(1)
        val headers = Collections.synchronizedList(mutableListOf<String?>())
        val threads =
            (1..5).map {
                Thread {
                    start.await()
                    entered.countDown()
                    val chain = FakeChain(platformRequest, ArrayDeque(listOf(response(200))))
                    interceptor.intercept(chain)
                    headers += chain.requests[0].header("Authorization")
                }.apply { start() }
            }
        start.countDown()
        assertTrue("threads did not enter intercept", entered.await(10, TimeUnit.SECONDS))
        tokenReady.countDown()
        threads.forEach { it.join(10_000) }

        // One page walk served all five concurrent requests. The previous
        // implementation walked the pages once per request, serialized
        // behind a lock held across the network I/O (and no OkHttp
        // timeout covers interceptor execution).
        assertEquals(1, pageFetches.get())
        assertEquals(1, maxInFlight.get())
        assertEquals(5, headers.count { it == "Bearer T1" })
    }

    @Test
    fun tokenWalk_isBoundedByTheTotalDeadline() {
        // 24 pages, each "slow" (30 ms of fake time). Without a whole-walk
        // deadline the first failed walk would try EVERY page (24 × 15 s of
        // real timeouts ≈ 6 min of stall before the backoff engaged). The
        // budget must cut the walk short and feed the failure into the
        // backoff. Deterministic: injected clock + pageCall seam, no
        // Thread.sleep.
        val urls = (1..24).map { "https://city$it.citybus.gr/en/stops" }
        var fakeNow = 1_000_000L // epoch-like baseline. 0 would collide with the lastFailureAt sentinel
        val tried = mutableListOf<String>()
        val interceptor =
            CityBusAuthInterceptor(
                tokenClient = OkHttpClient(), // unused, pageCall is injected
                tokenPageUrls = urls,
                failureBackoffMs = 1000,
                tokenFetchDeadlineMs = 50, // 50 ms total budget for the walk
                now = { fakeNow },
                pageCall = { url, _ ->
                    tried += url
                    fakeNow += 30 // each page burns 30 ms
                    throw IOException("network down")
                },
            )

        val e =
            assertThrows(IOException::class.java) {
                interceptor.intercept(FakeChain(platformRequest, ArrayDeque(listOf(response(200)))))
            }
        assertTrue(e.message!!.contains("timed out"))
        assertTrue("walk tried ${tried.size} of ${urls.size} pages", tried.size < urls.size)
        val firstWalkCalls = tried.size

        // The timeout records a failure: the next request fails fast from
        // the backoff instead of re-walking every page.
        val again =
            assertThrows(IOException::class.java) {
                interceptor.intercept(FakeChain(platformRequest, ArrayDeque(listOf(response(200)))))
            }
        assertTrue(again.message!!.contains("timed out"))
        assertEquals(firstWalkCalls, tried.size) // no additional page calls
    }
}
