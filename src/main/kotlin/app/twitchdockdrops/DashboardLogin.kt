package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import com.nathan.twitchdropsminer.android.runtime.LocalMinerRuntime
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Optional, loopback-only browser companion. Owns authentication transport, never mining. */
class DashboardLogin(private val runtime: LocalMinerRuntime, private val port: Int = 8091) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(Duration.ofSeconds(5)).build()
    private var job: Job? = null
    @Volatile private var status = View("", "idle", "")
    private data class View(val id: String, val state: String, val error: String)

    fun view(): JsonObject = status.let { view -> buildJsonObject {
        put("id", view.id); put("state", view.state); put("error", view.error)
    } }

    @Synchronized fun start(ticket: String) {
        job?.cancel()
        val id = UUID.randomUUID().toString()
        status = View(id, "starting", "")
        job = scope.launch {
            lifecycle.withLock {
                try {
                    runtime.browserLoginStatus(ticket) // A superseded start must not open a browser.
                    request("start", buildJsonObject { put("id", id) })
                    var submitted = 0
                    var durable = false
                    while (isActive) {
                        val admission = runtime.browserLoginStatus(ticket)
                        if (admission == "failed") throw LoginFailure("Twitch rejected account or Drops verification. Retry sign-in using the desktop helper; saved credentials were preserved.")
                        val response = request("status")
                        val state = response["state"]?.jsonPrimitive?.content
                        when (state) {
                            "interactive", "starting", "capturing", "ready" -> publish(id, state)
                            "failed" -> throw LoginFailure(browserFailureMessage(response["error"]?.jsonPrimitive?.content))
                            else -> error("Unexpected browser state")
                        }
                        val sequence = response["sequence"]?.jsonPrimitive?.intOrNull ?: 0
                        if (sequence > submitted) {
                            val context = BrowserSessionContext.parse(response["context"] as? JsonObject ?: error("Missing context"))
                            context.requireFresh()
                            runtime.submitBrowserSession(ticket, context)
                            durable = context.sdkCookie != null
                            submitted = sequence
                        }
                        if (submitted > 0 && response["context"] is JsonObject && runtime.browserLoginStatus(ticket) == "ready" && state == "capturing") {
                            request("accepted", buildJsonObject { put("id", id); put("sequence", submitted) })
                            publish(id, "ready")
                            // Runtime owns durable renewal independently of this view and lease.
                            if (durable) return@withLock
                        }
                        delay(if (state == "ready") 5_000 else 750)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: LoginFailure) { publish(id, "failed", error.message.orEmpty()) }
                catch (_: Throwable) {
                    publish(id, "failed", "Dashboard browser or Twitch verification failed. Retry sign-in, or use the desktop helper. Check that the browser service is running.")
                } finally {
                    withContext(NonCancellable) {
                        runCatching { request("cancel", buildJsonObject { put("id", id) }) }
                    }
                }
            }
        }
    }

    private class LoginFailure(message: String) : RuntimeException(message)

    companion object {
        internal fun browserFailureMessage(code: String?): String = when (code) {
            "login_timeout" -> "Twitch sign-in timed out. Start again and finish Twitch verification within eight minutes."
            "capture_failed" -> "The signed-in browser did not return verified Drops access. Complete Twitch verification before Finish sign-in, or try the desktop helper."
            "seed_failed" -> "Drops access was captured, but the browser could not prepare a renewal seed. Retry sign-in or use the desktop helper. Saved credentials were preserved."
            "issuance_failed" -> "Drops access was captured, but the separate renewal browser could not obtain fresh Twitch proof. Retry sign-in or use the desktop helper. Saved credentials were preserved."
            "acceptance_timeout" -> "Browser proof was captured, but server verification did not finish in time. Check the dashboard connection status before restarting sign-in."
            else -> "The login browser stopped. Check the browser container and retry sign-in."
        }
    }

    @Synchronized private fun publish(id: String, state: String, error: String = "") {
        if (status.id == id) status = View(id, state, error)
    }

    @Synchronized fun cancel() {
        status = View("", "idle", "")
        job?.cancel(); job = null
    }

    fun command(action: String, body: JsonObject) {
        val current = status
        require(body["id"]?.jsonPrimitive?.content == current.id && current.id.isNotEmpty()) { "Login view expired. Open it again." }
        require(current.state == "interactive") { "The login browser is not accepting input." }
        request(action, body)
    }

    fun frame(id: String): String {
        require(id == status.id && status.state == "interactive") { "Login view expired." }
        val response = request("frame")
        require(response["id"]?.jsonPrimitive?.content == id && status.id == id && status.state == "interactive") { "Login view expired." }
        val data = response["image"]?.jsonPrimitive?.content ?: error("Missing frame")
        require(data.length <= 2_000_000 && data.matches(Regex("[A-Za-z0-9+/=]+")))
        return data
    }

    private fun request(action: String, body: JsonObject? = null): JsonObject {
        val request = Request.Builder().url("http://127.0.0.1:$port/$action")
            .header("X-DockDrops-Internal", "1")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "Browser service unavailable" }
            val bytes = response.body?.byteStream()?.use { it.readNBytes(2_100_001) } ?: error("Missing browser response")
            require(bytes.size <= 2_100_000)
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("Invalid browser response")
        }
    }

    override fun close() {
        cancel()
        runBlocking { withTimeoutOrNull(6_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
