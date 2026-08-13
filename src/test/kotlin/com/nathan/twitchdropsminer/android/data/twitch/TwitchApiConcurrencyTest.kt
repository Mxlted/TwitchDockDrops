package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class TwitchApiConcurrencyTest {
    @Test
    fun `slow allowed-channel lookup does not block later work behind a fixed batch`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val response = MockResponse().setBody(
                    """{"data":{"user":{"id":"123","displayName":"channel","stream":{"id":"broadcast","viewersCount":1},"broadcastSettings":{"game":{"id":"game","displayName":"Game"},"title":"Live"}}}}""",
                )
                return if (body.contains("slow")) {
                    response.setBodyDelay(900, TimeUnit.MILLISECONDS)
                } else {
                    response
                }
            }
        }
        server.start()
        try {
            val client = TwitchApiClient(
                OkHttpClient(),
                gqlEndpoint = server.url("/gql").toString(),
                twitchWebBaseUrl = server.url("/").toString(),
                oauthBaseUrl = server.url("/").toString(),
            )
            val campaign = Campaign(
                id = "campaign",
                name = "Campaign",
                gameName = "Game",
                linked = true,
                active = true,
                allowedChannels = listOf("slow", "two", "three", "four", "five", "six", "seven", "eight")
                    .mapIndexed { index, name -> Channel((index + 1).toLong(), name, aclBased = true) },
            )
            val pending = async { client.fetchEligibleChannels(session(), campaign) }

            withTimeout(700) {
                while (server.requestCount < 8) delay(10)
            }
            assertTrue(server.requestCount >= 8)
            assertEquals(8, pending.await().size)
        } finally {
            server.shutdown()
        }
    }

    private fun session() = StoredTwitchSession("token", "123", "device", Instant.EPOCH)
}
