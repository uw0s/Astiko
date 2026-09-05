package app.astiko

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.astiko.data.AppLanguage
import app.astiko.data.AppPrefs
import app.astiko.data.AppearanceSettings
import app.astiko.data.MapThemeMode
import app.astiko.data.ThemeMode
import app.astiko.ui.TransitAppRoot
import app.astiko.ui.theme.AstikoTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as TransitApp).container

    /**
     * The app-language switch binds to the base context, and resources
     * resolve through it for the whole lifecycle. So the chosen locale
     * must be applied here, read from the [AppPrefs] snapshot (DataStore
     * is async and can't be awaited in this callback). SYSTEM leaves the
     * context untouched and the device locale applies.
     */
    override fun attachBaseContext(newBase: Context) {
        val lang =
            when (AppPrefs.language) {
                AppLanguage.EL -> "el"
                AppLanguage.EN -> "en"
                AppLanguage.SYSTEM -> null
            }
        super.attachBaseContext(lang?.let { newBase.withLanguage(it) } ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme follows SYSTEM dark mode (values-night). When the
        // user forces the opposite, the cold-start window background must match
        // what Compose will paint, or the launch frame flashes the wrong scheme.
        // setTheme before the decor is created wins over the manifest theme.
        val sysDark =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val effectiveDark =
            when (AppPrefs.theme) {
                ThemeMode.SYSTEM -> sysDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
        if (effectiveDark != sysDark) {
            setTheme(if (effectiveDark) R.style.Theme_Astiko_Night else R.style.Theme_Astiko_Day)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(edgeToEdgeStyle(effectiveDark), edgeToEdgeStyle(effectiveDark))
        setContent {
            // Initial = the boot snapshot, so the first emission (same
            // values) never triggers a recreate.
            val appearance by container.settingsRepository.appearance.collectAsState(
                initial = AppearanceSettings(AppPrefs.theme, AppPrefs.language, AppPrefs.mapTheme),
            )
            // In-app language switch. The locale is baked into the base context
            // at attachBaseContext, so the activity must be recreated. That
            // re-resolves every string, date format and provider request
            // language. The snapshot is updated first so the new
            // attachBaseContext and langProvider see the new value.
            // Theme and mapTheme sync along, because the recreate's onCreate
            // re-reads AppPrefs.theme for the cold-start window scheme
            // (setTheme). A stale boot-time value would flash the wrong
            // background for one frame. Theme changes apply live through
            // Compose without a recreate, but the snapshot must still match
            // whenever a recreate happens.
            LaunchedEffect(appearance) {
                val languageChanged = appearance.language != AppPrefs.language
                AppPrefs.language = appearance.language
                AppPrefs.theme = appearance.theme
                AppPrefs.mapTheme = appearance.mapTheme
                if (languageChanged) recreate()
            }
            val darkTheme =
                when (appearance.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            // The app theme resolved against the in-app map override (SYSTEM
            // follows the app theme). Passed into AstikoTheme so the map
            // screens read it via LocalMapDarkTheme.
            val mapDarkTheme =
                when (appearance.mapTheme) {
                    MapThemeMode.SYSTEM -> darkTheme
                    MapThemeMode.LIGHT -> false
                    MapThemeMode.DARK -> true
                }
            // Re-style the system bars on theme changes. The onCreate
            // styled them from the boot-time theme. A forced dark theme on a
            // light system would otherwise leave dark icons on the dark
            // background.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(edgeToEdgeStyle(darkTheme), edgeToEdgeStyle(darkTheme))
            }
            AstikoTheme(darkTheme = darkTheme, mapDarkTheme = mapDarkTheme) {
                TransitAppRoot()
            }
        }
    }
}

/** Transparent bars with the icon contrast of the effective theme. */
private fun edgeToEdgeStyle(dark: Boolean): SystemBarStyle =
    if (dark) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }

private fun Context.withLanguage(lang: String): Context {
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(lang))
    return createConfigurationContext(config)
}
