package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorization
import com.nathan.twitchdropsminer.android.data.twitch.DeviceTokenPollResult
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimResult
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.ValidatedToken
import com.nathan.twitchdropsminer.android.runtime.LocalMinerRuntime
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.io.TempDir

class WebServerTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var settings: SettingsRepository
    private lateinit var logs: LogRepository
    private lateinit var runtime: LocalMinerRuntime
    private lateinit var server: WebServer
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @BeforeTest
    fun startServer() {
        settings = SettingsRepository(directory)
        logs = LogRepository(directory)
        runtime = LocalMinerRuntime(
            settings,
            SecureSessionStore(directory, Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
            logs,
            NoopTwitchApi,
            OnlineNetwork,
        )
        server = WebServer(
            port = 0,
            runtime = runtime,
            settingsRepository = settings,
            logRepository = logs,
            trustedOrigins = setOf("http://127.0.0.1:*"),
            maxSseClients = 1,
        )
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `host is validated for health state events and static files`() {
        listOf("/api/health", "/api/state", "/api/events", "/app.js").forEach { path ->
            execute(path, host = "evil.example").use { response ->
                assertEquals(403, response.code, path)
                assertStructuredError(response)
            }
        }
    }

    @Test
    fun `trusted host and origin accept a valid persisted mutation`() {
        execute(
            "/api/settings",
            method = "PUT",
            body = """{"watchIntervalSeconds":21,"fallbackToOtherGames":true}""",
        ).use { response ->
            assertEquals(200, response.code)
            assertEquals("{\"ok\":true}", response.body!!.string())
        }

        val restarted = SettingsRepository(directory).settings.value
        assertEquals(21, restarted.watchIntervalSeconds)
        assertTrue(restarted.fallbackToOtherGames)
    }

    @Test
    fun `mutation origin is required and must be explicitly trusted`() {
        execute("/api/settings", "PUT", """{"debugLogging":true}""", origin = null).use {
            assertEquals(403, it.code)
            assertStructuredError(it)
        }
        execute(
            "/api/settings",
            "PUT",
            """{"debugLogging":true}""",
            origin = "https://evil.example",
        ).use {
            assertEquals(403, it.code)
            assertStructuredError(it)
        }
    }

    @Test
    fun `request methods content type body and route errors are stable json`() {
        execute("/api/state", "POST", "{}").use { assertError(it, 405) }
        execute("/api/settings", "POST", "{}").use { assertError(it, 405) }
        execute("/api/settings", "PUT", "{}", contentType = "text/plain").use { assertError(it, 415) }
        execute("/api/settings", "PUT", "").use { assertError(it, 400) }
        execute("/api/settings", "PUT", "{").use { assertError(it, 400) }
        execute("/api/missing").use { assertError(it, 404) }
        execute("/missing").use { assertError(it, 404) }
    }

    @Test
    fun `wrong json types ranges unknown fields and body size are rejected as 400 or 413`() {
        val invalid = listOf(
            "/api/settings" to """{"watchIntervalSeconds":"59"}""",
            "/api/settings" to """{"fallbackToOtherGames":"true"}""",
            "/api/settings" to """{"inventoryRefreshMinutes":-1}""",
            "/api/channels/select" to """{"channelId":-1}""",
            "/api/priorities/move" to """{"gameName":"Game","offset":99}""",
            "/api/priorities/set" to """{"gameName":false,"priority":1}""",
            "/api/campaigns/exclusion" to """{"campaignIds":[1],"excluded":true}""",
            "/api/miner/start" to """{"unexpected":true}""",
        )
        invalid.forEach { (path, body) ->
            execute(path, if (path == "/api/settings") "PUT" else "POST", body).use {
                assertError(it, 400)
            }
        }
        execute(
            "/api/settings",
            "PUT",
            "{\"debugLogging\":true,\"padding\":\"${"x".repeat(65 * 1024)}\"}",
        ).use { assertError(it, 413) }
    }

    @Test
    fun `concurrent settings mutations do not lose unrelated fields`() = runBlocking {
        listOf(
            """{"watchIntervalSeconds":37}""",
            """{"fallbackToOtherGames":true}""",
        ).map { body ->
            async(Dispatchers.IO) {
                execute("/api/settings", "PUT", body).use { response -> response.code }
            }
        }.awaitAll().forEach { assertEquals(200, it) }

        assertEquals(37, settings.settings.value.watchIntervalSeconds)
        assertTrue(settings.settings.value.fallbackToOtherGames)
    }

    @Test
    fun `priority response waits for persistence and survives restart`() {
        execute(
            "/api/priorities/toggle",
            "POST",
            """{"gameName":"Saved Gap Game"}""",
        ).use { assertEquals(200, it.code) }

        assertEquals(
            listOf("Saved Gap Game"),
            SettingsRepository(directory).settings.value.selectedGamePriority,
        )
    }

    @Test
    fun `sse clients are bounded and assets are revalidated`() {
        val first = execute("/api/events")
        try {
            assertEquals(200, first.code)
            execute("/api/events").use { second -> assertError(second, 503) }
        } finally {
            first.close()
        }

        listOf("/", "/app.js", "/app.css", "/theme-init.js").forEach { path ->
            execute(path).use { response ->
                assertEquals(200, response.code)
                assertEquals("no-cache", response.header("Cache-Control"))
            }
        }
    }

    private fun execute(
        path: String,
        method: String = "GET",
        body: String? = null,
        host: String? = null,
        origin: String? = if (method == "GET") null else "http://127.0.0.1:${server.boundPort}",
        contentType: String = "application/json",
    ): Response {
        val builder = Request.Builder().url("http://127.0.0.1:${server.boundPort}$path")
        if (host != null) builder.header("Host", host)
        if (origin != null) builder.header("Origin", origin)
        val requestBody = body?.toRequestBody(contentType.toMediaType())
        builder.method(method, requestBody)
        return client.newCall(builder.build()).execute()
    }

    private fun assertError(response: Response, status: Int) {
        assertEquals(status, response.code)
        assertStructuredError(response)
    }

    private fun assertStructuredError(response: Response) {
        val body = response.body!!.string()
        assertTrue(body.startsWith("{\"error\":"), body)
        assertFalse(body.contains("accessToken"))
        assertFalse(body.contains("deviceCode"))
        assertFalse(body.contains("filesystem-secret"))
    }
}

private object OnlineNetwork : NetworkStatusProvider {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
}

private object NoopTwitchApi : TwitchApi {
    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun validateAccessToken(accessToken: String): ValidatedToken = unused()
    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = emptyList()
    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> = emptyList()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = false
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = null
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun unused(): Nothing = error("Not used")
}
