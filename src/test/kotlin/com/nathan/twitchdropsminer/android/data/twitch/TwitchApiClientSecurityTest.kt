package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TwitchApiClientSecurityTest {
    @Test
    fun `validation forbidden and unavailable responses do not expire saved credentials`() {
        MockWebServer().use { server ->
            server.start()
            for (code in listOf(403, 429, 503)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val error = assertFailsWith<TwitchApiException> {
                    runBlocking { client(server).validateAccessToken("secret") }
                }
                assertEquals(TwitchApiErrorType.Http, error.type)
            }
        }
    }

    @Test
    fun `validation rejects foreign clients and malformed identities without expiring credentials`() {
        MockWebServer().use { server ->
            server.start()
            for ((clientId, userId) in listOf(
                "ue6666qo983tsx6so1t0vnawi233wa" to "123",
                "kimne78kx3ncx6brgo4mv6wki5h1ko" to "123",
                TwitchClientId to "0", TwitchClientId to "-1", TwitchClientId to "secret-value",
            )) {
                server.enqueue(MockResponse().setBody("""{"client_id":"$clientId","user_id":"$userId"}"""))
                val error = assertFailsWith<TwitchApiException> {
                    runBlocking { client(server).validateAccessToken("secret") }
                }
                assertEquals(TwitchApiErrorType.UnexpectedResponse, error.type)
                assertFalse(error.message.orEmpty().contains("secret-value"))
            }
            server.enqueue(MockResponse().setBody("""{"client_id":"$TwitchClientId","user_id":"123"}"""))
            assertEquals(ValidatedToken("123", TwitchClientId),
                runBlocking { client(server).validateAccessToken("secret") })
        }
    }

    @Test
    fun `HTTP 200 GraphQL auth rejection validates OAuth and never replays a claim`() {
        MockWebServer().use { server ->
            server.start()
            for (message in listOf("invalid oauth token", "failed integrity check")) {
                for (invalid in listOf(false, true)) {
                    val before = server.requestCount
                    server.enqueue(MockResponse().setBody("""{"errors":[{"message":"$message"}]}"""))
                    server.enqueue(if (invalid) MockResponse().setResponseCode(401) else
                        MockResponse().setBody("""{"client_id":"$TwitchClientId","user_id":"12345"}"""))
                    val error = assertFailsWith<TwitchApiException> {
                        runBlocking { client(server).claimDrop(session(), "claim-id") }
                    }
                    assertEquals(if (invalid) TwitchApiErrorType.InvalidToken else TwitchApiErrorType.Http, error.type)
                    assertEquals(before + 2, server.requestCount)
                    assertEquals("/gql", server.takeRequest().path)
                    assertEquals("/oauth2/validate", server.takeRequest().path)
                }
            }
        }
    }

    @Test
    fun `partial GraphQL data is not treated as a preexecution auth rejection`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(
                """{"data":{"user":{"id":"12","stream":null}},"errors":[{"message":"invalid oauth token","path":["user"]}]}""",
            ))
            assertFalse(runBlocking { client(server).fetchChannel(session(), "channel", "Game") }.online)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `untrusted derived watch URL is rejected without receiving a request`() {
        val twitch = MockWebServer()
        val untrusted = MockWebServer()
        twitch.start()
        untrusted.start()
        try {
            twitch.enqueue(html("""{"beacon_url":"${untrusted.url("/collect")}"}"""))
            val client = client(twitch)

            assertFalse(runBlocking { client.sendWatchMinute(session(), channel()) })

            assertEquals(0, untrusted.requestCount)
            assertEquals("OAuth access-token-secret", twitch.takeRequest().getHeader("Authorization"))
        } finally {
            twitch.shutdown()
            untrusted.shutdown()
        }
    }

    @Test
    fun `trusted spade event is authenticated without classifying rejection as an invalid token`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = client(server)
            server.enqueue(MockResponse().setResponseCode(403))
            val configurationError = assertFailsWith<TwitchApiException> {
                runBlocking { client.sendWatchMinute(session(), channel()) }
            }
            assertEquals(TwitchApiErrorType.Http, configurationError.type)
            assertEquals("OAuth access-token-secret", server.takeRequest().getHeader("Authorization"))

            server.enqueue(html("""{"beacon_url":"${server.url("/spade")}"}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            assertFalse(runBlocking { client.sendWatchMinute(session(), channel().copy(id = 2)) })
            val configurationRequest = server.takeRequest()
            val spadeRequest = server.takeRequest()
            assertEquals("OAuth access-token-secret", configurationRequest.getHeader("Authorization"))
            assertEquals("OAuth access-token-secret", spadeRequest.getHeader("Authorization"))
            assertEquals("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", spadeRequest.getHeader("Client-Id"))
            assertEquals("device-secret", spadeRequest.getHeader("Client-Session-Id"))
            assertEquals("device-secret", spadeRequest.getHeader("X-Device-Id"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `current Twitch settings and watch collector URLs are narrowly trusted`() {
        assertTrue(
            isTrustedTwitchSettingsUrl(
                "https://assets.twitch.tv/config/settings.01ea5b32d773303bd1d77952a1ff9b91.js",
            ),
        )
        assertTrue(
            isTrustedTwitchSettingsUrl(
                "https://static.twitchcdn.net/config/settings.01ea5b32d773303bd1d77952a1ff9b91.js",
            ),
        )
        assertTrue(isTrustedTwitchWatchEventUrl("https://beacon.twitch.tv/track"))
        assertTrue(isTrustedTwitchWatchEventUrl("https://spade.twitch.tv/some/event/path"))
        assertFalse(isTrustedTwitchSettingsUrl("https://assets.twitch.tv/other.js"))
        assertFalse(isTrustedTwitchSettingsUrl("https://example.com/config/settings.01ea5b32d773303bd1d77952a1ff9b91.js"))
        assertFalse(isTrustedTwitchWatchEventUrl("https://beacon.twitch.tv/not-track"))
        assertFalse(isTrustedTwitchWatchEventUrl("http://beacon.twitch.tv/track"))
    }

    @Test
    fun `authoritative validation rejection is an invalid token`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client(server).validateAccessToken("access-token-secret") }
            }
            assertEquals(TwitchApiErrorType.InvalidToken, error.type)
            assertFalse(error.message.orEmpty().contains("access-token-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `graphql rejection expires session only after validation confirms invalid token`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))

            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client(server).fetchChannel(session(), "channel", "Game") }
            }

            assertEquals(TwitchApiErrorType.InvalidToken, error.type)
            assertEquals("/gql", server.takeRequest().path)
            assertEquals("/oauth2/validate", server.takeRequest().path)
            assertFalse(error.message.orEmpty().contains("access-token-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `graphql rejection remains transient when validation confirms session`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"user_id":"1","client_id":"kd1unb4b3q4t58fwlpcbzcbnm76a8fp"}""",
                ),
            )

            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client(server).fetchChannel(session(), "channel", "Game") }
            }

            assertEquals(TwitchApiErrorType.Http, error.type)
            assertEquals("/gql", server.takeRequest().path)
            assertEquals("/oauth2/validate", server.takeRequest().path)
            assertFalse(error.message.orEmpty().contains("access-token-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `graphql rejection remains transient when validation cannot confirm it`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(503))

            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client(server).fetchChannel(session(), "channel", "Game") }
            }

            assertEquals(TwitchApiErrorType.Http, error.type)
            assertTrue(error.message.orEmpty().contains("could not be confirmed"))
            assertEquals("/gql", server.takeRequest().path)
            assertEquals("/oauth2/validate", server.takeRequest().path)
            assertFalse(error.message.orEmpty().contains("access-token-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty graphql error array allows safe data`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"user":{"id":"12","displayName":"offline","stream":null}},"errors":[]}""",
                ),
            )
            val channel = runBlocking { client(server).fetchChannel(session(), "offline", "Game") }
            assertFalse(channel.online)
            assertEquals(12L, channel.id)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `graphql response size is bounded`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)))
            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client(server).fetchChannel(session(), "large", null) }
            }
            assertEquals(TwitchApiErrorType.UnexpectedResponse, error.type)
        } finally {
            server.shutdown()
        }
    }

    private fun client(server: MockWebServer): TwitchApiClient = TwitchApiClient(
        OkHttpClient(),
        gqlEndpoint = server.url("/gql").toString(),
        twitchWebBaseUrl = server.url("/").toString(),
        oauthBaseUrl = server.url("/").toString(),
    )

    private fun session() = StoredTwitchSession(
        accessToken = "access-token-secret",
        userId = "12345",
        deviceId = "device-secret",
        savedAt = Instant.EPOCH,
    )

    private fun channel() = Channel(
        id = 1,
        name = "ExampleChannel",
        game = "Game",
        online = true,
        dropsEnabled = true,
        broadcastId = "broadcast",
    )

    private fun html(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/html")
        .setBody(body)
}
