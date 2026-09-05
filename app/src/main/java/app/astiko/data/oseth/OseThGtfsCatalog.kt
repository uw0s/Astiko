package app.astiko.data.oseth

import app.astiko.util.compareLineShortNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * One searchable stop of the OSETh catalog, language-independent (the
 * index stores both names. The repository picks per language).
 * [nameEn] is the feed's proper English transliteration when it has one,
 * null when the stop is Greek-only (caller falls back to [nameEl]).
 */
@Serializable
data class GtfsStopEntry(
    val id: String,
    val nameEl: String,
    val nameEn: String? = null,
    val lat: Double,
    val lon: Double,
    /** Serving lines' short names (badges), natural-sorted. */
    val lines: List<String> = emptyList(),
)

/**
 * The OSETh stop catalog from the data.gov.gr GTFS feed
 * ("Δεδομένα αστικών συγκοινωνιών Π.Ε. Θεσσαλονίκης", publisher ΟΣΕΘ,
 * updated ~every 2 weeks). Replaces the slow telematics `/stop` walk as
 * the catalog source: the zip (~8.9 MB) downloads fast, parses in about
 * a second, and its stop ids are byte-identical to the telematics stop
 * codes. All 3,648 GTFS stop ids appear in the telematics list, which
 * has 3,698. The 50 extras are telematics-only dupes/edges, 98.6 %
 * coverage, never merged.
 *
 * Design:
 * - **GTFS-first with local artifacts.** `load()` serves the parsed
 *   index from disk whenever it is fresh enough (built < ~7 days ago),
 *   with zero network. Otherwise it asks the CKAN resource list (tiny
 *   JSON), and only when a NEWER feed is published does it download and
 *   reparse. The check is capped at once per day (persisted timestamp),
 *   so a daily app open does not hammer the portal.
 * - **Never breaks the catalog.** Every failure (dead portal, missing
 *   envelope, broken zip, garbled CSV, sparse feed with fewer than
 *   2,000 stops) returns null, and the repository falls back to the
 *   telematics walk. A failed refresh also never discards the existing
 *   index: stale-local beats a server walk.
 * - **Atomic artifacts.** `feed.zip` (so a language switch re-parses
 *   without redownloading), `index.json` (the parsed list) and
 *   `marker.json` (which feed the index was built from + timestamps)
 *   are all written tmp + rename, same discipline as OfflineCache. The
 *   zip is parsed from its tmp file and only committed when verified,
 *   so a broken download can never replace a good local zip.
 * - **Streaming.** The 28 MB stop_times.txt is streamed line by line
 *   (never materialized). Only stops/routes/trips/translations are read
 *   fully (all small). ZipFile (not ZipInputStream) gives entry-by-name
 *   random access, so the parse never depends on entry order inside the
 *   zip. stop_times is still a single pass.
 * - **No new dependencies.** OkHttp is injected as [fetch] by the DI
 *   container. Parsing uses platform `java.util.zip`. The index is
 *   kotlinx.serialization. No CSV library: a quote-aware line splitter
 *   (see [splitCsvLine]. Today's stop names have no commas, but quotes
 *   are cheap insurance).
 */
