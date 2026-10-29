package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

class ChannelRestrictionsTest {
    private val now = Instant.now()
    private fun response(status: String, id: String = "42") = Json.parseToJsonElement(
        """{"data":{"user":{"id":"$id","self":{"banStatus":$status}}}}""",
    ).jsonObject

    @Test fun `permanent and temporary restrictions require exact typed channel evidence`() {
        assertEquals(ChannelRestrictionResult.Banned(true, null), parseChannelRestriction(response("""{"isPermanent":true,"expiresAt":"2020-01-01T00:00:00Z"}"""), 42))
        val expiry = now.plusSeconds(90)
        assertEquals(ChannelRestrictionResult.Banned(false, expiry), parseChannelRestriction(response("""{"isPermanent":false,"expiresAt":"$expiry"}"""), 42))
        for (expiryText in listOf("garbage", "2026-10-29T10:00:00")) {
            assertEquals(ChannelRestrictionResult.Banned(false, null), parseChannelRestriction(response("""{"isPermanent":false,"expiresAt":"$expiryText"}"""), 42))
        }
        assertEquals(ChannelRestrictionResult.Unbanned, parseChannelRestriction(response("null"), 42))
        for (invalid in listOf(response("null", "43"), response("{}"), response("""{"isPermanent":"false"}"""),
            JsonObject(response("null") + ("errors" to JsonArray(listOf(buildJsonObject { put("message", "fixture-secret") })))),
            Json.parseToJsonElement("""{"data":{"user":{"id":"42","self":{}}}}""").jsonObject)) {
            assertEquals(ChannelRestrictionResult.Unknown, parseChannelRestriction(invalid, 42))
        }
    }

    @Test fun `failed checks and rediscovery preserve bans but explicit unban and verified expiry release them`() {
        val cache = ChannelRestrictions(checkInterval = Duration.ofSeconds(1))
        cache.bind("account")
        cache.complete(assertNotNull(cache.begin("account", 42, now)), ChannelRestrictionResult.Banned(true, null))
        assertFalse(cache.allowed("account", 42, now))
        cache.bind("account")
        cache.complete(assertNotNull(cache.begin("account", 42, now.plusSeconds(2))), ChannelRestrictionResult.Unknown)
        assertFalse(cache.allowed("account", 42, now.plusSeconds(2)))
        cache.complete(assertNotNull(cache.begin("account", 42, now.plusSeconds(4))), ChannelRestrictionResult.Unbanned)
        assertTrue(cache.allowed("account", 42, now.plusSeconds(4)))
        cache.complete(assertNotNull(cache.begin("account", 42, now.plusSeconds(6))), ChannelRestrictionResult.Banned(false, now.plusSeconds(10)))
        assertEquals(now.plusSeconds(10), cache.nextExpiry("account", now.plusSeconds(6)))
        assertFalse(cache.allowed("account", 42, now.plusSeconds(9)))
        assertTrue(cache.allowed("account", 42, now.plusSeconds(10)))
    }

    @Test fun `account replacement and reset invalidate late checks and capacity never evicts active bans`() {
        val cache = ChannelRestrictions(capacity = 1)
        cache.bind("first")
        val late = assertNotNull(cache.begin("first", 42, now))
        cache.bind("second")
        cache.complete(late, ChannelRestrictionResult.Banned(true, null))
        assertTrue(cache.allowed("second", 42, now))
        cache.complete(assertNotNull(cache.begin("second", 42, now)), ChannelRestrictionResult.Banned(true, null))
        assertNull(cache.begin("second", 43, now))
        assertFalse(cache.allowed("second", 43, now))
        assertFalse(cache.allowed("second", 42, now))
        cache.bind(null)
        cache.complete(late, ChannelRestrictionResult.Unbanned)
        assertFalse(cache.allowed("second", 42, now))
        cache.bind("second")
        assertTrue(cache.allowed("second", 42, now))
    }

