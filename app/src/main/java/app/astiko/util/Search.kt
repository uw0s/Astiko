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

/** The stop code as printed on the sign, compared without leading zeros
 *  ("356" must find "0356"). Non-numeric ids come back trimmed. */
internal fun normalizeStopCode(s: String): String {
    val t = s.trim()
    if (t.isEmpty() || !t.all(Char::isDigit)) return t
    return t.trimStart('0').ifEmpty { "0" }
}

/** Whether the query is a stop code (digits only). */
internal fun looksLikeStopCode(query: String): Boolean {
    val t = query.trim()
    return t.isNotEmpty() && t.all(Char::isDigit)
}

/**
 * Accent/case-insensitive stop search over an in-memory list. Ranks
 * matches: exact code first, so a query typed off the sign lands on that
 * stop, then name prefix, name contains, then code prefix (users do type
 * "0116"). Leading zeros in codes are ignored both ways. Ties break
 * alphabetically. Blank queries return nothing.
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
    val code = normalizeStopCode(query)
    val codeQuery = looksLikeStopCode(query)
    val ranked =
        stops
            .mapNotNull { stop ->
                val name = normalizeSearchText(stop.name)
                val stopCode = normalizeStopCode(stop.id)
                val rank =
                    when {
                        codeQuery && stopCode == code -> 0
                        name.startsWith(q) -> 1
                        name.contains(q) -> 2
                        codeQuery && stopCode.startsWith(code) -> 3
                        stop.id.startsWith(q) -> 3
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
