package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.AutoModePriority
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.runtime.LocalMinerRuntime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WebServer(
    port: Int,
    private val runtime: LocalMinerRuntime,
    private val settingsRepository: SettingsRepository,
    private val logRepository: LogRepository,
    listenHost: String = "127.0.0.1",
    allowLanAccess: Boolean = false,
    trustedHosts: Set<String> = setOf("localhost", "127.0.0.1", "[::1]"),
    trustedOrigins: Set<String> = setOf(
        "http://localhost:$port",
        "http://127.0.0.1:$port",
        "http://[::1]:$port",
    ),
    private val maxSseClients: Int = DefaultMaxSseClients,
) : AutoCloseable {
    private val stateJson = StateJson()
    private val requestTrust = TrustedRequestPolicy(trustedHosts, trustedOrigins, allowLanAccess)
    private val mutationMutex = Mutex()
    private val eventClients = Semaphore(maxSseClients.coerceAtLeast(1), true)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress(listenHost, port), 0).apply {
        this.executor = this@WebServer.executor
        createContext("/", ::handle)
    }

    val boundPort: Int
        get() = server.address.port

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(1)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        addSecurityHeaders(exchange)
        try {
            requestTrust.verifyHost(exchange.requestHeaders["Host"])
            when (exchange.requestURI.path) {
                "/api/health" -> exchange.requireGetAndRespond("{\"status\":\"ok\"}")
                "/api/state" -> exchange.requireGetAndRespond(currentState())
                "/api/events" -> {
                    exchange.requireMethod("GET")
                    streamEvents(exchange)
                }

                in MutationRoutes -> handleApiMutation(exchange)
                "/", "/index.html", "/app.css", "/app.js", "/theme-init.js", "/favicon.svg" ->
                    serveStatic(exchange)

                else -> throw RequestException(404, "Route not found.")
            }
        } catch (_: IOException) {
            exchange.close()
        } catch (error: RequestException) {
            exchange.respondError(error.status, error.message ?: "Invalid request.")
        } catch (_: Throwable) {
            System.err.println("HTTP request failed without exposing internal details.")
            exchange.respondError(500, "The request could not be completed.")
        }
    }

    private fun handleApiMutation(exchange: HttpExchange) {
        exchange.requireMethod("POST", "PUT")
        verifyMutationRequest(exchange)
        val body = exchange.readJsonBody()
        val accepted = try {
            runBlocking {
                mutationMutex.withLock {
                    dispatchMutation(exchange.requestURI.path, exchange.requestMethod, body)
                }
            }
        } catch (error: RequestException) {
            throw error
        } catch (_: IOException) {
            throw RequestException(
                500,
                "Local data could not be saved; check data-directory permissions and available space.",
            )
        } catch (_: SecurityException) {
            throw RequestException(
                500,
                "Local data could not be saved; check data-directory permissions.",
            )
        }
        exchange.respondJson(if (accepted) 202 else 200, "{\"ok\":true}")
    }

    private suspend fun dispatchMutation(path: String, method: String, body: JsonObject): Boolean {
        val expectedMethod = if (path == "/api/settings") "PUT" else "POST"
        if (method != expectedMethod) {
            throw RequestException(405, "Method not allowed.")
        }
        return when (path) {
            "/api/auth/start" -> body.noFields().let { runtime.startAuthentication(); true }
            "/api/auth/replace" -> body.noFields().let { runtime.replaceAuthentication(); true }
            "/api/miner/start" -> body.noFields().let { runtime.startMining(); true }
            "/api/miner/stop" -> body.noFields().let { runtime.stopMining(); true }
            "/api/inventory/refresh" -> body.noFields().let { runtime.refreshInventory(); true }
            "/api/session/reset" -> body.noFields().let { runtime.resetSessionAndJoin(); false }
            "/api/logs/clear" -> body.noFields().let { logRepository.clear(); false }
            "/api/channels/find" -> body.noFields().let { runtime.findNewChannel(); true }
            "/api/channels/select" -> {
                body.onlyFields("channelId")
                runtime.selectChannel(body.requiredLong("channelId", 1L..Long.MAX_VALUE))
                true
            }

            "/api/priorities/toggle" -> {
                body.onlyFields("gameName")
                runtime.toggleGamePriority(body.requiredString("gameName", MaxNameLength))
                false
            }

            "/api/priorities/move" -> {
                body.onlyFields("gameName", "offset")
                runtime.moveGamePriority(
                    body.requiredString("gameName", MaxNameLength),
                    body.requiredInt("offset", -1..1).takeUnless { it == 0 }
                        ?: throw RequestException(400, "offset must be -1 or 1."),
                )
                false
            }

            "/api/priorities/set" -> {
                body.onlyFields("gameName", "priority")
                runtime.setGamePriority(
                    body.requiredString("gameName", MaxNameLength),
                    body.requiredInt("priority", 1..MaxPriorityNumber),
                )
                false
            }

            "/api/priorities/clear" -> body.noFields().let {
                runtime.clearGamePriority()
                false
            }

            "/api/campaigns/exclusion" -> {
                body.onlyFields("campaignIds", "excluded")
                settingsRepository.setCampaignExclusion(
                    body.requiredStringList("campaignIds", MaxCampaignIds, MaxIdentifierLength),
                    body.requiredBoolean("excluded"),
                )
                false
            }

            "/api/settings" -> {
                body.onlyFields(
                    "watchIntervalSeconds",
                    "inventoryRefreshMinutes",
                    "fallbackToOtherGames",
                    "debugLogging",
                )
                if (body.isEmpty()) throw RequestException(400, "At least one setting is required.")
                settingsRepository.update { current ->
                    current.copy(
                        watchIntervalSeconds = body.optionalInt("watchIntervalSeconds", 20..300)
                            ?: current.watchIntervalSeconds,
                        inventoryRefreshMinutes = body.optionalInt("inventoryRefreshMinutes", 15..180)
                            ?: current.inventoryRefreshMinutes,
                        fallbackToOtherGames = body.optionalBoolean("fallbackToOtherGames")
                            ?: current.fallbackToOtherGames,
                        debugLogging = body.optionalBoolean("debugLogging") ?: current.debugLogging,
                    )
                }
                false
            }

            "/api/settings/auto-priority/move" -> {
                body.onlyFields("key", "offset")
                val option = AutoModePriority.fromStorageKey(body.requiredString("key", 80))
                    ?: throw RequestException(400, "Unknown Auto Mode priority key.")
                val offset = body.requiredInt("offset", -1..1).takeUnless { it == 0 }
                    ?: throw RequestException(400, "offset must be -1 or 1.")
                settingsRepository.moveAutoModePriority(option, offset)
                false
            }

            "/api/settings/reset" -> body.noFields().let {
                settingsRepository.resetSettings()
                false
            }

            else -> throw RequestException(404, "API route not found.")
        }
    }

    private fun verifyMutationRequest(exchange: HttpExchange) {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")
            ?.substringBefore(';')
            ?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) {
            throw RequestException(415, "Mutations require application/json.")
        }
        requestTrust.verifyOrigin(
            exchange.requestHeaders["Origin"],
            exchange.requestHeaders["Host"],
        )
    }

    private fun streamEvents(exchange: HttpExchange) {
        if (!eventClients.tryAcquire()) {
            throw RequestException(503, "Too many event streams are already open; use state polling.")
        }
        try {
            exchange.responseHeaders.apply {
                set("Content-Type", "text/event-stream; charset=utf-8")
                set("Cache-Control", "no-cache, no-transform")
                set("Connection", "keep-alive")
            }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                var lastSource: StateSource? = null
                var nextKeepAliveAt = 0L
                while (!Thread.currentThread().isInterrupted) {
                    val source = currentStateSource()
                    val now = System.nanoTime()
                    val wroteEvent = if (source != lastSource) {
                        writer.write("event: state\n")
                        writer.write("data: ")
                        writer.write(currentState(source))
                        writer.write("\n\n")
                        lastSource = source
                        true
                    } else if (now >= nextKeepAliveAt) {
                        writer.write(": keep-alive\n\n")
                        true
                    } else {
                        false
                    }
                    if (wroteEvent) {
                        writer.flush()
                        nextKeepAliveAt = now + EventKeepAliveNanos
                    }
                    try {
                        Thread.sleep(EventStateCheckMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        } finally {
            eventClients.release()
        }
    }

    private fun currentStateSource() = StateSource(
        snapshot = runtime.snapshot.value,
        settings = settingsRepository.settings.value,
        logs = logRepository.entries.value,
    )

    private fun currentState(source: StateSource = currentStateSource()): String = stateJson.encode(
        snapshot = source.snapshot,
        settings = source.settings,
        logs = source.logs,
    )

    private fun serveStatic(exchange: HttpExchange) {
        exchange.requireMethod("GET")
        val resource = when (exchange.requestURI.path) {
            "/", "/index.html" -> "/web/index.html"
            "/app.css" -> "/web/app.css"
            "/app.js" -> "/web/app.js"
            "/theme-init.js" -> "/web/theme-init.js"
            "/favicon.svg" -> "/web/favicon.svg"
            else -> throw RequestException(404, "Static resource not found.")
        }
        val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw RequestException(404, "Static resource not found.")
        val contentType = when {
            resource.endsWith(".html") -> "text/html; charset=utf-8"
            resource.endsWith(".css") -> "text/css; charset=utf-8"
            resource.endsWith(".js") -> "text/javascript; charset=utf-8"
            resource.endsWith(".svg") -> "image/svg+xml; charset=utf-8"
            else -> "application/octet-stream"
        }
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set(
            "Cache-Control",
            if (resource.endsWith("favicon.svg")) "public, max-age=86400" else "no-cache",
        )
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun addSecurityHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.apply {
            set("Content-Security-Policy", "default-src 'self'; img-src 'self' https: data:; style-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
            set("X-Content-Type-Options", "nosniff")
            set("X-Frame-Options", "DENY")
            set("Referrer-Policy", "no-referrer")
            set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
        }
    }
}

internal class TrustedRequestPolicy(
    trustedHosts: Set<String>,
    trustedOrigins: Set<String>,
    private val allowLanAccess: Boolean = false,
) {
    private val hosts = trustedHosts.map { value ->
        parseAuthority(value) ?: throw IllegalArgumentException("Invalid trusted Host entry.")
    }
    private val origins = trustedOrigins.map { value ->
        parseOriginPattern(value) ?: throw IllegalArgumentException("Invalid trusted origin entry.")
    }

    init {
        require(hosts.isNotEmpty()) { "At least one trusted Host is required." }
        require(origins.isNotEmpty()) { "At least one trusted origin is required." }
    }

    fun verifyHost(values: List<String>?) {
        val value = values?.singleOrNull()
            ?: throw RequestException(400, "Exactly one Host header is required.")
        val authority = parseAuthority(value)
            ?: throw RequestException(400, "Host header is invalid.")
        val trustedLanAddress = allowLanAccess && authority.host.isLanAddressLiteral()
        if (hosts.none { allowed -> allowed.matches(authority) } && !trustedLanAddress) {
            throw RequestException(403, "Host is not trusted.")
        }
    }

    fun verifyOrigin(values: List<String>?, hostValues: List<String>?) {
        val value = values?.singleOrNull()
            ?: throw RequestException(403, "A trusted Origin header is required.")
        val origin = parseOrigin(value)
            ?: throw RequestException(403, "Request origin could not be verified.")
        val requestHost = hostValues?.singleOrNull()?.let(::parseAuthority)
        val trustedLanOrigin = allowLanAccess &&
            origin.scheme == "http" &&
            origin.authority.host.isLanAddressLiteral() &&
            requestHost != null &&
            requestHost.host == origin.authority.host &&
            (requestHost.port ?: 80) == origin.authority.port
        if (origins.none { allowed -> allowed.matches(origin) } && !trustedLanOrigin) {
            throw RequestException(403, "Request origin is not trusted.")
        }
    }
}

private const val MaxJsonBodyBytes = 64 * 1024
private const val MaxNameLength = 200
private const val MaxIdentifierLength = 256
private const val MaxCampaignIds = 500
private const val MaxPriorityNumber = 500
private const val DefaultMaxSseClients = 32
private const val EventStateCheckMillis = 2_000L
private const val EventKeepAliveNanos = 15_000_000_000L

private val MutationRoutes = setOf(
    "/api/auth/start",
    "/api/auth/replace",
    "/api/miner/start",
    "/api/miner/stop",
    "/api/inventory/refresh",
    "/api/session/reset",
    "/api/logs/clear",
    "/api/channels/find",
    "/api/channels/select",
    "/api/priorities/toggle",
    "/api/priorities/move",
    "/api/priorities/set",
    "/api/priorities/clear",
    "/api/campaigns/exclusion",
    "/api/settings",
    "/api/settings/auto-priority/move",
    "/api/settings/reset",
)

private data class StateSource(
    val snapshot: RuntimeSnapshot,
    val settings: AppSettings,
    val logs: List<LocalLogEntry>,
)

private data class Authority(val host: String, val port: Int?) {
    fun matches(candidate: Authority): Boolean =
        host == candidate.host && (port == null || port == candidate.port)
}

private data class OriginPattern(
    val scheme: String,
    val authority: Authority,
    val anyPort: Boolean,
) {
    fun matches(candidate: OriginPattern): Boolean =
        scheme == candidate.scheme &&
            authority.host == candidate.authority.host &&
            (anyPort || authority.port == candidate.authority.port)
}

private fun parseAuthority(raw: String): Authority? {
    val value = raw.trim()
    if (value.isEmpty() || value.any(Char::isWhitespace) || value.any { it in "/?#@,\\" }) return null
    val uri = runCatching { URI("http://$value") }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']') ?: return null
    if (host.isEmpty() || uri.rawUserInfo != null || uri.rawPath.orEmpty().isNotEmpty()) return null
    return Authority(host, uri.port.takeIf { it >= 0 })
}

private fun String.isLanAddressLiteral(): Boolean {
    val ipv4 = split('.').takeIf { segments ->
        segments.size == 4 && segments.all { segment ->
            segment.isNotEmpty() && segment.all(Char::isDigit)
        }
    }?.map { segment -> segment.toIntOrNull() ?: return false }
    if (ipv4 != null && ipv4.all { it in 0..255 }) {
        return ipv4[0] == 10 ||
            (ipv4[0] == 172 && ipv4[1] in 16..31) ||
            (ipv4[0] == 192 && ipv4[1] == 168) ||
            (ipv4[0] == 169 && ipv4[1] == 254)
    }
    if (':' !in this) return false
    val address = runCatching { InetAddress.getByName(this) }.getOrNull()
    if (address !is Inet6Address) return false
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return (first and 0xfe) == 0xfc || (first == 0xfe && (second and 0xc0) == 0x80)
}

private fun parseOriginPattern(raw: String): OriginPattern? {
    val trimmed = raw.trim()
    val wildcard = trimmed.endsWith(":*")
    val parseable = if (wildcard) trimmed.dropLast(1) + "1" else trimmed
    val parsed = parseOrigin(parseable) ?: return null
    return parsed.copy(anyPort = wildcard)
}

private fun parseOrigin(raw: String): OriginPattern? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" }
        ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.rawPath.orEmpty() !in setOf("", "/")) return null
    val host = uri.host?.lowercase(Locale.ROOT)?.trim('[', ']') ?: return null
    val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
    return OriginPattern(scheme, Authority(host, port), anyPort = false)
}

