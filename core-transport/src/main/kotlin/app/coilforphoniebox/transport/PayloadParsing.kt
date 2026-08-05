package app.coilforphoniebox.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient accessors for everything arriving from the box.
 *
 * MPD hands most of its status over as strings — `"elapsed": "12.345"`,
 * `"repeat": "1"` — and omits whole fields depending on the content: web radio has no
 * `duration` and no `album`. Nothing here throws on a missing or oddly typed field;
 * it returns null and the caller falls back (§4.2).
 */
internal fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

internal fun JsonObject.string(key: String): String? =
    this[key].asPrimitiveOrNull()?.let { primitive ->
        primitive.content.takeIf { it.isNotBlank() && it != "null" }
    }

internal fun JsonObject.double(key: String): Double? =
    this[key].asPrimitiveOrNull()?.let { it.content.toDoubleOrNull() }

internal fun JsonObject.int(key: String): Int? =
    this[key].asPrimitiveOrNull()?.let { it.content.toDoubleOrNull()?.toInt() }

/**
 * Accepts `true`, `"true"`, `1` and `"1"`. MPD uses `"0"`/`"1"`, the volume plugin
 * publishes a real JSON boolean, and both reach the same fields.
 */
internal fun JsonObject.boolean(key: String): Boolean? =
    this[key].asPrimitiveOrNull()?.content?.lowercase()?.let { value ->
        when (value) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

/**
 * A value that may be a single item or a list of them. `list_albums` groups by album
 * artist, and an artist with several albums comes back as an array under one key.
 */
internal fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonPrimitive -> listOf(content)
    is JsonArray -> mapNotNull { it.asPrimitiveOrNull()?.content }
    else -> emptyList()
}
