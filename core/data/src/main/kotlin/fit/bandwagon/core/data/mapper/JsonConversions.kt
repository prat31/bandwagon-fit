package fit.bandwagon.core.data.mapper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Firestore hands out plain maps, lists and numbers; kotlinx.serialization works on JSON trees.
 * These convert between the two so typed models (ChallengeSpec) can round-trip through Firestore
 * and callables.
 */
fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this.toDouble())
    is Double -> if (this % 1.0 == 0.0 && kotlin.math.abs(this) < 1e15) JsonPrimitive(this.toLong()) else JsonPrimitive(this)
    is Number -> JsonPrimitive(this.toDouble())
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is Iterable<*> -> JsonArray(map { it.toJson() })
    is Array<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValues { (_, v) -> v.toPlainValue() }
    is JsonArray -> map { it.toPlainValue() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        else -> doubleOrNull
    }
}
