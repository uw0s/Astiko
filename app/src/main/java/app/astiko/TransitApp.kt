package app.astiko

import android.app.Application
import app.astiko.data.AppPrefs
import app.astiko.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class TransitApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre 11 requires this before any MapView is created. The empty
        // key plus MapLibre server means the app brings its own style
        // (OpenFreeMap).
        MapLibre.getInstance(this, "", WellKnownTileServer.MapLibre)
        container = AppContainer(this)
        // Settings snapshot for synchronous use: the base-context locale is
        // fixed at attachBaseContext and the first Compose frame is drawn
        // before the store's first value arrives. One-time blocking read of a
        // tiny prefs file at cold start.
        runBlocking {
            container.settingsRepository.snapshot().let {
                AppPrefs.theme = it.appearance.theme
                AppPrefs.language = it.appearance.language
                AppPrefs.mapTheme = it.appearance.mapTheme
                AppPrefs.cityDecided = it.city.autoDecided
                AppPrefs.cityMode = it.city.mode
                AppPrefs.city = it.city.city
            }
        }
    }
}
