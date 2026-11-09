package app.lantern.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The text of a string, number or boolean field, or null when the field is missing or JSON null. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
