package app.astiko.ui

import app.astiko.data.AppLanguage
import app.astiko.data.AppPrefs
import app.astiko.data.model.Arrival
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Decimal separators follow the IN-APP language, not the JVM default
 * locale (which the in-app override never touches, the weekday-chips
 * trap). A forced Greek UI on an English device must render "1,2km"
 * like any Greek device, and "1.2km" in English. SYSTEM falls back
 * to the device locale. Read from the [AppPrefs] snapshot, the same
 * one attachBaseContext and langProvider use.
 */
private val uiLocale: Locale
    get() =
        when (AppPrefs.language) {
            AppLanguage.EL -> Locale.forLanguageTag("el")
            AppLanguage.EN -> Locale.forLanguageTag("en")
            AppLanguage.SYSTEM -> Locale.getDefault()
        }

/** 830 m / 1.2 km, compact and without a space (cluster rows join as "45m/58m"). */
fun formatDistance(km: Double): String =
    if (km < 1.0) "${(km * 1000).roundToInt()}m" else String.format(uiLocale, "%.1fkm", km)

/** 0 -> [nowLabel] ("Τώρα"/"Now"), else "3′". Callers pass a localized label. */
fun formatEta(
    minutes: Int,
    nowLabel: String,
): String = if (minutes <= 0) nowLabel else "$minutes′"

/**
 * The time text for an arrival row/card. Scheduled (offline) arrivals
 * always show the scheduled clock time, never a "X′" countdown, even
 * when the trip is minutes away (a schedule is not live telemetry).
 * Live arrivals count down in minutes while imminent (≤ 90 min) and
 * show the clock time when far. Null when there is no time to show
 * ("–" is the caller's job).
 */
fun arrivalTimeText(
    arrival: Arrival,
    nowLabel: String,
): String? {
    if (arrival.isScheduled) return arrival.scheduledTime?.take(5)
    val imminent = (arrival.etaMinutes ?: Int.MAX_VALUE) <= 90
    return when {
        imminent -> {
            arrival.etaMinutes?.let { formatEta(it, nowLabel) }
        }

        else -> {
            arrival.scheduledTime?.take(5)
                ?: arrival.etaMinutes?.let { formatEta(it, nowLabel) }
        }
    }
}

/** 830 B / 4.2 KB / 1.3 MB, shown in the settings offline-cache row. */
fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(uiLocale, "%.0f KB", bytes / 1024.0)
        else -> String.format(uiLocale, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

/**
 * The settings last-sync row. The pattern comes from resources
 * (R.string.date_time_format). The in-app language override never
 * touches the JVM default locale, so a hardcoded pattern here would be
 * the same trap as the weekday chips. Both locales currently share the
 * Greek format (dd/MM/yyyy, 24 h), adjusted per locale in the string
 * files.
 */
fun formatTimestamp(
    epochMs: Long,
    pattern: String,
): String =
    Instant
        .ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))

/** TODO: move this day-suffix stripping into OseThRepository (destination fields only). */
private val DAY_SUFFIXES =
    setOf(
        "ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ",
        "SATURDAY-SUNDAY",
        "ΣΑΒΒΑΤΟ",
        "ΚΥΡΙΑΚΗ",
        "SATURDAY",
        "SUNDAY",
        "WEEKEND",
        "ΔΕΥΤΕΡΑ",
        "ΤΡΙΤΗ",
        "ΤΕΤΑΡΤΗ",
        "ΠΕΜΠΤΗ",
        "ΠΑΡΑΣΚΕΥΗ",
        "MONDAY",
        "TUESDAY",
        "WEDNESDAY",
        "THURSDAY",
        "FRIDAY",
    )

/** Trailing bracketed annotations, e.g. "ΜΟΣΧΑΤΟ [Προσωρινή λόγω έργων]". */
private val BRACKET_SUFFIX = Regex("\\s*\\[[^\\]]*\\]\\s*$")

private val DASH_SEPARATOR = Regex("\\s*-\\s*")

/**
 * Short destination of a direction label: the segment after the last
 * " - " (or "-"), with trailing bracketed annotations
 * ("... [Προσωρινή λόγω έργων]") and OSETh day suffixes
 * ("... - ΣΑΒΒΑΤΟ-ΚΥΡΙΑΚΗ") stripped first. Null when the label has no
 * separator, the destination is too long, or nothing remains. Callers
 * fall back to the full label. Used by the direction switcher so the
 * top-bar subtitle doesn't ellipsize whole route descriptions.
 */
fun directionDestination(label: String): String? {
    val parts =
        label
            .replace(BRACKET_SUFFIX, "")
            .split(DASH_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    var index = parts.lastIndex
    while (index > 0 && parts[index].uppercase() in DAY_SUFFIXES) index--
    val destination = parts[index]
    return destination.takeIf { it.length <= 32 }
}
