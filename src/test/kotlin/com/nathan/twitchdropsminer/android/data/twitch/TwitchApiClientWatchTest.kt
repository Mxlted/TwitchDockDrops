package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class TwitchApiClientWatchTest {
    @Test
    fun `channel lookup preserves canonical login separately from display name`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"data":{"user":{"id":"67890","login":"example_channel","displayName":"Example Display","stream":{"id":"24680","viewersCount":100},"broadcastSettings":{"game":{"id":"13579","displayName":"Example Game"},"title":"Example Stream"}}}}""",
                ),
            )

            val channel = runBlocking {
                client(server).fetchChannel(session(), "lookup_input", "Example Game")
            }

            assertEquals("Example Display", channel.name)
            assertEquals("example_channel", channel.login)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `watch minute posts fresh complete attribution payload directly to spade`() {
        val server = MockWebServer()
        server.start()
        try {
            val spadeUrl = server.url("/spade").toString()
            server.enqueue(htmlResponse("""{"beacon_url":"$spadeUrl"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val times = ArrayDeque(
                listOf(
                    Instant.parse("2026-08-13T12:34:56.123Z"),
                    Instant.parse("2026-08-13T12:35:55.456Z"),
                ),
            )
            val client = client(server) { times.removeFirst() }

            assertTrue(runBlocking { client.sendWatchMinute(session(), channel()) })
            assertTrue(runBlocking { client.sendWatchMinute(session(), channel()) })

            val configurationRequest = server.takeRequest()
            val firstSpadeRequest = server.takeRequest()
            val secondSpadeRequest = server.takeRequest()
            assertEquals("/example_channel", configurationRequest.path)
            assertEquals("POST", firstSpadeRequest.method)
            assertEquals("/spade", firstSpadeRequest.path)
            assertEquals("OAuth access-token-secret", firstSpadeRequest.getHeader("Authorization"))
            assertEquals("kd1unb4b3q4t58fwlpcbzcbnm76a8fp", firstSpadeRequest.getHeader("Client-Id"))
            assertEquals("device-secret", firstSpadeRequest.getHeader("X-Device-Id"))
            assertTrue(
                firstSpadeRequest.getHeader("Content-Type").orEmpty()
                    .startsWith("application/x-www-form-urlencoded"),
            )
            val firstProperties = firstSpadeRequest.watchProperties()
            val secondProperties = secondSpadeRequest.watchProperties()
            assertEquals("24680", firstProperties["broadcast_id"]?.jsonPrimitive?.content)
            assertEquals("67890", firstProperties["channel_id"]?.jsonPrimitive?.content)
            assertEquals("example_channel", firstProperties["channel"]?.jsonPrimitive?.content)
            assertEquals("Example Game", firstProperties["game"]?.jsonPrimitive?.content)
            assertEquals("13579", firstProperties["game_id"]?.jsonPrimitive?.content)
            assertEquals("channel", firstProperties["location"]?.jsonPrimitive?.content)
            assertEquals("site", firstProperties["player"]?.jsonPrimitive?.content)
            assertEquals(1, firstProperties["minutes_logged"]?.jsonPrimitive?.int)
            assertEquals(12345, firstProperties["user_id"]?.jsonPrimitive?.int)
            assertFalse(firstProperties["user_id"]!!.jsonPrimitive.isString)
            assertTrue(firstProperties["is_live"]!!.jsonPrimitive.boolean)
            assertTrue(firstProperties["live"]!!.jsonPrimitive.boolean)
            assertTrue(firstProperties["logged_in"]!!.jsonPrimitive.boolean)
            assertFalse(firstProperties["hidden"]!!.jsonPrimitive.boolean)
            assertFalse(firstProperties["muted"]!!.jsonPrimitive.boolean)
            val firstTime = firstProperties["client_time"]!!.jsonPrimitive.content
            val secondTime = secondProperties["client_time"]!!.jsonPrimitive.content
            assertEquals("2026-08-13T12:34:56.123Z", firstTime)
            assertEquals("2026-08-13T12:35:55.456Z", secondTime)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `watch minute returns false for non-204 spade response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(htmlResponse("""{"beacon_url":"${server.url("/spade")}"}"""))
            server.enqueue(MockResponse().setResponseCode(400))

            val client = client(server)
            assertFalse(runBlocking { client.sendWatchMinute(session(), channel()) })
            assertEquals(400, client.watchRejectionStatus())
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `watch minute returns false when stream metadata or spade URL is missing`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = client(server)
            assertFalse(
                runBlocking {
                    client.sendWatchMinute(session(), channel().copy(broadcastId = null))
                },
            )
            assertFalse(
                runBlocking {
                    client.sendWatchMinute(session().copy(userId = "not-numeric"), channel())
                },
            )
            assertEquals(0, server.requestCount)

            server.enqueue(htmlResponse("<html><body>No watch configuration</body></html>"))
            assertFalse(runBlocking { client.sendWatchMinute(session(), channel()) })
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun RecordedRequest.watchProperties() =
        Json.parseToJsonElement(
            String(
                Base64.getDecoder().decode(
                    URLDecoder.decode(
                        body.readUtf8().substringAfter("data="),
                        StandardCharsets.UTF_8.name(),
                    ),
                ),
                Charsets.UTF_8,
            ),
        ).jsonArray.single().jsonObject.also { event ->
            assertEquals("minute-watched", event["event"]?.jsonPrimitive?.content)
        }["properties"]!!.jsonObject

    private fun client(
        server: MockWebServer,
        watchEventTime: () -> Instant = Instant::now,
    ) = TwitchApiClient(
        okHttpClient = OkHttpClient(),
        gqlEndpoint = server.url("/gql").toString(),
        twitchWebBaseUrl = server.url("/").toString(),
        oauthBaseUrl = server.url("/").toString(),
        watchEventTime = watchEventTime,
    )

    private fun session() = StoredTwitchSession(
        accessToken = "access-token-secret",
        userId = "12345",
        deviceId = "device-secret",
        savedAt = Instant.parse("2026-08-13T00:00:00Z"),
    )

    private fun channel() = Channel(
        id = 67890,
        name = "Example Display",
        game = "Example Game",
        online = true,
        dropsEnabled = true,
        broadcastId = "24680",
        gameId = "13579",
        login = "example_channel",
    )

    private fun htmlResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)
}
