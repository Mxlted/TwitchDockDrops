package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TwitchApiClientSecurityTest {
    @Test
    fun `untrusted derived watch URL is rejected without receiving authorization`() {
        val twitch = MockWebServer()
        val untrusted = MockWebServer()
        twitch.start()
        untrusted.start()
        try {
            twitch.enqueue(html("""{"beacon_url":"${untrusted.url("/collect")}"}"""))
            val client = client(twitch)

            assertFalse(runBlocking { client.sendWatchMinute(session(), channel()) })

            assertEquals(0, untrusted.requestCount)
            assertNull(twitch.takeRequest().getHeader("Authorization"))
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
            assertNull(server.takeRequest().getHeader("Authorization"))

            server.enqueue(html("""{"beacon_url":"${server.url("/spade")}"}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            assertFalse(runBlocking { client.sendWatchMinute(session(), channel().copy(id = 2)) })
            val configurationRequest = server.takeRequest()
            val spadeRequest = server.takeRequest()
            assertNull(configurationRequest.getHeader("Authorization"))
            assertEquals("OAuth access-token-secret", spadeRequest.getHeader("Authorization"))
            assertEquals("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", spadeRequest.getHeader("Client-Id"))
            assertEquals("device-secret", spadeRequest.getHeader("Client-Session-Id"))
            assertEquals("device-secret", spadeRequest.getHeader("X-Device-Id"))
        } finally {
            server.shutdown()
        }
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
