package com.nathan.twitchdropsminer.android.data.twitch

import java.time.Duration
import java.util.Locale
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class TwitchCategory(val id: String, val name: String)

fun interface CategorySearch {
    suspend fun search(query: String): List<TwitchCategory>
}

class CategorySearchException(val busy: Boolean = false) : IllegalStateException(
    if (busy) "Category search is busy. Try again shortly."
    else "Twitch category search is unavailable. Try again shortly or use loaded and saved games.",
)

/** Public catalog lookup only. Never receives a stored session or changes mining state. */
class TwitchCategorySearch(
    httpClient: OkHttpClient,
    endpoint: String = "https://gql.twitch.tv/gql",
    private val nanoTime: () -> Long = System::nanoTime,
) : CategorySearch {
    private val url = endpoint.toHttpUrl().also {
        require((it.scheme == "https" && it.host == "gql.twitch.tv" && it.port == 443 && it.encodedPath == "/gql") ||
            (it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1")))
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = httpClient.newBuilder()
        .callTimeout(Duration.ofSeconds(15))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val permits = Semaphore(2)
    private val cache = LinkedHashMap<String, CachedCategories>()

    override suspend fun search(query: String): List<TwitchCategory> {
        val normalized = query.trim()
        require(normalized.length in 2..100 && normalized.none(Char::isISOControl))
        val key = normalized.lowercase(Locale.ROOT)
        synchronized(cache) {
            cache[key]?.takeIf { nanoTime() - it.savedAt < CacheNanos }?.let { return it.categories }
        }
        if (!permits.tryAcquire()) throw CategorySearchException(busy = true)
        try {
            val categories = withContext(Dispatchers.IO) { fetch(normalized) }
            synchronized(cache) {
                cache.remove(key)
                cache[key] = CachedCategories(nanoTime(), categories)
                while (cache.size > 32) cache.remove(cache.keys.first())
            }
            return categories
        } finally {
            permits.release()
        }
    }

    private fun fetch(query: String): List<TwitchCategory> {
        val payload = buildJsonObject {
            put("operationName", "SearchCategories")
            put("query", "query SearchCategories(\$query: String!) { searchCategories(query: \$query, first: 12) { edges { node { id name } } } }")
            put("variables", buildJsonObject { put("query", query) })
        }
        val request = Request.Builder().url(url)
            .header("Client-ID", TwitchClientId)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CategorySearchException()
                val bytes = (response.body ?: throw CategorySearchException()).byteStream().readNBytes(MaxResponseBytes + 1)
                if (bytes.size > MaxResponseBytes) throw CategorySearchException()
                val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                    ?: throw CategorySearchException()
                val errors = root["errors"]
                if (errors != null && (errors !is JsonArray || errors.isNotEmpty())) throw CategorySearchException()
                val data = root["data"] as? JsonObject
                val result = data?.get("searchCategories") as? JsonObject
                val edges = result?.get("edges") as? JsonArray ?: throw CategorySearchException()
                val categories = edges.mapNotNull { edge ->
                    val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null
                    val id = (node["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val name = (node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                    if (id == null || !id.matches(Regex("[0-9]{1,30}")) || name.isNullOrEmpty() ||
                        name.length > 200 || name.any(Char::isISOControl)) return@mapNotNull null
                    TwitchCategory(id, name)
                }.distinctBy { it.id }.distinctBy { it.name.lowercase(Locale.ROOT) }.take(12)
                if (edges.isNotEmpty() && categories.isEmpty()) throw CategorySearchException()
                categories
            }
        } catch (error: java.io.IOException) {
            throw CategorySearchException()
        } catch (error: IllegalArgumentException) {
            throw CategorySearchException()
        }
    }

    private data class CachedCategories(val savedAt: Long, val categories: List<TwitchCategory>)
    private companion object {
        const val MaxResponseBytes = 128 * 1024
        val CacheNanos: Long = Duration.ofMinutes(5).toNanos()
    }
}
