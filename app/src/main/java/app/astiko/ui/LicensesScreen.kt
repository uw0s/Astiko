package app.astiko.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astiko.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One bundled component: what it is, its license, and the asset file
 * with the license text. The assets keep the full texts (the Apache
 * file is shared by every Apache-2.0 component).
 */
private data class LicensedComponent(
    @StringRes val nameRes: Int,
    @StringRes val licenseRes: Int,
    val assetPath: String,
    val websiteUrl: String? = null,
)

/**
 * Component list for the licensed components. Name strings live in
 * resources like everything else. The lists stay in one place so a new
 * component can't be forgotten.
 */
private val LICENSED_COMPONENTS =
    listOf(
        LicensedComponent(
            nameRes = R.string.licenses_name_astiko,
            licenseRes = R.string.licenses_license_mit,
            assetPath = "licenses/mit.txt",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_androidx,
            licenseRes = R.string.licenses_license_apache,
            assetPath = "licenses/apache-2.0.txt",
            websiteUrl = "https://developer.android.com/develop/ui/compose",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_maplibre,
            licenseRes = R.string.licenses_license_bsd,
            assetPath = "licenses/maplibre-bsd-2.txt",
            websiteUrl = "https://github.com/maplibre/maplibre-native",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_osm,
            licenseRes = R.string.licenses_license_odbl,
            assetPath = "licenses/osm-attribution.txt",
            websiteUrl = "https://www.openstreetmap.org/copyright",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_gtfs,
            licenseRes = R.string.licenses_license_ccby,
            assetPath = "licenses/cc-by-4.0.txt",
            websiteUrl =
                "https://data.gov.gr/dataset/dedomena-astikon-sygkoinonion-p-e-thessalonikis",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_openfreemap,
            licenseRes = R.string.licenses_license_tos,
            assetPath = "licenses/openfreemap.txt",
            websiteUrl = "https://openfreemap.org/tos/",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_retrofit,
            licenseRes = R.string.licenses_license_apache,
            assetPath = "licenses/apache-2.0.txt",
            websiteUrl = "https://github.com/square/retrofit",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_kotlinx,
            licenseRes = R.string.licenses_license_apache,
            assetPath = "licenses/apache-2.0.txt",
            websiteUrl = "https://github.com/Kotlin/kotlinx.serialization",
        ),
        LicensedComponent(
            nameRes = R.string.licenses_name_manrope,
            licenseRes = R.string.licenses_license_ofl,
            assetPath = "licenses/OFL-Manrope.txt",
            websiteUrl = "https://fonts.google.com/specimen/Manrope",
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var selected by remember { mutableStateOf<LicensedComponent?>(null) }
    var licenseText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) {
        val component = selected ?: return@LaunchedEffect
        licenseText = null
        licenseText =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets
                        .open(component.assetPath)
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
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
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_app_notice),
                    Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        LICENSED_COMPONENTS.forEachIndexed { index, component ->
                            SettingsRow(
                                title = stringResource(component.nameRes),
                                subtitle = stringResource(component.licenseRes),
                                onClick = { selected = component },
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            )
                            if (index < LICENSED_COMPONENTS.lastIndex) {
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { component ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Column {
                    Text(stringResource(component.nameRes))
                    Text(
                        stringResource(component.licenseRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                when {
                    licenseText == null -> {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }

                    else -> {
                        Text(
                            licenseText!!,
                            Modifier
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.close))
                }
            },
            dismissButton = {
                component.websiteUrl?.let { url ->
                    TextButton(onClick = { uriHandler.openUri(url) }) {
                        Text(stringResource(R.string.licenses_website))
                    }
                }
            },
        )
    }
}
