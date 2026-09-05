package app.astiko.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test JSON: same decoder settings as production (unknown keys tolerated). */
val testJson = Json { ignoreUnknownKeys = true }

fun jsonObj(vararg fields: Pair<String, Any?>): JsonObject =
    buildJsonObject {
        for ((key, value) in fields) {
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, JsonPrimitive(value))
                is Int -> put(key, JsonPrimitive(value))
                is Double -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is JsonElement -> put(key, value)
                is List<*> -> put(key, JsonArray(value.map { it as JsonElement }))
                else -> error("Unsupported test value: $value")
            }
        }
    }

fun jsonArr(vararg items: Any?): JsonArray =
    JsonArray(
        items.map {
            when (it) {
                null -> JsonNull
                is String -> JsonPrimitive(it)
                is Int -> JsonPrimitive(it)
                is Double -> JsonPrimitive(it)
                is Boolean -> JsonPrimitive(it)
                is JsonElement -> it
                else -> error("Unsupported test value: $it")
            }
        },
    )

/**
 * The JSON string `""` that OASA live endpoints answer with when nothing
 * is running. The adapter must treat it as "empty", not an error.
 */
fun oasaEmpty(): JsonElement = JsonPrimitive("")

private val resourceLoader = object {}

/**
 * Load a captured wire sample from src/test/resources/samples/{provider}/{name}.
 * The files are real API responses captured against the live services. They
 * pin the DTO field names to what the servers actually send.
 */
fun loadSample(
    provider: String,
    name: String,
): JsonElement {
    val resource = "/samples/$provider/$name"
    val stream =
        requireNotNull(resourceLoader.javaClass.getResourceAsStream(resource)) {
            "missing test sample: $resource"
        }
    return testJson.parseToJsonElement(stream.readBytes().toString(Charsets.UTF_8))
}
