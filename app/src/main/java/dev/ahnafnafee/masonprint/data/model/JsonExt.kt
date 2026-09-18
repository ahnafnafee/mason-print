package dev.ahnafnafee.masonprint.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The one JSON configuration in the app.
 *
 * `/PharosAPI` responses are decoded through explicit mapping ([JsonObject] helpers below)
 * rather than annotation-driven data classes, because the same field is not the same type from
 * one endpoint to the next — all of this is visible in GMU's 4.11.24.1 bundle, whose own model
 * defaults document the inconsistency:
 *
 *  - `FinishingOptions.Mono` / `.Duplex`: `"Yes"`/`"No"` in `/settings` and in the job list
 *    (the server publishes `"FinishingOptions":{"Duplex":"Yes","Mono":"Yes",…}`), booleans in
 *    Print Center's upload dialog, `"True"`/`"False"` in the Xamarin app's model. All three
 *    are accepted; the clone mirrors whichever shape the endpoint it came from uses.
 *  - `PagesPerSide` / `Copies`: **must** be JSON strings in an upload `MetaData` part — as
 *    integers the server answers 500 (docs/FINDINGS.md §5, re-verified live against GMU).
 *  - `ModelUserTransaction.defaults.Amount` is `""` and `.Pages`/`.Sheets` are `""`, while
 *    `Amount` arrives as a number in GMU's own data — quoted and bare numbers are mixed.
 *  - Paged collections can carry `"Items": null`.
 *
 * Request bodies are built by hand for the same reason: the encoding *is* the contract here,
 * and two endpoints disagree about it. See [FinishingPayload].
 */
val PharosJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

/** Pharos' own truthiness. It uses Yes/No and Allow/Deny interchangeably across endpoints. */
private val TRUTHY = setOf("yes", "true", "allow", "allowed", "1", "on", "enabled")
private val FALSY = setOf("no", "false", "deny", "denied", "0", "off", "disabled", "none")

fun asBoolean(raw: JsonElement?): Boolean? {
    if (raw == null || raw is JsonNull) return null
    if (raw is JsonPrimitive) {
        raw.booleanOrNull?.let { return it }
        raw.doubleOrNull?.let { return it != 0.0 }
        val s = raw.contentOrNullish()?.lowercase() ?: return null
        return when {
            s in TRUTHY -> true
            s in FALSY -> false
            else -> null
        }
    }
    return null
}

fun asDouble(raw: JsonElement?): Double? {
    if (raw == null || raw is JsonNull) return null
    if (raw is JsonPrimitive) {
        raw.doubleOrNull?.let { return it }
        val s = raw.contentOrNullish()?.trim() ?: return null
        return s.toDoubleOrNull() ?: s.removePrefix("$").replace(",", "").toDoubleOrNull()
    }
    return null
}

fun asLong(raw: JsonElement?): Long? {
    if (raw == null || raw is JsonNull) return null
    if (raw is JsonPrimitive) {
        raw.doubleOrNull?.let { return it.toLong() }
        return raw.contentOrNullish()?.trim()?.toLongOrNull()
    }
    return null
}

fun asString(raw: JsonElement?): String? {
    if (raw == null || raw is JsonNull) return null
    return if (raw is JsonPrimitive) raw.content else raw.toString()
}

private fun JsonPrimitive.contentOrNullish(): String? = if (isString) content else content

fun JsonObject.str(key: String): String? = asString(this[key])

/** Case-insensitive key lookup: this API has been observed capitalising inconsistently. */
fun JsonObject.strCI(key: String): String? {
    this[key]?.let { return asString(it) }
    val hit = keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
    return asString(this[hit])
}

fun JsonObject.bool(key: String): Boolean? = asBoolean(this[key])
fun JsonObject.boolCI(key: String): Boolean? = asBoolean(entries.firstOrNull { it.key.equals(key, true) }?.value)
fun JsonObject.dbl(key: String): Double? = asDouble(this[key])

