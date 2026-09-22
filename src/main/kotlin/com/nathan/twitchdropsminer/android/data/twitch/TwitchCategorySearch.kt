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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class TwitchCategory(val id: String, val name: String)
data class TwitchCategoryPage(val categories: List<TwitchCategory>, val nextCursor: String? = null)

fun categorySearchLimit(query: String): Int = if (query.trim().length >= 4) 50 else 12
fun isCategorySearchCursor(value: String): Boolean = value.matches(Regex("[A-Za-z0-9+/=_-]{1,512}"))

fun interface CategorySearch {
    suspend fun search(query: String, after: String?): TwitchCategoryPage
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
    private val cache = LinkedHashMap<CacheKey, CachedCategories>()

    override suspend fun search(query: String, after: String?): TwitchCategoryPage {
        val normalized = query.trim()
        require(normalized.length in 2..100 && normalized.none(Char::isISOControl))
        require(after == null || (normalized.length >= 4 && isCategorySearchCursor(after)))
        val key = CacheKey(normalized.lowercase(Locale.ROOT), after)
        synchronized(cache) {
            cache[key]?.takeIf { nanoTime() - it.savedAt < CacheNanos }?.let { return it.categories }
        }
        if (!permits.tryAcquire()) throw CategorySearchException(busy = true)
        try {
            val categories = withContext(Dispatchers.IO) { fetch(normalized, after) }
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

    private fun fetch(query: String, after: String?): TwitchCategoryPage {
        val limit = categorySearchLimit(query)
        val payload = buildJsonObject {
            put("operationName", "SearchCategories")
            put("query", "query SearchCategories(\$query: String!, \$first: Int!, \$after: Cursor) { searchCategories(query: \$query, first: \$first, after: \$after) { edges { cursor node { id name } } pageInfo { hasNextPage } } }")
            put("variables", buildJsonObject { put("query", query); put("first", limit); put("after", after) })
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
                // Never silently truncate a page and skip the omitted records via its cursor.
                if (edges.size > limit) throw CategorySearchException()
                val categories = edges.mapNotNull { edge ->
                    val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null
                    val id = (node["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val name = (node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                    if (id == null || !id.matches(Regex("[0-9]{1,30}")) || name.isNullOrEmpty() ||
                        name.length > 200 || name.any(Char::isISOControl)) return@mapNotNull null
                    TwitchCategory(id, name)
                }.distinctBy { it.id }.distinctBy { it.name.lowercase(Locale.ROOT) }
                if (edges.isNotEmpty() && categories.isEmpty()) throw CategorySearchException()
                val pageInfo = result["pageInfo"] as? JsonObject ?: throw CategorySearchException()
                val hasNext = (pageInfo["hasNextPage"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: throw CategorySearchException()
                val nextCursor = if (query.length >= 4 && hasNext) {
                    // Twitch returns a null pageInfo.endCursor; the continuation is on the last edge.
                    val cursor = ((edges.lastOrNull() as? JsonObject)?.get("cursor") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.contentOrNull ?: throw CategorySearchException()
                    if (!isCategorySearchCursor(cursor) || cursor == after) throw CategorySearchException()
                    cursor
                } else null
                TwitchCategoryPage(categories, nextCursor)
            }
        } catch (error: java.io.IOException) {
            throw CategorySearchException()
        } catch (error: IllegalArgumentException) {
            throw CategorySearchException()
        }
    }

    private data class CacheKey(val query: String, val after: String?)
    private data class CachedCategories(val savedAt: Long, val categories: TwitchCategoryPage)
    private companion object {
        const val MaxResponseBytes = 128 * 1024
        val CacheNanos: Long = Duration.ofMinutes(5).toNanos()
    }
}
