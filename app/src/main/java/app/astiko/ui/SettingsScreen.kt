package app.astiko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astiko.R
import app.astiko.TransitApp
import app.astiko.data.AppPrefs
import app.astiko.data.AppearanceSettings
import app.astiko.data.cache.OfflineCache
import app.astiko.data.cache.PrefetchStatus
import app.astiko.data.model.City
import app.astiko.ui.theme.AstikoBlue
import kotlinx.coroutines.launch

/** Settings tab: app info and the data sources behind it. */
@Composable
fun SettingsScreen(
    city: City,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "–"
        }

    // Offline-data section state, declared here so the clear-confirmation
    // dialog below can reach it. Cache stats of the current city, passed in
    // by TransitAppRoot, so this screen shares the root's CityViewModel
    // instead of creating its own. A second instance would double the GPS
    // tracking in AUTO mode.
    val app = LocalContext.current.applicationContext as TransitApp
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val appearance by app.container.settingsRepository.appearance.collectAsState(
        initial = AppearanceSettings(AppPrefs.theme, AppPrefs.language, AppPrefs.mapTheme),
    )
    var cacheStats by remember { mutableStateOf<OfflineCache.PrefixStats?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val prefetchStatus by app.container.offlinePrefetcher.status
        .collectAsState()
    // A status belongs to the city it was started for, the prefetcher is a
    // singleton. Larissa's finished download must not show as "done" on
    // Patras's section, where nothing is cached. Anything not belonging to
    // the current city reads as Idle.
    val statusForThisCity =
        prefetchStatus.takeIf { status ->
            status.provider == null || status.provider == city.provider
        } ?: PrefetchStatus.Idle
    LaunchedEffect(city, prefetchStatus) {
        // Reload the numbers when a prefetch run settles (Done/Failed/
        // NeedsWifi). The disk cache grew while it ran, so the rows would
        // otherwise keep the pre-run size until the screen recomposed. While
        // Running the effect restarts per progress tick but does no work.
        // The final reload happens at the end.
        if (prefetchStatus !is PrefetchStatus.Running) {
            cacheStats =
                app.container.offlineCache.statsFor(app.container.cachePrefix(city.provider))
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // App header
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AstikoBlue),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.version, versionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.settings_offline),
            Modifier.fillMaxWidth().padding(start = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                // "Download city data for offline". Walks the catalog through
                // the cache decorators, so the disk cache is the download.
                // Re-running it later only refetches entries past their TTL.
                TwoLineRow(
                    onClick = { app.container.offlinePrefetcher.start(city) },
                    headline = {
                        Text(
                            stringResource(R.string.settings_download_city),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supporting = {
                        when (val s = statusForThisCity) {
                            PrefetchStatus.Idle -> {
                                Text(
                                    stringResource(R.string.settings_download_city_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is PrefetchStatus.Running -> {
                                Text(
                                    if (s.total > 0) {
                                        stringResource(
                                            R.string.prefetch_running,
                                            (s.done * 100 / s.total).coerceIn(0, 100),
                                        )
                                    } else {
                                        // Pass 1 (counting directions). The
                                        // total is unknown yet, no percentage.
                                        stringResource(R.string.prefetch_working)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is PrefetchStatus.Done -> {
                                Text(
                                    stringResource(R.string.prefetch_done),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is PrefetchStatus.NeedsWifi -> {
                                Text(
                                    stringResource(R.string.prefetch_needs_wifi),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is PrefetchStatus.Failed -> {
                                Text(
                                    s.message ?: stringResource(R.string.prefetch_failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    trailing = {
                        if (statusForThisCity is PrefetchStatus.Running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
                val runningStatus = statusForThisCity as? PrefetchStatus.Running
                if (runningStatus != null) {
                    if (runningStatus.total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (runningStatus.done.toFloat() / runningStatus.total).coerceIn(
                                    0f,
                                    1f,
                                )
                            },
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp,
                                ),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp,
                                ),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                PlainTwoLineRow(
                    headline = {
                        Text(
                            stringResource(R.string.settings_cache_data),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supporting = {
                        val stats = cacheStats
                        Text(
                            if (stats == null ||
                                stats.entries == 0
                            ) {
                                stringResource(R.string.cache_empty)
                            } else {
                                "${formatBytes(stats.bytes)} · " +
                                    pluralStringResource(
                                        R.plurals.cache_entries,
                                        stats.entries,
                                        stats.entries,
                                    )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailing = {},
                )
                cacheStats?.takeIf { it.entries > 0 }?.newestAt?.let { newest ->
                    PlainTwoLineRow(
                        headline = {
                            Text(
                                stringResource(R.string.settings_last_sync),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supporting = {
                            Text(
                                formatTimestamp(newest, stringResource(R.string.date_time_format)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailing = {},
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_clear_cache))
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.settings_appearance),
            Modifier.fillMaxWidth().padding(start = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        AppearancePrefsCard(
            appearance = appearance,
            onThemeSelect = { scope.launch { app.container.settingsRepository.setThemeMode(it) } },
            onMapThemeSelect = {
                scope.launch {
                    app.container.settingsRepository.setMapThemeMode(
                        it,
                    )
                }
            },
            onLanguageSelect = {
                scope.launch {
                    app.container.settingsRepository.setLanguage(
                        it,
                    )
                }
            },
        )

        Spacer(Modifier.height(24.dp))

        // Data sources
        Text(
            stringResource(R.string.data_sources),
            Modifier.fillMaxWidth().padding(start = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            // Same order as the city pickers: `pickerOrder` (2021 census
            // population, largest first).
            Column {
                City.pickerOrder.forEachIndexed { index, c ->
                    SettingsRow(
                        title = stringResource(operatorNameRes(c)),
                        onClick = { uriHandler.openUri(sourceUrl(c)) },
                        subtitle = stringResource(sourceSubtitleRes(c)),
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        iconDescription = stringResource(R.string.open_website),
                    )
                    if (index < City.pickerOrder.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
                // Open GTFS feed: the Thessaloniki stop catalog source.
                HorizontalDivider(Modifier.padding(start = 16.dp))
                SettingsRow(
                    title = stringResource(R.string.src_gtfs),
                    onClick = { uriHandler.openUri(GTFS_DATASET_URL) },
                    subtitle = stringResource(R.string.src_gtfs_subtitle),
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    iconDescription = stringResource(R.string.open_website),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Licenses (third-party notices, Play/F-Droid requirement)
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SettingsRow(
                title = stringResource(R.string.licenses_title),
                onClick = onOpenLicenses,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            )
        }

        // App links (landing page, privacy policy)
        Surface(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                SettingsRow(
                    title = stringResource(R.string.app_website),
                    onClick = { uriHandler.openUri(LANDING_URL) },
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    iconDescription = stringResource(R.string.open_website),
                )
                HorizontalDivider(Modifier.padding(start = 16.dp))
                SettingsRow(
                    title = stringResource(R.string.privacy_policy),
                    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    iconDescription = stringResource(R.string.open_website),
                )
            }
        }

        // Disclaimer
        Surface(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(Modifier.padding(16.dp)) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(top = 1.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.unofficial_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_clear_cache_title)) },
            text = { Text(stringResource(R.string.settings_clear_cache_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        app.container.offlineCache.clear()
                        cacheStats =
                            app.container.offlineCache.statsFor(
                                app.container.cachePrefix(city.provider),
                            )
                    }
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val LANDING_URL = "https://astiko.app"
private const val PRIVACY_POLICY_URL = "https://astiko.app/privacy"

/** The OSETh GTFS dataset on the Greek open-data portal (CC BY 4.0). */
private const val GTFS_DATASET_URL =
    "https://data.gov.gr/dataset/dedomena-astikon-sygkoinonion-p-e-thessalonikis"

/** Official agency site per city (info-screen rows). */
private fun sourceUrl(city: City): String =
    when (city) {
        City.ATHENS -> "https://www.oasa.gr"
        City.THESSALONIKI -> "https://www.oseth.com.gr"
        City.LARISSA -> "https://ktelast-larisas.gr"
        City.XANTHI -> "https://astikoxanthis.gr"
        City.IRAKLIO -> "https://astiko-irakleiou.gr"
        City.IOANNINA -> "https://astiko-ioannina.gr"
        City.PATRA -> "https://astikopatras.gr"
        City.CHANIA -> "https://chaniabus.gr"
        City.VOLOS -> "https://astikovolou.gr"
        City.CORFU -> "https://astikoktelkerkyras.gr"
        City.SALAMINA -> "https://ktelsalaminas.gr"
        City.KAVALA -> "https://astiko-kavalas.gr"
        City.CHALKIDA -> "https://astikochalkidas.gr"
        City.SERRES -> "https://astikoktelserron.gr"
        City.KATERINI -> "https://astika-katerinis.gr"
        City.MITILINI -> "https://astika-mitilinis.gr"
        City.ALEXANDROUPOLI -> "https://astikoktel.gr"
        City.PTOLEMAIDA -> "https://ptolemaida.citybus.gr"
        City.KOZANI -> "https://astikoktelkozanis.gr"
        City.LAMIA -> "https://astikoktellamias.gr"
        City.AGRINIO -> "https://agrinio.citybus.gr"
        City.CHIOS -> "https://chioscitybus.gr"
        City.KOMOTINI -> "https://astikakomotinis.gr"
        City.ARTA -> "https://arta.citybus.gr"
        City.VEROIA -> "https://astikoverias.gr"
        City.MESOLOGGI -> "https://mesologgi.citybus.gr" // no operator site found, the city app page is the source
    }
