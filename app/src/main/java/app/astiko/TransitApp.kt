package app.astiko

import android.app.Application
import app.astiko.data.AppPrefs
import app.astiko.di.AppContainer
import kotlinx.coroutines.flow.first
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
        // Appearance snapshot for synchronous use. The activity's base context
        // (locale) is fixed at attachBaseContext, before any coroutine could
        // deliver the DataStore value. One-time blocking read of a tiny prefs
        // file at cold start. MainActivity keeps the snapshot in sync on
        // in-session changes.
        runBlocking {
            container.settingsRepository.appearance.first().let {
                AppPrefs.theme = it.theme
                AppPrefs.language = it.language
                AppPrefs.mapTheme = it.mapTheme
            }
        }
    }
}
