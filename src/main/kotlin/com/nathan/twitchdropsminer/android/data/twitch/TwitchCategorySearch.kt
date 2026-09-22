package com.nathan.twitchdropsminer.android.data.twitch

import java.time.Duration
import java.util.Locale
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    override suspend fun search(request: CategorySearchRequest): TwitchCategoryPage {
        val key = CacheKey(request.query.lowercase(Locale.ROOT), request.after)
        synchronized(cache) {
            cache[key]?.takeIf { nanoTime() - it.savedAt < CacheNanos }?.let { return it.page }
        }
        if (!permits.tryAcquire()) throw CategorySearchException(busy = true)
        try {
            val page = withContext(Dispatchers.IO) { fetch(request) }
            synchronized(cache) {
                cache.remove(key)
                cache[key] = CachedCategories(nanoTime(), page)
                while (cache.size > MaxCachedPages) cache.remove(cache.keys.first())
            }
            return page
        } finally {
            permits.release()
        }
    }

    private fun fetch(search: CategorySearchRequest): TwitchCategoryPage {
        val payload = buildJsonObject {
            put("operationName", "SearchCategories")
            put("query", SearchQuery)
            put("variables", buildJsonObject {
                put("query", search.query)
                put("first", search.limit)
                put("after", search.after)
            })
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
                parsePage(bytes.toString(Charsets.UTF_8), search)
            }
        } catch (error: java.io.IOException) {
            throw CategorySearchException()
        } catch (error: IllegalArgumentException) {
            throw CategorySearchException()
        }
    }

    private fun parsePage(body: String, search: CategorySearchRequest): TwitchCategoryPage {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw CategorySearchException()
        val errors = root["errors"]
        if (errors != null && (errors !is JsonArray || errors.isNotEmpty())) throw CategorySearchException()
        val data = root["data"] as? JsonObject
        val result = data?.get("searchCategories") as? JsonObject
        val edges = result?.get("edges") as? JsonArray ?: throw CategorySearchException()
        // Never silently truncate a page and skip the omitted records via its cursor.
        if (edges.size > search.limit) throw CategorySearchException()
        val categories = edges.mapNotNull(::parseCategory)
            .distinctBy { it.id }
            .distinctBy { it.name.lowercase(Locale.ROOT) }
        if (edges.isNotEmpty() && categories.isEmpty()) throw CategorySearchException()
        val pageInfo = result["pageInfo"] as? JsonObject ?: throw CategorySearchException()
        val hasNext = (pageInfo["hasNextPage"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw CategorySearchException()
        val nextCursor = if (search.supportsPagination && hasNext) parseNextCursor(edges, search.after) else null
        return TwitchCategoryPage(categories, nextCursor)
    }

    private fun parseCategory(edge: JsonElement): TwitchCategory? {
        val node = (edge as? JsonObject)?.get("node") as? JsonObject ?: return null
        val id = node.string("id") ?: return null
        val name = node.string("name")?.trim() ?: return null
        if (!CategoryIdPattern.matches(id) || name.isEmpty() || name.length > 200 || name.any(Char::isISOControl)) {
            return null
        }
        return TwitchCategory(id, name)
    }

    private fun parseNextCursor(edges: JsonArray, after: String?): String {
        // Twitch returns a null pageInfo.endCursor; the continuation is on the last edge.
        val cursor = (edges.lastOrNull() as? JsonObject)?.string("cursor") ?: throw CategorySearchException()
        if (!CategorySearchRequest.isValidCursor(cursor) || cursor == after) throw CategorySearchException()
        return cursor
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private data class CacheKey(val query: String, val after: String?)
    private data class CachedCategories(val savedAt: Long, val page: TwitchCategoryPage)
    private companion object {
        const val MaxResponseBytes = 128 * 1024
        const val MaxCachedPages = 32
        val CategoryIdPattern = Regex("[0-9]{1,30}")
        val CacheNanos: Long = Duration.ofMinutes(5).toNanos()
        val SearchQuery = """
            query SearchCategories(${'$'}query: String!, ${'$'}first: Int!, ${'$'}after: Cursor) {
                searchCategories(query: ${'$'}query, first: ${'$'}first, after: ${'$'}after) {
                    edges { cursor node { id name } }
                    pageInfo { hasNextPage }
                }
            }
        """.trimIndent()
    }
}