private fun HttpExchange.requireGetAndRespond(body: String) {
    requireMethod("GET")
    respondJson(200, body)
}

private fun HttpExchange.requireMethod(vararg allowed: String) {
    if (requestMethod !in allowed) throw RequestException(405, "Method not allowed.")
}

private fun HttpExchange.readJsonBody(): JsonObject {
    requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let { declared ->
        if (declared > MaxJsonBodyBytes) throw RequestException(413, "Request body is too large.")
    }
    val bytes = requestBody.readNBytes(MaxJsonBodyBytes + 1)
    if (bytes.size > MaxJsonBodyBytes) throw RequestException(413, "Request body is too large.")
    if (bytes.isEmpty()) throw RequestException(400, "Request body must be a JSON object.")
    return runCatching {
        Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as JsonObject
    }.getOrElse { throw RequestException(400, "Request body must be a JSON object.") }
}

private fun HttpExchange.respondJson(status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.respondError(status: Int, message: String) {
    val body = buildJsonObject { put("error", message) }.toString()
    runCatching { respondJson(status, body) }.onFailure { close() }
}

private fun JsonObject.noFields() = onlyFields()

private fun JsonObject.onlyFields(vararg allowed: String) {
    val unknown = keys - allowed.toSet()
    if (unknown.isNotEmpty()) throw RequestException(400, "Unknown request field: ${unknown.sorted().first()}.")
}

private fun JsonObject.requiredString(key: String, maxLength: Int): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw RequestException(400, "$key must be a JSON string.")
    if (!primitive.isString) throw RequestException(400, "$key must be a JSON string.")
    val value = primitive.content.trim()
    if (value.isEmpty()) throw RequestException(400, "$key must not be blank.")
    if (value.length > maxLength) throw RequestException(400, "$key is too long.")
    return value
}

