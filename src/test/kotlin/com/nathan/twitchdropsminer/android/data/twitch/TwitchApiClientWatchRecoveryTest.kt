package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TwitchApiClientWatchRecoveryTest {
    @Test
    fun `rejected cached watch endpoint is refreshed and retried once`() {
        val server = MockWebServer()
        server.start()
        try {
            val oldSpadeUrl = server.url("/spade-old").toString()
            val newSpadeUrl = server.url("/spade-new").toString()
            server.enqueue(htmlResponse("""{"beacon_url":"$oldSpadeUrl"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(410))
            server.enqueue(htmlResponse("""{"beacon_url":"$newSpadeUrl"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = TwitchApiClient(
                okHttpClient = OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
                twitchWebBaseUrl = server.url("/").toString(),
            )

            assertTrue(runBlocking { client.sendWatchMinute(session(), channel()) })
            assertTrue(runBlocking { client.sendWatchMinute(session(), channel()) })

            assertEquals(
                listOf(
                    "/ExampleChannel",
                    "/spade-old",
                    "/spade-old",
                    "/ExampleChannel",
                    "/spade-new",
                ),
                List(5) { server.takeRequest().path },
            )
        } finally {
            server.shutdown()
        }
    }

    private fun session() = StoredTwitchSession(
        accessToken = "token",
        userId = "12345",
        deviceId = "device-1",
        savedAt = Instant.parse("2026-08-12T00:00:00Z"),
    )

    private fun channel() = Channel(
        id = 67890,
        name = "ExampleChannel",
        game = "Example Game",
        online = true,
        dropsEnabled = true,
        broadcastId = "broadcast-1",
    )

    private fun htmlResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)
}
