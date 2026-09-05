package app.astiko.di

import android.content.Context
import android.util.Log
import app.astiko.BuildConfig
import app.astiko.data.AppLanguage
import app.astiko.data.AppPrefs
import app.astiko.data.FavoritesRepository
import app.astiko.data.SettingsRepository
import app.astiko.data.TransitRepository
import app.astiko.data.cache.CachedTransitRepository
import app.astiko.data.cache.OfflineCache
import app.astiko.data.cache.OfflinePrefetcher
import app.astiko.data.citybus.CityBusApi
import app.astiko.data.citybus.CityBusAuthInterceptor
import app.astiko.data.citybus.CityBusRepository
import app.astiko.data.favoritesDataStore
import app.astiko.data.model.Provider
import app.astiko.data.oasa.OasaApi
import app.astiko.data.oasa.OasaRepository
import app.astiko.data.oseth.OseThApi
import app.astiko.data.oseth.OseThGtfsCatalog
import app.astiko.data.oseth.OseThRepository
import app.astiko.data.settingsDataStore
import app.astiko.util.ConnectivityMonitor
import app.astiko.util.LocationProvider
import app.astiko.util.runCatchingNotCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container. Every layer grabs what it needs from
 * here. Can be swapped for Hilt later without touching the UI.
 */
