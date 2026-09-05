package app.astiko.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astiko.R
import app.astiko.data.CityMode
import app.astiko.data.model.City
import app.astiko.util.hasLocationPermission
import app.astiko.util.normalizeSearchText

/**
 * The city picker lives on a dedicated drill-in screen (the sheet grew
 * past the sheet max height and turned into a fullscreen sheet, which
 * behaves like neither a sheet nor a screen). Search is the point: the
 * list is filtered as you type against the localized name, the Greek
 * label, and a Latin transliteration (so "veria" finds Βέροια on a
 * Greek UI).
 *
 * Deliberately takes city/mode/callbacks as parameters instead of
 * creating its own [CityViewModel]. A second instance would double the
 * GPS tracking in AUTO mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
// The resource read inside remember is safe. The app's language is
// baked into the base context at attachBaseContext, and a switch
// recreates the whole activity, so the city-name resources can never
// go stale mid-composition. Lint cannot see that lifecycle.
@SuppressLint("LocalContextGetResourceValueCall")
fun CityPickerScreen(
    city: City,
    mode: CityMode,
    onSelectCity: (City) -> Unit,
    onSelectAuto: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val filtered =
        remember(query, context) {
            filterCities(query, localizedName = { context.getString(cityNameRes(it)) })
        }

    // "Follow GPS" needs location permission. The onboarding path
    // asks first, but this screen must too. A denied user would otherwise
    // silently stick on Athens with GPS tracking no-oping. Request FINE.
    // A COARSE-only grant (Android 12+ "Approximate") is enough for city
    // detection.
    var autoDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted || hasLocationPermission(context)) {
                autoDenied = false
                onSelectAuto()
            } else {
                autoDenied = true
            }
        }

    fun chooseAuto() {
        if (hasLocationPermission(context)) {
            autoDenied = false
            onSelectAuto()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.choose_city)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // imePadding: same IME-inset rule as the stop search screen (see
        // there). The picker's list must stay above the keyboard. The
        // navbar is consumed first: the IME inset already covers it when
        // the keyboard is up, padding by both adds an empty strip.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(WindowInsets.navigationBars)
                .imePadding(),
        ) {
            // Filled tonal pill: the Material 3 search pattern, same as
            // the Γραμμές tab's search bar.
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_city)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
            )

            // Pinned above the list, hidden while searching (it doesn't
            // match a city query).
            if (query.isBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ListRowShape)
                        .clickable(onClick = ::chooseAuto)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == CityMode.AUTO, onClick = ::chooseAuto)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.auto_gps))
                        Text(
                            stringResource(R.string.auto_gps_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (autoDenied) {
                    // Compact denial hint + retry (the system dialog may be
                    // re-shown until "don't ask again" is checked).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.auto_gps_needs_location),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = ::chooseAuto) {
                            Text(stringResource(R.string.allow_location))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }

            if (filtered.isEmpty()) {
                // Shared empty-state pattern (icon + title), centered in the
                // space below the search field.
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.no_city_results),
                    modifier = Modifier.weight(1f),
                    center = true,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.name }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ListRowShape)
                                .clickable { onSelectCity(c) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == CityMode.MANUAL && city == c,
                                onClick = { onSelectCity(c) },
                            )
                            // Same two-line recipe as the onboarding city
                            // rows: name on top, operator underneath.
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(c.displayName(), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(operatorNameRes(c)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * City search: matches the query case-insensitively against the localized
 * display name, the Greek label, and the Latin transliteration, so "ver"
 * finds Βέροια on a Greek UI and "βερ" works on an English one. Greek
 * diacritics are stripped on both sides, so an unaccented query ("βερ",
 * "αρτα") still finds the accented label (Βέροια, Άρτα). A blank query
 * returns the full list.
 */
internal fun filterCities(
    query: String,
    localizedName: (City) -> String,
): List<City> {
    val q = normalizeSearchText(query.trim())
    if (q.isEmpty()) return City.pickerOrder
    return City.pickerOrder.filter { c ->
        normalizeSearchText(localizedName(c)).contains(q) ||
            normalizeSearchText(c.label).contains(q) ||
            normalizeSearchText(latinSearchAlias(c)).contains(q)
    }
}

/** English names for cross-locale search ("athens" finds Αθήνα on a Greek UI). */
internal fun latinSearchAlias(city: City): String =
    when (city) {
        City.ATHENS -> "athens"
        City.THESSALONIKI -> "thessaloniki"
        City.LARISSA -> "larissa"
        City.XANTHI -> "xanthi"
        City.IRAKLIO -> "heraklion"
        City.IOANNINA -> "ioannina"
        City.PATRA -> "patras"
        City.CHANIA -> "chania"
        City.VOLOS -> "volos"
        City.CORFU -> "corfu"
        City.SALAMINA -> "salamina"
        City.KAVALA -> "kavala"
        City.CHALKIDA -> "chalkida"
        City.SERRES -> "serres"
        City.KATERINI -> "katerini"
        City.MITILINI -> "mytilene"
        City.ALEXANDROUPOLI -> "alexandroupoli"
        City.PTOLEMAIDA -> "ptolemaida"
        City.KOZANI -> "kozani"
        City.LAMIA -> "lamia"
        City.AGRINIO -> "agrinio"
        City.CHIOS -> "chios"
        City.KOMOTINI -> "komotini"
        City.ARTA -> "arta"
        City.VEROIA -> "veroia"
        City.MESOLOGGI -> "mesologgi"
    }
