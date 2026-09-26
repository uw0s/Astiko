package app.astiko.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.astiko.MainActivity
import app.astiko.R
import app.astiko.data.model.Provider
import app.astiko.data.model.Stop

/** Action of the intent a pinned stop shortcut launches. */
internal const val STOP_SHORTCUT_ACTION = "app.astiko.action.OPEN_STOP"

// Extra keys. Flat and stable, so a shortcut pinned by an older build
// keeps working when Stop gains fields.
internal const val EXTRA_PROVIDER = "app.astiko.extra.STOP_PROVIDER"
internal const val EXTRA_ID = "app.astiko.extra.STOP_ID"
internal const val EXTRA_NAME = "app.astiko.extra.STOP_NAME"
internal const val EXTRA_STREET = "app.astiko.extra.STOP_STREET"
internal const val EXTRA_LAT = "app.astiko.extra.STOP_LAT"
internal const val EXTRA_LON = "app.astiko.extra.STOP_LON"

/** What the launcher did with a pin request. */
enum class StopShortcutResult {
    /** The launcher asked the user to confirm. */
    REQUESTED,

    /** The same stop is already on the home screen. */
    ALREADY_PINNED,

    /** No launcher on this device handles pin requests. */
    UNSUPPORTED,
}

/** Identity of a stop's shortcut. Provider and id together, stop ids
 *  repeat across providers. */
internal fun stopShortcutId(stop: Stop): String = "stop-${stop.provider.name}-${stop.id}"

/**
 * The stop as flat extras. The intent extras are copied into a
 * PersistableBundle, so only its types survive and an ArrayList there
 * makes the pin call throw. The query-relative distance and the badges
 * stay out, nothing reads them back.
 */
internal fun stopShortcutExtras(stop: Stop): Map<String, Any?> =
    mapOf(
        EXTRA_PROVIDER to stop.provider.name,
        EXTRA_ID to stop.id,
        EXTRA_NAME to stop.name,
        EXTRA_STREET to stop.street,
        EXTRA_LAT to stop.lat,
        EXTRA_LON to stop.lon,
    )

/** Inverse of [stopShortcutExtras]. Null when the payload is unusable. */
internal fun stopFromShortcutExtras(extras: Map<String, Any?>): Stop? {
    val providerName = extras[EXTRA_PROVIDER] as? String
    val provider = Provider.entries.firstOrNull { it.name == providerName }
    val id = extras[EXTRA_ID] as? String
    val name = extras[EXTRA_NAME] as? String
    val lat = extras[EXTRA_LAT] as? Double
    val lon = extras[EXTRA_LON] as? Double
    if (provider == null || id.isNullOrEmpty() || name.isNullOrEmpty() || lat == null || lon == null) {
        return null
    }
    return Stop(
        provider = provider,
        id = id,
        name = name,
        street = extras[EXTRA_STREET] as? String,
        lat = lat,
        lon = lon,
    )
}

/**
 * The intent a pinned shortcut launches. CLEAR_TOP with SINGLE_TOP reuses
 * the running activity through [MainActivity.onNewIntent] instead of
 * stacking a second one, which would leave two sets of polling
 * ViewModels alive.
 */
internal fun stopShortcutIntent(
    context: Context,
    stop: Stop,
): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(STOP_SHORTCUT_ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtras(stopShortcutValues(stop))

/** The stop a shortcut intent carries, null for any other intent. */
internal fun stopFromShortcutIntent(intent: Intent?): Stop? {
    if (intent?.action != STOP_SHORTCUT_ACTION) return null
    val extras = intent.extras ?: return null
    return stopFromShortcutExtras(extras.toShortcutMap())
}

/**
 * Asks the launcher to pin [stop]. [StopShortcutResult.REQUESTED] means
 * its confirmation dialog went up, not that the icon exists. A pinned
 * shortcut can be updated by the app later, removed only by the user.
 */
internal fun requestStopShortcut(
    context: Context,
    stop: Stop,
): StopShortcutResult {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        return StopShortcutResult.UNSUPPORTED
    }
    if (isStopShortcutPinned(context, stop)) return StopShortcutResult.ALREADY_PINNED
    val shortcut =
        ShortcutInfoCompat
            .Builder(context, stopShortcutId(stop))
            .setShortLabel(stop.name)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_stop))
            .setIntent(stopShortcutIntent(context, stop))
            .build()
    val sent = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    return if (sent) StopShortcutResult.REQUESTED else StopShortcutResult.UNSUPPORTED
}

/** Whether this stop already has an icon on the home screen. */
internal fun isStopShortcutPinned(
    context: Context,
    stop: Stop,
): Boolean {
    val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
    return pinned.any { it.id == stopShortcutId(stop) }
}

/** Null for [StopShortcutResult.REQUESTED], the launcher's own dialog is
 *  the feedback there. */
internal fun stopShortcutMessage(result: StopShortcutResult): Int? =
    when (result) {
        StopShortcutResult.REQUESTED -> null
        StopShortcutResult.ALREADY_PINNED -> R.string.shortcut_exists
        StopShortcutResult.UNSUPPORTED -> R.string.shortcut_unsupported
    }

private fun stopShortcutValues(stop: Stop): Bundle =
    Bundle().apply {
        for ((key, value) in stopShortcutExtras(stop)) {
            when (value) {
                null -> Unit
                is String -> putString(key, value)
                is Double -> putDouble(key, value)
            }
        }
    }

private fun Bundle.toShortcutMap(): Map<String, Any?> =
    mapOf(
        EXTRA_PROVIDER to getString(EXTRA_PROVIDER),
        EXTRA_ID to getString(EXTRA_ID),
        EXTRA_NAME to getString(EXTRA_NAME),
        EXTRA_STREET to getString(EXTRA_STREET),
        EXTRA_LAT to doubleOrNull(EXTRA_LAT),
        EXTRA_LON to doubleOrNull(EXTRA_LON),
    )

/** An absent double must stay absent, not become 0.0 (a real coordinate). */
private fun Bundle.doubleOrNull(key: String): Double? =
    if (containsKey(key)) getDouble(key, Double.NaN).takeUnless { it.isNaN() } else null