/** As [strCI]/[boolCI], for the money fields whose casing has not been observed yet. */
fun JsonObject.dblCI(key: String): Double? = asDouble(entries.firstOrNull { it.key.equals(key, true) }?.value)
fun dblIn(obj: JsonObject, vararg keys: String): Double? = keys.firstNotNullOfOrNull { obj.dbl(it) }
fun JsonObject.lng(key: String): Long? = asLong(this[key])
fun lngIn(obj: JsonObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { obj.lng(it) }
fun JsonObject.strIn(vararg keys: String): String? = keys.firstNotNullOfOrNull { str(it) }
fun JsonObject.boolIn(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { bool(it) }

fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)?.takeIf { it.isNotEmpty() }

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objects(key: String): List<JsonObject> =
    (arr(key) ?: return emptyList()).mapNotNull { it as? JsonObject }

/**
 * Some Pharos lists arrive wrapped twice — cost centres come back as
 * `{"CostCenters":{"CostCenters":[…]}}` (GMU bundle: `resp.CostCenters.CostCenters`).
 * This unwraps either shape.
 */
fun JsonObject.objectsNested(key: String): List<JsonObject> {
    obj(key)?.let { inner ->
        inner.objects(key).ifEmpty { inner.objects("Items") }.ifEmpty {
            inner.entries.firstOrNull { it.value is JsonArray }
                ?.let { (it.value as JsonArray).mapNotNull { e -> e as? JsonObject } }
                .orEmpty()
        }.let { return it }
    }
    return objects(key)
}

fun JsonObject.has(key: String): Boolean = contains(key) && this[key] !is JsonNull

/**
 * Request-body builders. Pharos tolerates some explicit nulls and rejects others, so every
 * optional field is written only when it carries a value.
 */
fun JsonObjectBuilder.putStr(key: String, value: String?) { if (!value.isNullOrEmpty()) put(key, value) }
fun JsonObjectBuilder.putBool(key: String, value: Boolean?) { if (value != null) put(key, value) }
fun JsonObjectBuilder.putNum(key: String, value: Number?) { if (value != null) put(key, value) }
fun JsonObjectBuilder.putStrNum(key: String, value: Long?) { if (value != null) put(key, value.toString()) }
fun JsonObjectBuilder.putElem(key: String, value: JsonElement?) { if (value != null) put(key, value) }

/** Body helper: `{ "PrintJobs": [ … ] }` from already-built per-job objects. */
fun printJobsBody(jobs: List<JsonObject>, extra: (JsonObjectBuilder.() -> Unit)? = null): JsonObject =
    buildJsonObject {
        put("PrintJobs", JsonArray(jobs))
        extra?.invoke(this)
    }

/** Body helper: `[{…},{…}]` at the top level (a couple of endpoints want a bare array). */
fun JsonArray(vararg items: JsonObject): JsonArray = JsonArray(items.toList())

fun JsonObject.encode(): String = PharosJson.encodeToString(JsonObject.serializer(), this)

fun String.toJsonObject(): JsonObject = PharosJson.parseToJsonElement(this).jsonObject

/**
 * Parse a body that may be an object **or a bare top-level array**, as a list of objects.
 *
 * `POST /printjobs/cost` and `DELETE /printjobs` both answer with a bare array, and the array is not
 * an error signal: on the first live upload probe GMU returned **HTTP 200** whose single element was
 * `{"Status":405,"ErrorCode":"JobActionNotAllowedStillProcessing",…}`. Parsing those bodies with
 * [toJsonObject] throws, which the callers swallowed — so a server that explicitly *refused* to price
 * a job was indistinguishable from one that never answered.
 */
fun String.toJsonObjects(): List<JsonObject> = when (val el = PharosJson.parseToJsonElement(this)) {
    is JsonArray -> el.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(el)
    else -> emptyList()
}
