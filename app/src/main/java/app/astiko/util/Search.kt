package app.astiko.util

import app.astiko.data.model.Stop
import java.text.Normalizer
import java.util.Locale

/** NFD + strip combining marks (ά -> α), then lowercase (Locale.ROOT). */
internal fun normalizeSearchText(s: String): String =
    Normalizer
        .normalize(s, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)

private val DIACRITICS = Regex("\\p{Mn}+")

/**
 * Accent/case-insensitive stop search over an in-memory list. Ranks
 * matches: name prefix first, then name contains, then stop code prefix
 * (users do type "0116"). Ties break alphabetically. Blank queries
 * return nothing.
 *
 * Used by the CityBus adapter (the full stop catalog is already in
 * memory) and the offline decorator's cached-stop fallback. OSETh does
 * the matching server-side. OASA has no stop index yet.
 */
internal fun rankStopSearch(
    stops: List<Stop>,
    query: String,
    limit: Int,
): List<Stop> {
    val q = normalizeSearchText(query.trim())
    if (q.isEmpty()) return emptyList()
    val ranked =
        stops
            .mapNotNull { stop ->
                val name = normalizeSearchText(stop.name)
                val rank =
                    when {
                        name.startsWith(q) -> 0
                        name.contains(q) -> 1
                        stop.id.startsWith(q) -> 2
                        else -> return@mapNotNull null
                    }
                rank to stop
            }.sortedWith(
                compareBy(
                    { it.first },
                    { normalizeSearchText(it.second.name) },
                    { it.second.id }, // fully deterministic: equal names keep a stable order
                ),
            )
    return ranked.take(limit).map { it.second }
}