class AppContainer(
    context: Context,
) {
    /** Lenient JSON: unknown keys are ignored, missing fields get defaults. */
    val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    private val baseHttp =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    private val okHttp =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    // BASIC logs every request URL to logcat (debug builds
                    // only). Release builds must not leak request URLs (stop
                    // ids, city paths) into logcat.
                    level =
                        if (BuildConfig.DEBUG) {
                            HttpLoggingInterceptor.Level.BASIC
                        } else {
                            HttpLoggingInterceptor.Level.NONE
                        }
                },
            )
            // CityBus needs `Authorization: Bearer <JWT>` on every call. The
            // interceptor scrapes the token from a city app page and refreshes
            // it once on 401. Token fetches go through baseHttp (no
            // interceptors), so they can never re-enter this one. One token
            // serves the whole platform, so the page list is a fallback
            // chain, not per-city auth.
            .addInterceptor(
                CityBusAuthInterceptor(
                    baseHttp,
                    listOf(
                        CITYBUS_TOKEN_PAGE_LARISSA,
                        CITYBUS_TOKEN_PAGE_XANTHI,
                        CITYBUS_TOKEN_PAGE_IRAKLIO,
                        CITYBUS_TOKEN_PAGE_IOANNINA,
                        CITYBUS_TOKEN_PAGE_PATRA,
                        CITYBUS_TOKEN_PAGE_CHANIA,
                        CITYBUS_TOKEN_PAGE_VOLOS,
                        CITYBUS_TOKEN_PAGE_CORFU,
                        CITYBUS_TOKEN_PAGE_SALAMINA,
                        CITYBUS_TOKEN_PAGE_KAVALA,
                        CITYBUS_TOKEN_PAGE_CHALKIDA,
                        CITYBUS_TOKEN_PAGE_SERRES,
                        CITYBUS_TOKEN_PAGE_KATERINI,
                        CITYBUS_TOKEN_PAGE_MITILINI,
                        CITYBUS_TOKEN_PAGE_ALEXANDROUPOLI,
                        CITYBUS_TOKEN_PAGE_PTOLEMAIDA,
                        CITYBUS_TOKEN_PAGE_KOZANI,
                        CITYBUS_TOKEN_PAGE_LAMIA,
                        CITYBUS_TOKEN_PAGE_AGRINIO,
                        CITYBUS_TOKEN_PAGE_CHIOS,
                        CITYBUS_TOKEN_PAGE_KOMOTINI,
                        CITYBUS_TOKEN_PAGE_ARTA,
                        CITYBUS_TOKEN_PAGE_VEROIA,
                        CITYBUS_TOKEN_PAGE_MESOLOGGI,
                    ),
                ),
            ).build()

    /** One Retrofit client per provider platform: same client, same
     *  converter, different base URL. */
    private fun buildRetrofit(baseUrl: String): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    private val retrofit = buildRetrofit("https://telematics.oasa.gr/api/")
    private val oseThRetrofit = buildRetrofit("https://oseth.com.gr/")
    private val cityBusRetrofit = buildRetrofit("https://rest.citybus.gr/")

    val favoritesRepository = FavoritesRepository(context.applicationContext.favoritesDataStore)
    val settingsRepository = SettingsRepository(context.applicationContext.settingsDataStore)
    val locationProvider = LocationProvider(context.applicationContext)

    /** Disk cache + connectivity state backing the offline feature. Every
     *  adapter below is wrapped in a CachedTransitRepository that serves
     *  cached data when the network is missing or an API call fails. */
    val offlineCache = OfflineCache(context.applicationContext)
    val connectivityMonitor = ConnectivityMonitor(context.applicationContext)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedRepositories = mutableListOf<CachedTransitRepository>()

    private fun cached(repo: TransitRepository): TransitRepository =
        CachedTransitRepository(repo, offlineCache, connectivityMonitor.online, langProvider)
            .also { cachedRepositories += it }

    /**
     * The app language, resolved at call time. "el"/"en" from the in-app
     * language setting, or the device locale for System (the app default).
     * Read per request so a language switch mid-process picks up
     * immediately. MainActivity refreshes [AppPrefs] before recreating.
     */
    private val appContext = context.applicationContext

    /** One GET for the GTFS catalog: the CKAN resource list (tiny JSON)
     *  and the feed zip. data.gov.gr needs no auth. Any failure returns
     *  null so the loader keeps serving the local index (or the adapter
     *  falls back to the telematics walk). */
    private suspend fun gtfsFetch(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatchingNotCancelled {
                okHttp
                    .newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) null else response.body.bytes()
                    }
            }.getOrNull()
        }

    private val langProvider: () -> String = {
        when (AppPrefs.language) {
            AppLanguage.EL -> {
                "el"
            }

            AppLanguage.EN -> {
                "en"
            }

            AppLanguage.SYSTEM -> {
                if (appContext.resources.configuration.locales[0]
                        .language == "en"
                ) {
                    "en"
                } else {
                    "el"
                }
            }
        }
    }

    /** One distinct tenant of the shared citybus.gr platform. The agency
     *  id and the provider are the only per-city inputs. The api, json and
     *  language are platform-wide. */
    private fun cityBus(
        agency: String,
        provider: Provider,
        supportsTimetables: Boolean = true,
    ): TransitRepository =
        CityBusRepository(
            cityBusRetrofit.create(CityBusApi::class.java),
            json,
            agency = agency,
            langProvider = langProvider,
            provider = provider,
            supportsTimetables = supportsTimetables,
        )

    /** One adapter per provider, each wrapped in the offline cache
     *  decorator, the normalization layer. */
    private val repositories: Map<Provider, TransitRepository> =
        mapOf(
            Provider.OASA to
                OasaRepository(retrofit.create(OasaApi::class.java), json, langProvider),
            Provider.OSETh to
                OseThRepository(
                    oseThRetrofit.create(OseThApi::class.java),
                    json,
                    langProvider,
                    gtfsCatalog =
                        OseThGtfsCatalog(
                            json = json,
                            dir = File(appContext.filesDir, "oseth_gtfs"),
                            fetch = ::gtfsFetch,
                        ),
                ),
            // One platform serves all CityBus cities. A new city is a new
            // entry with its agency id (plus a token page in the fallback
            // chain above).
            Provider.CITYBUS to cityBus(CITYBUS_AGENCY_LARISSA, Provider.CITYBUS),
            Provider.CITYBUS_XANTHI to cityBus(CITYBUS_AGENCY_XANTHI, Provider.CITYBUS_XANTHI),
            Provider.CITYBUS_IRAKLIO to cityBus(CITYBUS_AGENCY_IRAKLIO, Provider.CITYBUS_IRAKLIO),
            Provider.CITYBUS_IOANNINA to cityBus(CITYBUS_AGENCY_IOANNINA, Provider.CITYBUS_IOANNINA),
            Provider.CITYBUS_PATRA to cityBus(CITYBUS_AGENCY_PATRA, Provider.CITYBUS_PATRA),
            Provider.CITYBUS_CHANIA to cityBus(CITYBUS_AGENCY_CHANIA, Provider.CITYBUS_CHANIA),
            Provider.CITYBUS_VOLOS to cityBus(CITYBUS_AGENCY_VOLOS, Provider.CITYBUS_VOLOS),
            Provider.CITYBUS_CORFU to cityBus(CITYBUS_AGENCY_CORFU, Provider.CITYBUS_CORFU),
            Provider.CITYBUS_SALAMINA to cityBus(CITYBUS_AGENCY_SALAMINA, Provider.CITYBUS_SALAMINA),
            Provider.CITYBUS_KAVALA to cityBus(CITYBUS_AGENCY_KAVALA, Provider.CITYBUS_KAVALA),
            Provider.CITYBUS_CHALKIDA to cityBus(CITYBUS_AGENCY_CHALKIDA, Provider.CITYBUS_CHALKIDA),
            Provider.CITYBUS_SERRES to cityBus(CITYBUS_AGENCY_SERRES, Provider.CITYBUS_SERRES),
            Provider.CITYBUS_KATERINI to cityBus(CITYBUS_AGENCY_KATERINI, Provider.CITYBUS_KATERINI),
            Provider.CITYBUS_MITILINI to cityBus(CITYBUS_AGENCY_MITILINI, Provider.CITYBUS_MITILINI),
            Provider.CITYBUS_ALEXANDROUPOLI to cityBus(CITYBUS_AGENCY_ALEXANDROUPOLI, Provider.CITYBUS_ALEXANDROUPOLI),
            Provider.CITYBUS_PTOLEMAIDA to cityBus(CITYBUS_AGENCY_PTOLEMAIDA, Provider.CITYBUS_PTOLEMAIDA),
            Provider.CITYBUS_KOZANI to cityBus(CITYBUS_AGENCY_KOZANI, Provider.CITYBUS_KOZANI),
            Provider.CITYBUS_LAMIA to cityBus(CITYBUS_AGENCY_LAMIA, Provider.CITYBUS_LAMIA),
            Provider.CITYBUS_AGRINIO to
                cityBus(
                    CITYBUS_AGENCY_AGRINIO,
                    Provider.CITYBUS_AGRINIO,
                    // Agrinio publishes no schedules. trips/stop/.../day/...
                    // 404s on every stop/day, so the timetable entry points
                    // are hidden.
                    supportsTimetables = false,
                ),
            Provider.CITYBUS_CHIOS to cityBus(CITYBUS_AGENCY_CHIOS, Provider.CITYBUS_CHIOS),
            Provider.CITYBUS_KOMOTINI to cityBus(CITYBUS_AGENCY_KOMOTINI, Provider.CITYBUS_KOMOTINI),
            Provider.CITYBUS_ARTA to cityBus(CITYBUS_AGENCY_ARTA, Provider.CITYBUS_ARTA),
            Provider.CITYBUS_VEROIA to cityBus(CITYBUS_AGENCY_VEROIA, Provider.CITYBUS_VEROIA),
            Provider.CITYBUS_MESOLOGGI to
                cityBus(
                    CITYBUS_AGENCY_MESOLOGGI,
                    Provider.CITYBUS_MESOLOGGI,
                    // Same as Agrinio. This tenant publishes no schedules,
                    // and trips/stop/... 404s on every stop/day.
                    supportsTimetables = false,
                ),
        )
            // Wrap after construction, so every adapter gets the same
            // read-through cache decorator. The UI never sees the difference.
            .mapValues { (_, repo) -> cached(repo) }

    fun repository(provider: Provider): TransitRepository = repositories.getValue(provider)

    /** Cache namespace of a provider in the current app language
     *  (`provider-lang`). Used by the offline banner and the settings
     *  cache section to ask the store about this city's data. */
    fun cachePrefix(provider: Provider): String = OfflineCache.prefix(provider, langProvider())

    /** "Download city data for offline" (settings row). Walks the city's
     *  catalog through the decorators, writing every fetch into the cache.
     *  Wi-Fi-only. The gate combines the validated-network state with the
     *  transport check (ConnectivityMonitor.onValidatedWifi). */
    val offlinePrefetcher =
        OfflinePrefetcher(
            repository = { provider -> repository(provider) },
            wifiCheck = {
                connectivityMonitor.online.value && connectivityMonitor.onValidatedWifi()
            },
        )

    init {
        // Wired after [repositories] is fully built. The collectors below
        // iterate [cachedRepositories] on appScope (Dispatchers.IO). An
        // append during that iteration would be a race (partial iteration or
        // a ConcurrentModificationException), so the list is immutable from
        // here on.
        // Keep the offline stop catalog warm with favorites. A favorite must
        // open offline even if its stop was never seen in a nearby/route
        // response.
        appScope.launch {
            favoritesRepository.favoriteStops.collect { favorites ->
                favorites.forEach { stop ->
                    cachedRepositories
                        .firstOrNull { it.provider == stop.provider }
                        ?.warmStop(stop)
                }
            }
        }
        // Connectivity return. Refresh whatever the user browsed this
        // process that aged past its TTL. Each decorator's refreshExpired
        // re-runs the registered fetches. Fresh entries serve from cache
        // (no network), so only stale or evicted entries hit the
        // APIs.
        appScope.launch {
            // StateFlow only re-emits on value CHANGE, so this fires on the
            // false->true transition (and once at start, when the registry
            // is still empty, a no-op).
            connectivityMonitor.online.collect { online ->
                if (online && cachedRepositories.isNotEmpty()) {
                    Log.i(TAG, "connectivity returned, refreshing expired cache entries")
                    cachedRepositories.forEach { repo ->
                        runCatchingNotCancelled { repo.refreshExpired() }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AppContainer"

        // CityBus platform tenants, agencies and token pages per city. A new
        // city is an agency id plus a token page below.
        private const val CITYBUS_AGENCY_LARISSA = "102"
        private const val CITYBUS_AGENCY_XANTHI = "104"
        private const val CITYBUS_AGENCY_IRAKLIO = "110"
        private const val CITYBUS_AGENCY_IOANNINA = "106"
        private const val CITYBUS_AGENCY_PATRA = "112"
        private const val CITYBUS_AGENCY_CHANIA = "120"
        private const val CITYBUS_AGENCY_VOLOS = "103"
        private const val CITYBUS_AGENCY_CORFU = "101"
        private const val CITYBUS_AGENCY_SALAMINA = "113"
        private const val CITYBUS_AGENCY_KAVALA = "123"
        private const val CITYBUS_AGENCY_CHALKIDA = "133"
        private const val CITYBUS_AGENCY_SERRES = "117"
        private const val CITYBUS_AGENCY_KATERINI = "118"
        private const val CITYBUS_AGENCY_MITILINI = "122"
        private const val CITYBUS_AGENCY_ALEXANDROUPOLI = "107"
        private const val CITYBUS_AGENCY_PTOLEMAIDA = "128"
        private const val CITYBUS_AGENCY_KOZANI = "121"
        private const val CITYBUS_AGENCY_LAMIA = "114"
        private const val CITYBUS_AGENCY_AGRINIO = "130"
        private const val CITYBUS_AGENCY_CHIOS = "127"
        private const val CITYBUS_AGENCY_KOMOTINI = "105"
        private const val CITYBUS_AGENCY_ARTA = "125"
        private const val CITYBUS_AGENCY_VEROIA = "129"
        private const val CITYBUS_AGENCY_MESOLOGGI = "109"
        private const val CITYBUS_TOKEN_PAGE_LARISSA = "https://larisa.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_XANTHI = "https://xanthi.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_IRAKLIO = "https://irakleio.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_IOANNINA = "https://ioannina.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_PATRA = "https://patra.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_CHANIA = "https://chania.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_VOLOS = "https://volos.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_CORFU = "https://corfu.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_SALAMINA = "https://salamina.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_KAVALA = "https://kavala.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_CHALKIDA = "https://chalkida.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_SERRES = "https://serres.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_KATERINI = "https://katerini.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_MITILINI = "https://mitilini.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_ALEXANDROUPOLI = "https://alexandroupoli.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_PTOLEMAIDA = "https://ptolemaida.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_KOZANI = "https://kozani.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_LAMIA = "https://lamia.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_AGRINIO = "https://agrinio.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_CHIOS = "https://chios.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_KOMOTINI = "https://komotini.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_ARTA = "https://arta.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_VEROIA = "https://veroia.citybus.gr/en/stops"
        private const val CITYBUS_TOKEN_PAGE_MESOLOGGI = "https://mesologgi.citybus.gr/en/stops"
    }
}
