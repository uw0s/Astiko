package app.astiko.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import app.astiko.R

enum class MainTab(
    @StringRes val labelRes: Int,
    val icon: @Composable () -> Unit,
) {
    // Same pin the stops screens use for empty states and location
    // prompts.
    STOPS(R.string.tab_stops, {
        Icon(Icons.Filled.LocationOn, contentDescription = null)
    }),

    // Bus glyph for the lines tab. The bus is a drawable because the
    // programmatic ImageVector builder renders nothing. The arrivals
    // screen reuses it too. contentDescription stays null. The tab label
    // already names the destination, TalkBack would read it twice.
    LINES(R.string.tab_lines, {
        Icon(painterResource(R.drawable.ic_bus), contentDescription = null)
    }),
    SETTINGS(R.string.tab_settings, { Icon(Icons.Filled.Settings, contentDescription = null) }),
}
