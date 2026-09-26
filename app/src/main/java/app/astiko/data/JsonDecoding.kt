package app.astiko.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One entry of a response array, null when the entry does not match its
 * DTO. A single malformed row must drop itself, not fail the page around
 * it (an arrivals list with one bad row still has arrivals).
 *
 * Takes the serializer explicitly, the member decode form the adapters
 * use. Decoding does not suspend, so the plain runCatching here cannot
 * swallow a cancellation.
 */
internal fun <T> Json.decodeOrNull(
    element: JsonElement,
    serializer: KSerializer<T>,
): T? = runCatching { decodeFromJsonElement(serializer, element) }.getOrNull()
