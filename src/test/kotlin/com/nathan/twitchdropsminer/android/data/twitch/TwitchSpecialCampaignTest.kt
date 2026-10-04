package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TwitchSpecialCampaignTest {
    @Test
    fun `special category IDs survive mapping independently of localized display names`() {
        for (id in listOf("509663", "509672")) {
            val campaign = TwitchCampaignMapper.mapCampaign(Json.parseToJsonElement(
                """{"id":"campaign","name":"Event","game":{"id":"$id","displayName":"Localized category"},"timeBasedDrops":[]}""",
            ).jsonObject, emptyMap()).campaign
            assertEquals(id, assertNotNull(campaign).gameId)
        }
    }

    @Test
    fun `special campaign participants may stream another category while retaining telemetry attribution`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = client(server)
            for (id in listOf("509663", "509672")) {
                server.enqueue(liveChannel())
                val channels = client.fetchEligibleChannels(session, campaign(id))
                val channel = channels.single()
                assertTrue(channel.dropsEnabled)
                assertEquals("Actual game", channel.game)
                assertEquals("123", channel.gameId)
                assertEquals("broadcast", channel.broadcastId)
                assertEquals(42L, channel.id)
            }
        }
    }

    @Test
    fun `ordinary ACL campaigns still require their own category`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(liveChannel())
            assertTrue(client(server).fetchEligibleChannels(session, campaign("ordinary")).isEmpty())
        }
    }

    @Test
    fun `special exception requires matching ACL identity and a live channel`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = client(server)
            for (candidate in listOf(
                campaign("509663").copy(allowedChannels = emptyList()),
                campaign("509663").copy(allowedChannels = listOf(Channel(99, "participant"))),
                campaign("509663").copy(allowedChannels = listOf(Channel(42, "other"))),
                campaign(null),
            )) {
                server.enqueue(liveChannel())
                assertFalse(client.fetchCampaignChannel(session, "participant", candidate).dropsEnabled)
            }
            server.enqueue(MockResponse().setBody(
                """{"data":{"user":{"id":"42","login":"participant","stream":null}}}""",
            ))
            val offline = client.fetchCampaignChannel(session, "participant", campaign("509663"))
            assertFalse(offline.online)
            assertFalse(offline.dropsEnabled)
        }
    }

    private val session = StoredTwitchSession("token", "12345", "device", Instant.EPOCH)

    private fun campaign(gameId: String?) = Campaign(
        id = "campaign", name = "Event", gameName = "Special Events", gameId = gameId,
        allowedChannels = listOf(Channel(42, "participant")),
    )

    private fun client(server: MockWebServer) = TwitchApiClient(
        OkHttpClient(), gqlEndpoint = server.url("/gql").toString(),
        twitchWebBaseUrl = server.url("/").toString(), oauthBaseUrl = server.url("/").toString(),
    )

    private fun liveChannel() = MockResponse().setBody(
        """{"data":{"user":{"id":"42","login":"participant","displayName":"Participant","stream":{"id":"broadcast","viewersCount":100},"broadcastSettings":{"game":{"id":"123","displayName":"Actual game"}}}}}""",
    )
}
