package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import com.nathan.twitchdropsminer.android.data.model.BrowserLeaseUnavailableException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Fixed loopback transport only. The runtime owns retries, account checks and persistence. */
class BrowserRenewalClient(private val port: Int = 8091) : AutoCloseable {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(Duration.ofSeconds(5)).build()

    suspend fun renew(context: BrowserSessionContext): BrowserSessionContext = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        try {
            withTimeoutOrNull(180_000) {
                currentCoroutineContext().ensureActive()
                request("renew", buildJsonObject { put("id", id); put("context", context.toJson()) })
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val response = request("status")
                    check(response["id"]?.jsonPrimitive?.content == id) { "Browser renewal was replaced." }
                    if (response["error"]?.jsonPrimitive?.content == "browser_missing") throw BrowserLeaseUnavailableException()
                    check(response["state"]?.jsonPrimitive?.content != "failed") { "Browser renewal temporarily unavailable." }
                    val captured = response["context"] as? JsonObject
                    if (captured != null) return@withTimeoutOrNull BrowserSessionContext.parse(captured)
                    delay(750)
                }
                @Suppress("UNREACHABLE_CODE") error("Browser renewal unavailable.")
            } ?: error("Browser renewal timed out.")
        } finally {
            withContext(NonCancellable) {
                runCatching { request("release", buildJsonObject { put("id", id) }) }
            }
        }
    }

    suspend fun revoke(lease: String) = withContext(Dispatchers.IO) {
        request("revoke", buildJsonObject { put("id", UUID.randomUUID().toString()); put("lease", lease) })
        Unit
    }

    private fun request(action: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url("http://127.0.0.1:$port/$action").header("X-DockDrops-Internal", "1")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "Browser renewal temporarily unavailable." }
            val bytes = response.body?.byteStream()?.use { it.readNBytes(40 * 1024 + 1) } ?: error("Missing renewal response.")
            require(bytes.size <= 40 * 1024) { "Invalid renewal response." }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("Invalid renewal response.")
        }
    }

    override fun close() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