    @Test fun `browser checks are cached failed checks preserve bans and watch never queries`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var time = now
            val api = client(server) { time }
            val session = session()
            server.enqueue(MockResponse().setBody(response("""{"isPermanent":false}""").toString()))
            assertFalse(api.checkChannelEligibility(session, 42))
            repeat(3) {
                assertFalse(api.checkChannelEligibility(session, 42))
                assertFalse(api.pollWatchStream(session, channel(42)) { true })
                assertFalse(api.sendWatchMinute(session, channel(42)))
            }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertTrue(request.body.readUtf8().contains("ChannelBanStatus"))
            assertEquals("OAuth fixture-token", request.getHeader("Authorization"))
            time = now.plusSeconds(181)
            server.enqueue(MockResponse().setResponseCode(503).setBody("fixture-secret"))
            assertFalse(api.checkChannelEligibility(session, 42))
            api.bindChannelAccount(session.userId)
            assertFalse(api.checkChannelEligibility(session, 42))
            assertEquals(2, server.requestCount)
            time = now.plusSeconds(362)
            server.enqueue(MockResponse().setBody(response("null").toString()))
            assertTrue(api.checkChannelEligibility(session, 42))
        }
    }

    @Test fun `temporary expiry and wrong channel responses never erase a current ban`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var time = now
            val api = client(server) { time }
            server.enqueue(MockResponse().setBody(response("""{"isPermanent":false,"expiresAt":"${now.plusSeconds(300)}"}""").toString()))
            assertFalse(api.checkChannelEligibility(session(), 42))
            time = now.plusSeconds(181)
            server.enqueue(MockResponse().setBody(response("null", "43").toString()))
            assertFalse(api.checkChannelEligibility(session(), 42))
            time = now.plusSeconds(300)
            assertTrue(api.isChannelAllowed("12345", 42))
        }
    }

    @Test fun `malformed and unsupported successful responses neither invent nor erase restrictions`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var time = now
            val api = client(server) { time }
            val invalidBodies = listOf(
                "not-json",
                "[]",
                """{"errors":[{"message":"unsupported fixture query"}]}""",
                """{"data":{"user":{"id":"42","self":{"banStatus":{"isPermanent":"false"}}}}}""",
                response("null", "43").toString(),
            )
            for (body in invalidBodies) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(api.checkChannelEligibility(session(), 42))
                time = time.plusSeconds(181)
            }
            server.enqueue(MockResponse().setBody(response("""{"isPermanent":false}""").toString()))
            assertFalse(api.checkChannelEligibility(session(), 42))
            for (body in invalidBodies) {
                time = time.plusSeconds(181)
                server.enqueue(MockResponse().setBody(body))
                assertFalse(api.checkChannelEligibility(session(), 42))
            }
            assertEquals(11, server.requestCount)
        }
    }

    @Test fun `concurrent checks for one channel share a single bounded request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = client(server)
            server.enqueue(MockResponse().setBody(response("""{"isPermanent":true}""").toString()).setBodyDelay(200, TimeUnit.MILLISECONDS))
            val first = async { api.checkChannelEligibility(session(), 42) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            coroutineScope { List(20) { async { api.checkChannelEligibility(session(), 42) } }.awaitAll() }
            assertFalse(first.await())
            assertFalse(api.checkChannelEligibility(session(), 42))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `directory restriction workers are bounded and cancellation cannot install late evidence`() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(4)
            val release = CountDownLatch(1)
            val checks = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return when (payload["operationName"]!!.jsonPrimitive.content) {
                        "ChannelBanStatus" -> {
                            checks.incrementAndGet()
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            val id = payload["variables"]!!.jsonObject["channelID"]!!.jsonPrimitive.content
                            MockResponse().setBody(response("""{"isPermanent":true}""", id).toString())
                        }
                        "DirectoryPage_Game" -> {
                            val edges = (42..61).joinToString(",") { id ->
                                """{"node":{"id":"broadcast$id","viewersCount":100,"broadcaster":{"id":"$id","login":"channel$id","displayName":"Channel$id"}}}"""
                            }
                            MockResponse().setBody("""{"data":{"game":{"streams":{"edges":[$edges]}}}}""")
                        }
                        else -> MockResponse().setBody("""{"data":{"game":{"slug":"game"}}}""")
                    }
                }
            }
            server.start()
            val api = client(server)
            val discovery = async { api.fetchEligibleChannels(session(), Campaign("campaign", "Campaign", "Game")) }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                assertEquals(4, checks.get())
                withTimeout(1_000) { discovery.cancelAndJoin() }
            } finally {
                release.countDown()
                discovery.cancelAndJoin()
            }
            assertEquals(4, checks.get())
            for (id in 42L..61L) assertTrue(api.isChannelAllowed("12345", id))
        }
    }

    @Test fun `ban confirmed while an HLS playlist is in flight prevents media and telemetry delivery`() = runBlocking {
        MockWebServer().use { server ->
            val playlistStarted = CountDownLatch(1)
            val releasePlaylist = CountDownLatch(1)
            val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.method to request.requestUrl?.encodedPath
                    return when {
                        request.path == "/gql" -> {
                            val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            if (payload["operationName"]!!.jsonPrimitive.content == "ChannelBanStatus") {
                                MockResponse().setBody(response("""{"isPermanent":true}""").toString())
                            } else {
                                MockResponse().setBody("""{"data":{"streamPlaybackAccessToken":{"value":"synthetic-value","signature":"synthetic-signature"}}}""")
                            }
                        }
                        request.path!!.startsWith("/api/channel/hls/") ->
                            MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/low.m3u8")
                        request.path == "/low.m3u8" -> {
                            playlistStarted.countDown()
                            check(releasePlaylist.await(5, TimeUnit.SECONDS))
                            MockResponse().setBody("#EXTM3U\n#EXTINF:2,\n/never-delivered.ts")
                        }
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
            val api = client(server)
            val watch = async { api.pollWatchStream(session(), channel(42)) { true } }
            try {
                assertTrue(withContext(Dispatchers.IO) { playlistStarted.await(3, TimeUnit.SECONDS) })
                assertFalse(api.checkChannelEligibility(session(), 42))
                releasePlaylist.countDown()
                assertFalse(withTimeout(2_000) { watch.await() })
                repeat(3) { assertFalse(api.pollWatchStream(session(), channel(42)) { true }) }
                assertEquals(4, requests.size)
                assertFalse(requests.any { it.first == "HEAD" })
                assertFalse(requests.any { it.second == "/channel42" })
            } finally {
                releasePlaylist.countDown()
                watch.cancelAndJoin()
            }
        }
    }

    @Test fun `unsupported identities do not issue browser queries and same account keeps confirmed restrictions`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = client(server)
            server.enqueue(MockResponse().setBody(response("""{"isPermanent":true}""").toString()))
            assertFalse(api.checkChannelEligibility(session(), 42))
            val tv = session().copy(browserContext = null, clientId = TwitchTvClientId)
            api.bindChannelAccount(tv.userId)
            assertFalse(api.checkChannelEligibility(tv, 42))
            assertTrue(api.checkChannelEligibility(tv, 43))
            assertTrue(api.checkChannelEligibility(tv.copy(clientId = null), 43))
            assertEquals(1, server.requestCount)
            api.bindChannelAccount("67890")
            assertTrue(api.checkChannelEligibility(tv.copy(userId = "67890"), 42))
        }
    }

    @Test fun `cancelled and replaced checks cannot install late bans`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = client(server)
            api.bindChannelAccount("12345")
            server.enqueue(MockResponse().setBody(response("""{"isPermanent":true}""").toString()).setBodyDelay(1, TimeUnit.SECONDS))
            val check = async { api.checkChannelEligibility(session(), 42) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            api.bindChannelAccount("67890")
            check.cancelAndJoin()
            assertTrue(api.isChannelAllowed("67890", 42))
            assertFalse(api.isChannelAllowed("12345", 42))
        }
    }

    @Test fun `stalled optional check has a deadline and failures do not retry on every call`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = client(server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertTrue(withTimeout(8_000) { api.checkChannelEligibility(session(), 42) })
            repeat(5) { assertTrue(api.checkChannelEligibility(session(), 42)) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `directory discovery filters permanent and temporary bans without changing survivor order`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val operation = payload["operationName"]!!.jsonPrimitive.content
                    return when (operation) {
                        "ChannelBanStatus" -> {
                            val id = payload["variables"]!!.jsonObject["channelID"]!!.jsonPrimitive.content
                            val status = when (id) { "42" -> """{"isPermanent":true}"""; "43" -> """{"isPermanent":false}"""; else -> "null" }
                            MockResponse().setBody(response(status, id).toString())
                        }
                        "DirectoryPage_Game" -> MockResponse().setBody("""{"data":{"game":{"streams":{"edges":[
                            {"node":{"id":"broadcast42","viewersCount":500,"broadcaster":{"id":"42","login":"first","displayName":"First"}}},
                            {"node":{"id":"broadcast43","viewersCount":400,"broadcaster":{"id":"43","login":"second","displayName":"Second"}}},
                            {"node":{"id":"broadcast44","viewersCount":300,"broadcaster":{"id":"44","login":"third","displayName":"Third"}}}
                        ]}}}}""")
                        else -> MockResponse().setBody("""{"data":{"game":{"slug":"game"}}}""")
                    }
                }
            }
            server.start()
            assertEquals(listOf(44L), client(server).fetchEligibleChannels(session(), Campaign("campaign", "Campaign", "Game")).map { it.id })
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun `ACL pagination and special category eligibility exclude bans before returning candidates`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return if (payload["operationName"]?.jsonPrimitive?.content == "ChannelBanStatus") {
                        val id = payload["variables"]!!.jsonObject["channelID"]!!.jsonPrimitive.content
                        MockResponse().setBody(response(if (id == "42") """{"isPermanent":true}""" else "null", id).toString())
                    } else {
                        val login = payload["variables"]!!.jsonObject["channel"]!!.jsonPrimitive.content
                        val id = if (login == "first") "42" else "43"
                        MockResponse().setBody("""{"data":{"user":{"id":"$id","login":"$login","stream":{"id":"broadcast"},"broadcastSettings":{"game":{"id":"999","displayName":"Another game"}}}}}""")
                    }
                }
            }
            server.start()
            val api = client(server)
            val campaign = Campaign("campaign", "Campaign", "Special Events", gameId = "509663", allowedChannels = listOf(channel(42).copy(login = "first"), channel(43).copy(login = "second")))
            assertEquals(listOf(43L), api.fetchEligibleChannels(session(), campaign, limit = 1).map { it.id })
            assertFalse(api.fetchCampaignChannel(session(), "first", campaign).dropsEnabled)
            assertEquals(5, server.requestCount)
        }
    }

    private fun session(): StoredTwitchSession {
        val context = BrowserSessionContext.parse(buildJsonObject {
            put("version", 1); put("captured_at", now.epochSecond); put("expires_at", now.plusSeconds(3600).epochSecond)
            put("user_agent", "FixtureBrowser/1.0")
            putJsonObject("headers") {
                put("client-id", TwitchWebClientId); put("authorization", "OAuth fixture-token")
                put("client-integrity", "fixture-proof"); put("x-device-id", "fixture-device")
            }
        })
        return StoredTwitchSession(context.accessToken, "12345", context.deviceId, now, context)
    }
    private fun channel(id: Long) = Channel(id, "channel$id", online = true, dropsEnabled = true, broadcastId = "broadcast")
    private fun client(server: MockWebServer, clock: () -> Instant = { now }) = TwitchApiClient(OkHttpClient(),
        gqlEndpoint = server.url("/gql").toString(), twitchWebBaseUrl = server.url("/").toString(),
        oauthBaseUrl = server.url("/").toString(), channelRestrictionTime = clock)
}