private fun JsonObject.requiredInt(key: String, range: IntRange): Int =
    optionalInt(key, range) ?: throw RequestException(400, "$key must be an integer.")

private fun JsonObject.requiredLong(key: String, range: LongRange): Long {
    val primitive = this[key] as? JsonPrimitive
        ?: throw RequestException(400, "$key must be an integer.")
    if (primitive.isString) throw RequestException(400, "$key must be an integer.")
    val value = primitive.contentOrNull?.toLongOrNull()
        ?: throw RequestException(400, "$key must be an integer.")
    if (value !in range) throw RequestException(400, "$key is outside the allowed range.")
    return value
}

private fun JsonObject.requiredBoolean(key: String): Boolean =
    optionalBoolean(key) ?: throw RequestException(400, "$key must be a boolean.")

private fun JsonObject.requiredStringList(
    key: String,
    maxItems: Int,
    maxItemLength: Int,
): List<String> {
    val array = this[key] as? JsonArray
        ?: throw RequestException(400, "$key must be an array of strings.")
    if (array.isEmpty()) throw RequestException(400, "$key must contain at least one value.")
    if (array.size > maxItems) throw RequestException(400, "$key contains too many values.")
    return array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
            ?: throw RequestException(400, "$key[$index] must be a JSON string.")
        if (!primitive.isString) throw RequestException(400, "$key[$index] must be a JSON string.")
        primitive.content.trim().also { value ->
            if (value.isEmpty()) throw RequestException(400, "$key[$index] must not be blank.")
            if (value.length > maxItemLength) throw RequestException(400, "$key[$index] is too long.")
        }
    }.distinctBy(String::lowercase)
}

private fun JsonObject.optionalInt(key: String, range: IntRange): Int? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
        ?: throw RequestException(400, "$key must be an integer.")
    if (primitive.isString) throw RequestException(400, "$key must be an integer.")
    val value = primitive.intOrNull ?: throw RequestException(400, "$key must be an integer.")
    if (value !in range) throw RequestException(400, "$key is outside the allowed range.")
    return value
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive
        ?: throw RequestException(400, "$key must be a boolean.")
    if (primitive.isString) throw RequestException(400, "$key must be a boolean.")
    return primitive.booleanOrNull ?: throw RequestException(400, "$key must be a boolean.")
}

private class RequestException(val status: Int, message: String) : IllegalArgumentException(message)
