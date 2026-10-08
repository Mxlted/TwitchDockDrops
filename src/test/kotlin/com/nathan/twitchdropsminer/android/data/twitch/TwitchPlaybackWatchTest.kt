package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.model.TwitchWebClientId
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class TwitchPlaybackWatchTest {
    @Test fun `blocked progress query is bounded so stream polling can resume`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val error = assertFailsWith<TwitchApiException> {
                withTimeout(7_000) { client(server).currentDrop(session, channel.id) }
            }
            assertEquals(TwitchApiErrorType.Network, error.type)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `browser watch requires HLS and throttles auxiliary telemetry even when it fails`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var tick = 0L
            val api = client(server) { tick }
            server.enqueue(token())
            server.enqueue(master())
            server.enqueue(media())
            server.enqueue(MockResponse())
            server.enqueue(MockResponse().setBody("""{"beacon_url":"${server.url("/spade")}"}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(api.pollWatchStream(session, channel) { true })
            val gql = server.takeRequest()
            assertEquals("OAuth synthetic-token", gql.getHeader("Authorization"))
            assertEquals("synthetic-proof", gql.getHeader("Client-Integrity"))
            assertEquals(TwitchWebClientId, gql.getHeader("Client-Id"))
            val operation = Json.parseToJsonElement(gql.body.readUtf8()).jsonObject
            assertEquals("PlaybackAccessToken", operation["operationName"]!!.jsonPrimitive.content)
            assertEquals("synthetic", operation["variables"]!!.jsonObject["login"]!!.jsonPrimitive.content)
            assertEquals("ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9",
                operation["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject["sha256Hash"]!!.jsonPrimitive.content)
            val usher = server.takeRequest()
            assertEquals("synthetic value&?", usher.requestUrl!!.queryParameter("token"))
            val playlist = server.takeRequest()
            val segment = server.takeRequest()
            assertEquals("HEAD", segment.method)
            for (request in listOf(usher, playlist, segment)) {
                for (header in listOf("Authorization", "Client-Id", "Client-Integrity", "X-Device-Id", "Cookie"))
                    assertNull(request.getHeader(header), header)
            }
            server.takeRequest(); server.takeRequest()
            tick = 10_000_000_000
            server.enqueue(media())
            assertTrue(api.pollWatchStream(session, channel) { true })
            assertEquals("/low/index.m3u8", server.takeRequest().path)
            assertEquals(7, server.requestCount)
            tick = 59_000_000_000
            server.enqueue(media())
            server.enqueue(MockResponse().setBody("""{"beacon_url":"${server.url("/spade")}"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            assertTrue(api.pollWatchStream(session, channel) { true })
            assertEquals(10, server.requestCount)
        }
    }

    @Test fun `HLS failure does not become success through accepted telemetry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(token()); server.enqueue(master()); server.enqueue(MockResponse().setResponseCode(403))
            assertFalse(client(server).pollWatchStream(session, channel) { true })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `expired browser proof prevents watch traffic and preserves credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val expired = session.copy(browserContext = browserContext(Instant.now().minusSeconds(60)))
            val error = assertFailsWith<TwitchApiException> { client(server).pollWatchStream(expired, channel) { true } }
            assertEquals(TwitchApiErrorType.Http, error.type)
            assertTrue(error.message!!.contains("browser service"))
            assertFalse(error.message!!.contains("synthetic-token"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `playback authentication rejection expires only an authoritatively invalid OAuth token`() = runBlocking {
        for (invalid in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(403))
                server.enqueue(if (invalid) MockResponse().setResponseCode(401) else MockResponse().setBody(
                    """{"client_id":"$TwitchWebClientId","user_id":"12345"}"""))
                val error = assertFailsWith<TwitchApiException> { client(server).pollWatchStream(session, channel) { true } }
                assertEquals(if (invalid) TwitchApiErrorType.InvalidToken else TwitchApiErrorType.Http, error.type)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun `malformed playback access cannot expose upstream text or signed values`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"errors":[{"message":"synthetic-private-playback-value"}]}"""))
            val error = assertFailsWith<TwitchApiException> { client(server).pollWatchStream(session, channel) { true } }
            assertFalse(error.toString().contains("synthetic-private-playback-value"))
            assertNull(error.cause)
            assertEquals(1, server.requestCount)
        }
    }

    private fun client(server: MockWebServer, clock: () -> Long = System::nanoTime) = TwitchApiClient(
        OkHttpClient(), gqlEndpoint = server.url("/gql").toString(), twitchWebBaseUrl = server.url("/").toString(),
        oauthBaseUrl = server.url("/").toString(), watchMonotonicNanos = clock,
    )
    private fun token() = MockResponse().setBody("""{"data":{"streamPlaybackAccessToken":{"value":"synthetic value&?","signature":"synthetic-signature"}}}""")
    private fun master() = MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n/low/index.m3u8\n")
    private fun media() = MockResponse().setBody("#EXTM3U\n#EXTINF:2.0,\nsegment.ts\n")
    private val session = StoredTwitchSession("synthetic-token", "12345", "synthetic-device", Instant.EPOCH,
        browserContext(Instant.now().plusSeconds(3600)))
    private fun browserContext(expiry: Instant) = BrowserSessionContext.parse(buildJsonObject {
        put("version", 1)
        put("captured_at", expiry.minusSeconds(3600).epochSecond)
        put("expires_at", expiry.epochSecond)
        put("user_agent", "Synthetic/1.0")
        putJsonObject("headers") {
            put("authorization", "OAuth synthetic-token")
            put("client-id", TwitchWebClientId)
            put("client-integrity", "synthetic-proof")
            put("x-device-id", "synthetic-device")
        }
    })
    private val channel = Channel(67890, "Synthetic", online = true, dropsEnabled = true, broadcastId = "broadcast", login = "synthetic")
}
