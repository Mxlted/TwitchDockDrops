package com.nathan.twitchdropsminer.android.data.twitch

import kotlinx.serialization.json.*

/** Protocol absence is distinct from invalid syntax. No account state is inferred here. */
internal fun parseCurrentDrop(response: JsonObject): CurrentDropProgress? {
    fun malformed(): Nothing = throw TwitchApiException(TwitchApiErrorType.UnexpectedResponse, "Twitch progress is malformed.")
    if (response["errors"]?.let { it !is JsonArray || it.isNotEmpty() } == true) {
        throw TwitchApiException(TwitchApiErrorType.UnexpectedResponse, "Twitch progress check returned errors.")
    }
    val data = response["data"] as? JsonObject ?: malformed()
    val user = data["currentUser"] as? JsonObject ?: malformed()
    val value = user["dropCurrentSession"] ?: malformed()
    if (value == JsonNull) return null
    val record = value as? JsonObject ?: malformed()
    fun numericZero(key: String): Boolean = (record[key] as? JsonPrimitive)?.let {
        !it.isString && it.content == "0"
    } == true
    if (record["dropID"] == JsonPrimitive("") && record["channel"] == JsonNull &&
        record["game"] == JsonNull && numericZero("currentMinutesWatched") && numericZero("requiredMinutesWatched")) return null

    val id = (record["dropID"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()
    if (id.isBlank() || id.length > 2048 || id.any { it.isISOControl() }) malformed()
    // The reference supports unsigned integral JSON numbers and decimal digit strings.
    // This JVM model deliberately caps minutes at Int.MAX_VALUE and channel IDs at Long.MAX_VALUE.
    fun integer(value: JsonElement?): Long? = (value as? JsonPrimitive)?.content
        ?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    val channel = integer((record["channel"] as? JsonObject)?.get("id"))?.takeIf { it > 0 } ?: malformed()
    val minutes = integer(record["currentMinutesWatched"])?.takeIf { it <= Int.MAX_VALUE } ?: malformed()
    return CurrentDropProgress(id, minutes.toInt(), channel)
}
