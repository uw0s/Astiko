package app.astiko.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.astiko.R
import app.astiko.TransitApp
import app.astiko.data.model.Provider

/**
 * Citymapper-style offline strip, a slim bar under the top bar, shown
 * only while the device has NO validated network. Two wordings, cached
 * data available vs nothing cached for this city, so a first-time
 * offline user in a never-browsed city sees the real state instead of
 * a silent empty app.
 *
 * Connectivity state is the signal (never a mere fetch failure), and
 * there is no ✕ button. The bar disappears by itself when connectivity
 * returns.
 */
@Composable
fun OfflineBanner(provider: Provider) {
    val app = LocalContext.current.applicationContext as TransitApp
    val online by app.container.connectivityMonitor.online
        .collectAsState()
    // Any cache mutation (write, clear, eviction) re-keys the check below,
    // so clearing the cache while offline flips the wording immediately.
    val generation by app.container.offlineCache.generation
        .collectAsState()
    // Tri-state: null = not read yet. A screen composed while already
    // offline renders the optimistic "cached" wording until the first
    // stats read lands, so a cached city never flashes "nothing cached".
    // If nothing is cached after all, the screens themselves show the
    // honest empty state right below the banner.
    var hasCache by remember(provider) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(online, generation, provider) {
        if (!online) {
            hasCache =
                app.container.offlineCache
                    .statsFor(app.container.cachePrefix(provider))
                    .entries > 0
        }
    }

    if (online) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            stringResource(
                if (hasCache == false) R.string.offline_nothing_cached else R.string.offline_cached,
            ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}