class OseThGtfsCatalog(
    private val json: Json,
    /** Artifact directory (production: `filesDir/oseth_gtfs`). */
    private val dir: File,
    /** One GET, URL -> body bytes, null on any transport/HTTP failure. */
    private val fetch: suspend (url: String) -> ByteArray?,
    /** Injectable clock so tests pin the freshness decisions. */
    private val now: () -> Long = System::currentTimeMillis,
    /** A feed with fewer usable stops is a failure (garbage/partial),
     *  not a catalog. The real feed has 3,648. 2,000 is a generous floor. */
    private val minStops: Int = DEFAULT_MIN_STOPS,
) {
    private val mutex = Mutex()

    /**
     * The catalog, or null when unusable (the repository then falls back
     * to the telematics walk). Loads are serialized: a concurrent load
     * (other language, warm + search) awaits the first build instead of
     * double-downloading.
     *
     * Every failure except cancellation returns null. A dead portal, a
     * broken zip, a full disk all fall back. A thrown error must never
     * reach the UI past the repository's fallback.
     */
    suspend fun load(): List<GtfsStopEntry>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    loadLocked()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }

    private suspend fun loadLocked(): List<GtfsStopEntry>? {
        val marker = readMarker()
        val index = readIndex()

        // Fresh enough: serve the local index, zero network.
        if (index != null && marker != null && now() - marker.builtAt < FRESH_MS) {
            return index
        }
        // Checked within the daily cap: keep serving, no CKAN call.
        if (index != null && marker != null && now() - marker.checkedAt < CHECK_INTERVAL_MS) {
            return index
        }

        // Index lost (crash between writes, manually deleted) but the zip
        // and marker survived: reparse the local zip, keep the marker's
        // feed identity, still zero network.
        if (index == null && marker != null && zipFile().isFile) {
            val rebuilt = parseZip(zipFile())
            if (rebuilt != null && rebuilt.size >= minStops) {
                writeIndex(rebuilt)
                writeMarker(marker.copy(builtAt = now()))
                return rebuilt
            }
        }

        // Stale or first build: ask CKAN which feed is newest, download
        // only when it is newer than the marker's.
        val newest = ckanNewest() ?: return keepServing(marker, index)
        val markerStart = marker?.let { runCatching { LocalDate.parse(it.startDate) }.getOrNull() }
        if (markerStart != null && !newest.start.isAfter(markerStart)) {
            return keepServing(marker, index) // same or older feed, nothing to do
        }
        return fetchAndBuild(newest, marker, index)
    }

    /** A refresh that failed keeps serving what we had (and never forgets
     *  that we asked today, so the next load does not hammer the portal). */
    private suspend fun keepServing(
        marker: GtfsMarker?,
        index: List<GtfsStopEntry>?,
    ): List<GtfsStopEntry>? {
        if (marker != null) writeMarker(marker.copy(checkedAt = now()))
        return index
    }

    private suspend fun fetchAndBuild(
        resource: FeedResource,
        marker: GtfsMarker?,
        index: List<GtfsStopEntry>?,
    ): List<GtfsStopEntry>? {
        val url = downloadUrl(resource) ?: return keepServing(marker, index)
        val bytes = fetch(url) ?: return keepServing(marker, index)
        // Parse the temp file, commit only a verified feed (see class doc).
        dir.mkdirs()
        val tmp = File(dir, "$FILE_ZIP.tmp")
        tmp.writeBytes(bytes)
        val parsed = parseZip(tmp)
        if (parsed == null || parsed.size < minStops) {
            // Garbage or sparse: never replace good artifacts with it.
            tmp.delete()
            return keepServing(marker, index)
        }
        move(tmp, zipFile())
        writeIndex(parsed)
        writeMarker(
            GtfsMarker(
                resourceId = resource.id,
                startDate = resource.start.toString(),
                builtAt = now(),
                checkedAt = now(),
            ),
        )
        return parsed
    }

    // ------------------------------------------------------------------ CKAN

    /**
     * The newest GTFS resource from the CKAN package_show response. This
     * is the freshness signal, because the zip's own feed_info has empty
     * dates. "Newest" = the date-range start parsed from the resource
     * name ("GTFS 06/08/2026-17/08/2026"), falling back to `created` for
     * an unparseable name. Non-GTFS resources ("Όλα τα δεδομένα πόρων")
     * are excluded.
     */
    private suspend fun ckanNewest(): FeedResource? {
        val bytes = fetch(CKAN_URL) ?: return null
        val response =
            runCatching {
                json.decodeFromString(
                    CkanPackageResponse.serializer(),
                    bytes.toString(StandardCharsets.UTF_8),
                )
            }.getOrNull() ?: return null
        return response.result.resources
            .asSequence()
            .filter { it.name.startsWith("GTFS") }
            .mapNotNull { r -> startDate(r)?.let { start -> FeedResource(r, start) } }
            .maxByOrNull { it.start }
    }

    /**
     * The resource's download URL. The `url` field is already the
     * complete direct link (`.../resource/<id>/download/<file>.zip`,
     * redirecting to a signed blob). Use it as-is, appending `/download`
     * would 404. Some resources have an empty `url` (a dead link, its
     * blob is gone). Fall back to the canonical
     * `.../resource/<id>/download` route. If that 404s too, the download
     * fails and the previous index keeps serving.
     */
    private fun downloadUrl(resource: FeedResource): String? =
        resource.raw.url
            ?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: resource.raw.id
                .takeIf { it.isNotBlank() }
                ?.let { String.format(DOWNLOAD_TMPL, it) }

    private fun startDate(resource: CkanResource): LocalDate? {
        val fromName =
            NAME_START
                .find(resource.name)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { runCatching { LocalDate.parse(it, NAME_DATE) }.getOrNull() }
        if (fromName != null) return fromName
        return resource.created
            ?.substringBefore('T')
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    // ------------------------------------------------------------------ parsing

    /**
     * The zip -> the parsed index, or null on any failure (missing entry,
     * garbled CSV, unreadable). Expected size ~3.6 k. The caller applies
     * [minStops] on top.
     */
    private fun parseZip(file: File): List<GtfsStopEntry>? =
        try {
            ZipFile(file).use { zip ->
                val stopsRows = zip.readRows("stops.txt") ?: return@use null
                val routesRows = zip.readRows("routes.txt") ?: return@use null
                val tripsRows = zip.readRows("trips.txt") ?: return@use null
                val stopTimesEntry = zip.getEntry(FILE_STOP_TIMES) ?: return@use null
                // Translations are optional: without them every stop is
                // Greek-only (English mode falls back), still a valid catalog.
                val translationRows = zip.readRows("translations.txt")

                // route_id -> route_short_name (badges).
                val routeIdIdx = routesRows.columnIndex("route_id") ?: return@use null
                val routeShortIdx = routesRows.columnIndex("route_short_name") ?: return@use null
                val routes =
                    routesRows
                        .asSequence()
                        .drop(1)
                        .mapNotNull { row ->
                            val id = row.columnOrEmpty(routeIdIdx)
                            val short = row.columnOrEmpty(routeShortIdx)
                            if (id.isEmpty() || short.isEmpty()) null else id to short
                        }.toMap()
                if (routes.isEmpty()) return@use null

                // trip_id -> line short name, one hop instead of the
                // two-map join per stop_times row. Trips on unknown routes
                // are dropped by the map lookup.
                val tripIdIdx = tripsRows.columnIndex("trip_id") ?: return@use null
                val tripsRouteIdx = tripsRows.columnIndex("route_id") ?: return@use null
                val trips =
                    tripsRows
                        .asSequence()
                        .drop(1)
                        .mapNotNull { row ->
                            val tripId = row.columnOrEmpty(tripIdIdx)
                            val short = routes[row.columnOrEmpty(tripsRouteIdx)]
                            if (tripId.isEmpty() || short == null) null else tripId to short
                        }.toMap()
                if (trips.isEmpty()) return@use null

                // stop_times.txt (28 MB): streamed, one pass, never materialized.
                val stopLines = HashMap<String, MutableSet<String>>()
                var stopTimesHeader = false
                zip.getInputStream(stopTimesEntry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    val header = splitCsvLine(reader.readLine().orEmpty())
                    val tripIdx = header.indexOf("trip_id")
                    val stopIdx = header.indexOf("stop_id")
                    if (tripIdx >= 0 && stopIdx >= 0) {
                        stopTimesHeader = true
                        var line = reader.readLine()
                        while (line != null) {
                            val row = splitCsvLine(line)
                            val short = trips[row.columnOrEmpty(tripIdx)]
                            val stop = row.columnOrEmpty(stopIdx)
                            if (short != null && stop.isNotEmpty()) {
                                stopLines.getOrPut(stop) { HashSet() }.add(short)
                            }
                            line = reader.readLine()
                        }
                    }
                }
                if (!stopTimesHeader) return@use null

                val translations = parseTranslations(translationRows)
                val columns = stopColumns(stopsRows[0]) ?: return@use null

                stopsRows
                    .asSequence()
                    .drop(1)
                    .mapNotNull { row ->
                        val id =
                            row
                                .columnOrEmpty(columns.id)
                                .ifEmpty { row.columnOrEmpty(columns.code) }
                        val name = row.columnOrEmpty(columns.name)
                        // location_type: ""/"0" = a regular stop, 1/2 =
                        // station/entrance rows. The telematics list has no
                        // equivalents for those, they would pollute search.
                        val location = row.columnOrEmpty(columns.locationType)
                        val lat = row.columnOrEmpty(columns.lat).toDoubleOrNull()
                        val lon = row.columnOrEmpty(columns.lon).toDoubleOrNull()
                        if (id.isEmpty() || name.isEmpty() || location !in setOf("", "0") ||
                            lat == null || lon == null
                        ) {
                            null
                        } else {
                            val badges =
                                stopLines[id]
                                    ?.let { lines ->
                                        lines.sortedWith(Comparator { a, b -> compareLineShortNames(a, b) })
                                    }.orEmpty()
                            GtfsStopEntry(
                                id = id,
                                nameEl = name,
                                nameEn = translations[name]?.takeIf { it.isNotBlank() },
                                lat = lat,
                                lon = lon,
                                lines = badges,
                            )
                        }
                    }.filterNotNull()
                    .distinctBy { it.id }
                    .toList()
            }
        } catch (_: ZipException) {
            null // not a zip at all (garbage download)
        } catch (_: Exception) {
            null // any other parse failure: fallback, never a crash
        }

    /**
     * `translations.txt` (optional): stops/stop_name/en-GB rows keyed by
     * `field_value` (the Greek name. `record_id` is empty in this feed,
     * so the Greek name is the only key).
     */
    private fun parseTranslations(rows: List<List<String>>?): Map<String, String> {
        if (rows == null || rows.size < 2) return emptyMap()
        val table = rows[0].indexOf("table_name")
        val field = rows[0].indexOf("field_name")
        val lang = rows[0].indexOf("language")
        val translation = rows[0].indexOf("translation")
        val fieldValue = rows[0].indexOf("field_value")
        if (table < 0 || field < 0 || lang < 0 || translation < 0 || fieldValue < 0) {
            return emptyMap()
        }
        return rows
            .asSequence()
            .drop(1)
            .filter { row ->
                row.columnOrEmpty(table) == "stops" &&
                    row.columnOrEmpty(field) == "stop_name" &&
                    row.columnOrEmpty(lang).startsWith("en")
            }.mapNotNull { row ->
                val greek = row.columnOrEmpty(fieldValue)
                val english = row.columnOrEmpty(translation)
                if (greek.isEmpty() || english.isEmpty()) null else greek to english
            }.toMap()
    }

    /** Read one small CSV entry fully (stop_times is streamed separately). */
    private fun ZipFile.readRows(name: String): List<List<String>>? {
        val entry = getEntry(name) ?: return null
        return getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val rows = ArrayList<List<String>>()
            var line = reader.readLine()
            while (line != null) {
                rows += splitCsvLine(line)
                line = reader.readLine()
            }
            rows
        }
    }

    // ------------------------------------------------------------- artifacts

    private fun zipFile(): File = File(dir, FILE_ZIP)

    private fun readIndex(): List<GtfsStopEntry>? {
        val file = File(dir, FILE_INDEX)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(ListSerializer(GtfsStopEntry.serializer()), file.readText())
        }.getOrNull()
    }

    private fun writeIndex(entries: List<GtfsStopEntry>) {
        dir.mkdirs()
        val content =
            json.encodeToString(
                ListSerializer(GtfsStopEntry.serializer()),
                entries,
            )
        atomicWrite(File(dir, FILE_INDEX), content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readMarker(): GtfsMarker? {
        val file = File(dir, FILE_MARKER)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(GtfsMarker.serializer(), file.readText())
        }.getOrNull()
    }

    private fun writeMarker(marker: GtfsMarker) {
        dir.mkdirs()
        atomicWrite(
            File(dir, FILE_MARKER),
            json.encodeToString(GtfsMarker.serializer(), marker).toByteArray(),
        )
    }

    /** tmp + rename, same discipline as OfflineCache: a crash mid-write
     *  can never leave a truncated artifact that then serves as valid. */
    private fun atomicWrite(
        file: File,
        content: ByteArray,
    ) {
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeBytes(content)
        move(tmp, file)
    }

    private fun move(
        from: File,
        to: File,
    ) {
        try {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // --------------------------------------------------------------- plumbing

    private data class StopsColumns(
        val id: Int,
        val code: Int,
        val name: Int,
        val lat: Int,
        val lon: Int,
        val locationType: Int,
    )

    /** start = position in the header, -1 = column absent. */
    private fun stopColumns(header: List<String>): StopsColumns? {
        val id = header.indexOf("stop_id")
        val name = header.indexOf("stop_name")
        val lat = header.indexOf("stop_lat")
        val lon = header.indexOf("stop_lon")
        if (id < 0 || name < 0 || lat < 0 || lon < 0) return null
        return StopsColumns(
            id = id,
            code = header.indexOf("stop_code"),
            name = name,
            lat = lat,
            lon = lon,
            locationType = header.indexOf("location_type"),
        )
    }

    private fun List<String>.columnOrEmpty(index: Int): String = getOrNull(index)?.trim().orEmpty()

    private fun List<List<String>>.columnIndex(name: String): Int? = firstOrNull()?.indexOf(name)?.takeIf { it >= 0 }

    @Serializable
    private data class CkanPackageResponse(
        val result: CkanResult = CkanResult(),
    )

    @Serializable
    private data class CkanResult(
        val resources: List<CkanResource> = emptyList(),
    )

    @Serializable
    private data class CkanResource(
        val id: String = "",
        val name: String = "",
        val created: String? = null,
        val url: String? = null,
    )

    /** A GTFS resource + its parsed period-start date (the sort key). */
    private data class FeedResource(
        val raw: CkanResource,
        val start: LocalDate,
    ) {
        val id: String get() = raw.id
    }

    @Serializable
    private data class GtfsMarker(
        val resourceId: String,
        /** ISO date of the feed period start, the freshness comparison key. */
        val startDate: String,
        /** Wall clock of the last successful build. */
        val builtAt: Long,
        /** Wall clock of the last CKAN poll (the once-a-day cap). */
        val checkedAt: Long,
    )

    companion object {
        /** The dataset id on data.gov.gr (part of the CKAN endpoint). */
        private const val CKAN_URL =
            "https://data.gov.gr/api/3/action/package_show?id=dedomena-astikon-sygkoinonion-p-e-thessalonikis"

        /** The package id, for reconstructing a download URL from a
         *  resource id when the CKAN response has an empty `url`. */
        private const val DOWNLOAD_TMPL =
            "https://data.gov.gr/dataset/42c9a7da-c86c-48b1-914c-f340e8bef00d/resource/%s/download"

        private const val FILE_ZIP = "feed.zip"
        private const val FILE_INDEX = "index.json"
        private const val FILE_MARKER = "marker.json"
        private const val FILE_STOP_TIMES = "stop_times.txt"

        /** Index younger than this serves without any network. Feeds are
         *  published ~every 2 weeks. 7 days is half a cycle, so a new feed
         *  is caught within one cycle while daily opens stay offline. */
        private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000

        /** CKAN is only polled at most once per day. */
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        private const val DEFAULT_MIN_STOPS = 2_000

        private val NAME_START = Regex("^GTFS\\s+(\\d{2}/\\d{2}/\\d{4})")
        private val NAME_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

/** The feed's CSV files all start with a UTF-8 BOM. */
private const val CSV_BOM = "\uFEFF"

/**
 * RFC 4180-ish CSV line splitter: quoted fields may contain the
 * separator and doubled quotes (`""` = one quote). The BOM is stripped
 * from the first field (this feed's files all start with one). A quote
 * embedded mid-field is taken literally, which is all GTFS needs.
 */
internal fun splitCsvLine(line: String): List<String> {
    val fields = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            // Escaped quote inside a quoted field ("" -> ").
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"')
                i++ // consumed the first quote. The loop's ++ skips the pair.
            }

            // A quote mid-field is literal (no stop name today has one,
            // but toggling on it would swallow the rest of the line).
            c == '"' && !inQuotes && field.isNotEmpty() -> {
                field.append(c)
            }

            // A quote at field start opens/closes the quoted field.
            c == '"' -> {
                inQuotes = !inQuotes
            }

            c == ',' && !inQuotes -> {
                fields += field.toString()
                field.setLength(0)
            }

            else -> {
                field.append(c)
            }
        }
        i++
    }
    fields += field.toString()
    // The feed's files all start with a BOM. It lands at the start of the
    // first field of the first line only.
    fields[0] = fields[0].removePrefix(CSV_BOM)
    return fields
}
