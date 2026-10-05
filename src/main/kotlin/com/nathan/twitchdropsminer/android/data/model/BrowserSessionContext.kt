package com.nathan.twitchdropsminer.android.data.model

import java.time.Instant
import kotlinx.serialization.json.*

const val TwitchWebClientId = "kimne78kx3ncx6brgo4mv6wki5h1ko"

/** Private browser request context. Never include this object in public state or diagnostics. */
class BrowserSessionContext private constructor(
    val capturedAt: Instant,
    val expiresAt: Instant,
    val userAgent: String,
    val headers: Map<String, String>,
    val sdkCookie: BrowserSdkCookie? = null,
) {
    val accessToken: String get() = headers.getValue("authorization").removePrefix("OAuth ")
    val deviceId: String get() = headers["x-device-id"] ?: headers.getValue("device-id")
    fun requireFresh(now: Instant = Instant.now()) {
        require(expiresAt.isAfter(now.plusSeconds(15))) { "Browser session proof expired; keep the browser service or desktop helper running, or reconnect Twitch." }
    }
    fun toJson(): JsonObject = buildJsonObject {
        put("version", 1)
        put("captured_at", capturedAt.epochSecond)
        put("expires_at", expiresAt.epochSecond)
        put("user_agent", userAgent)
        put("headers", buildJsonObject { headers.forEach { (name, value) -> put(name, value) } })
        sdkCookie?.let { put("sdk_cookie", it.toJson()) }
    }
    override fun toString() = "BrowserSessionContext(redacted)"

    companion object {
        val HeaderNames = setOf("authorization", "client-id", "client-integrity", "client-version",
            "client-session-id", "x-device-id", "device-id", "accept-language")
        fun parse(value: JsonObject, now: Instant = Instant.now()): BrowserSessionContext {
            try {
                require(value.keys - "sdk_cookie" == setOf("version", "captured_at", "expires_at", "user_agent", "headers"))
                require(value.toString().toByteArray().size <= 36 * 1024)
                require(value["version"] == JsonPrimitive(1))
                fun seconds(name: String): Long {
                    val item = value[name] as? JsonPrimitive ?: error("shape")
                    require(!item.isString)
                    return item.longOrNull ?: error("number")
                }
                val captured = Instant.ofEpochSecond(seconds("captured_at"))
                val expires = Instant.ofEpochSecond(seconds("expires_at"))
                require(captured.epochSecond > 0 && captured <= now.plusSeconds(60))
                require(expires > captured && expires <= captured.plusSeconds(86400))
                fun text(item: JsonElement?): String {
                    val primitive = item as? JsonPrimitive ?: error("shape")
                    require(primitive.isString)
                    return primitive.content.also { require(it.matches(Regex("[\\x20-\\x7e]{1,16384}"))) }
                }
                val agent = text(value["user_agent"]).also { require(it.length <= 1024) }
                val rawHeaders = value["headers"] as? JsonObject ?: error("shape")
                require(HeaderNames.containsAll(rawHeaders.keys))
                val headers = rawHeaders.mapValues { text(it.value) }.toMap()
                require(headers["client-id"] == TwitchWebClientId)
                require(headers["authorization"].orEmpty().matches(Regex("OAuth [A-Za-z0-9_-]{1,512}")))
                require(!headers["client-integrity"].isNullOrBlank())
                require(!(headers["x-device-id"] ?: headers["device-id"]).isNullOrBlank())
                val cookie = value["sdk_cookie"]?.let { BrowserSdkCookie.parse(it as JsonObject) }
                require(cookie == null || cookie.expiresAt > expires)
                return BrowserSessionContext(captured, expires, agent, headers, cookie)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid browser session context.")
            }
        }
    }
}

/** The only persisted browser cookie: fixed SDK host/name/path, never a general cookie jar. */
class BrowserSdkCookie private constructor(val value: String, val expiresAt: Instant) {
    fun toJson() = buildJsonObject { put("value", value); put("expires_at", expiresAt.epochSecond) }
    override fun toString() = "BrowserSdkCookie(redacted)"
    companion object {
        fun parse(json: JsonObject): BrowserSdkCookie {
            require(json.keys == setOf("value", "expires_at"))
            val value = json["value"] as? JsonPrimitive ?: error("Invalid SDK cookie")
            val expiry = json["expires_at"] as? JsonPrimitive ?: error("Invalid SDK cookie")
            require(value.isString && value.content.matches(Regex("[\\x21\\x23-\\x2b\\x2d-\\x3a\\x3c-\\x5b\\x5d-\\x7e]{1,8192}")))
            require(!expiry.isString && (expiry.longOrNull ?: 0) > 0)
            return BrowserSdkCookie(value.content, Instant.ofEpochSecond(expiry.long))
        }
    }
}
