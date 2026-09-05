package app.astiko.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.astiko.R
import app.astiko.data.AppLanguage
import app.astiko.data.AppearanceSettings
import app.astiko.data.MapThemeMode
import app.astiko.data.ThemeMode

/**
 * Theme + map + language pickers, the same card on the settings screen
 * and at the top of onboarding. Each setting is an M3 segmented control
 * with three options: follow the system, or force light/dark (greek/
 * english). The map's System option follows the APP theme, so a forced
 * Dark app theme pulls the map dark too.
 */
@Composable
fun AppearancePrefsCard(
    appearance: AppearanceSettings,
    onThemeSelect: (ThemeMode) -> Unit,
    onMapThemeSelect: (MapThemeMode) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pref_theme),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SegmentedPrefRow(
                options = ThemeMode.entries,
                selected = appearance.theme,
                label = { mode -> stringResource(themeModeLabelRes(mode)) },
                onSelect = onThemeSelect,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.pref_map_theme),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SegmentedPrefRow(
                options = MapThemeMode.entries,
                selected = appearance.mapTheme,
                label = { mode -> stringResource(mapThemeModeLabelRes(mode)) },
                onSelect = onMapThemeSelect,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.pref_language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SegmentedPrefRow(
                options = AppLanguage.entries,
                selected = appearance.language,
                label = { lang -> stringResource(languageLabelRes(lang)) },
                onSelect = onLanguageSelect,
            )
        }
    }
}

@Composable
internal fun <T> SegmentedPrefRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@StringRes
private fun themeModeLabelRes(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

@StringRes
private fun mapThemeModeLabelRes(mode: MapThemeMode): Int =
    when (mode) {
        MapThemeMode.SYSTEM -> R.string.theme_system
        MapThemeMode.LIGHT -> R.string.theme_light
        MapThemeMode.DARK -> R.string.theme_dark
    }

@StringRes
private fun languageLabelRes(language: AppLanguage): Int =
    when (language) {
        AppLanguage.SYSTEM -> R.string.lang_system
        AppLanguage.EL -> R.string.lang_greek
        AppLanguage.EN -> R.string.lang_english
    }
