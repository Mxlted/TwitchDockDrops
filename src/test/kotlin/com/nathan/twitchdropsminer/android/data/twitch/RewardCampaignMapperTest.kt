package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class RewardCampaignMapperTest {
    private val record = Json.parseToJsonElement("""{
        "id":"reward", "name":"Reward event", "brand":"Brand", "game":null, "status":"UNKNOWN",
        "startsAt":"2026-01-01T00:00:00Z", "endsAt":"2027-01-01T00:00:00Z",
        "summary":"Participate on Twitch", "rewardValue":"must-not-leak",
        "rewardGroups":[{"rewards":[{"name":"Badge"},{"name":"Emote"}]}],
        "rewards":[{"name":"Badge"}]
    }""")

    @Test
    fun `sitewide rewards with unknown status retain dates and deduplicated group names`() {
        val result = mapRewardCampaigns(JsonArray(listOf(record)))
        assertTrue(result.available)
        val campaign = result.campaigns.single()
        assertEquals(null, campaign.gameName)
        assertEquals(listOf("Badge", "Emote"), campaign.rewardNames)
        assertFalse(campaign.toString().contains("must-not-leak"))
    }

    @Test
    fun `empty missing and partial rewards are distinguished`() {
        assertTrue(mapRewardCampaigns(JsonArray(emptyList())).available)
        assertFalse(mapRewardCampaigns(null).available)
        assertFalse(mapRewardCampaigns(JsonNull).available)
        val partial = mapRewardCampaigns(JsonArray(listOf(record, JsonNull)))
        assertFalse(partial.available)
        assertEquals(1, partial.campaigns.size)
        val invalidDate = Json.parseToJsonElement(record.toString().replace("2027-01-01", "2025-01-01"))
        assertFalse(mapRewardCampaigns(JsonArray(listOf(invalidDate))).available)
        assertTrue(mapRewardCampaigns(JsonArray(listOf(invalidDate))).campaigns.isEmpty())
    }

    @Test
    fun `rewards are requested alongside drops but never converted to mining campaigns`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"inventory":{"dropCampaignsInProgress":[]}}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"dropCampaigns":[]},"rewardCampaignsAvailableToUser":[$record]}}"""))
            server.start()
            val client = TwitchApiClient(OkHttpClient(), gqlEndpoint = server.url("/gql").toString(),
                twitchWebBaseUrl = server.url("/").toString(), oauthBaseUrl = server.url("/").toString())
            val inventory = client.fetchCampaignInventory(StoredTwitchSession("fixture-token", "fixture-user", "fixture-device", Instant.EPOCH))
            assertTrue(inventory.campaigns.isEmpty())
            assertTrue(inventory.rewardCampaignsAvailable)
            assertEquals("reward", inventory.rewardCampaigns.single().id)
            server.takeRequest()
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("true", request.getValue("variables").jsonObject.getValue("fetchRewardCampaigns").toString())
        }
    }
}
