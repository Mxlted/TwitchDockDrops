package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TwitchCampaignMapperTest {
    @Test
    fun `malformed campaign and drop records produce bounded diagnostics`() {
        val malformedCampaign = parse("""{"name":"No id","game":{"displayName":"Game"}}""")
        val failed = TwitchCampaignMapper.mapCampaign(malformedCampaign, emptyMap())
        assertNull(failed.campaign)
        assertTrue(failed.diagnostics.single().contains("id"))

        val partial = TwitchCampaignMapper.mapCampaign(
            parse(
                """{
                  "id":"campaign-1","name":"Campaign","status":"ACTIVE",
                  "game":{"displayName":"Game"},"self":{"isAccountConnected":true},
                  "timeBasedDrops":[
                    {"id":"drop-1","name":"Good","requiredMinutesWatched":60,"self":{"currentMinutesWatched":5},"benefitEdges":[]},
                    {"id":"drop-2","name":"Bad","requiredMinutesWatched":"sixty","benefitEdges":[]}
                  ]
                }""",
            ),
            emptyMap(),
        )

        assertEquals(listOf("drop-1"), assertNotNull(partial.campaign).drops.map { it.id })
        assertTrue(partial.diagnostics.isNotEmpty())
        assertTrue(partial.diagnostics.joinToString().length < 1_024)
    }

    @Test
    fun `nonempty inventory with no safely parsed campaigns is not reported as empty`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(inventoryResponse("""[{"id":"broken","name":"Broken","timeBasedDrops":[]}]"""))
            server.enqueue(campaignsResponse("[]"))
            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client(server).fetchCampaignInventory(session()) }
            }

            assertEquals(TwitchApiErrorType.UnexpectedResponse, error.type)
            assertTrue(error.message.orEmpty().contains("none could be parsed"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `safe partial inventory is explicitly identified`() {
        val server = MockWebServer()
        server.start()
        try {
            val records = """[
              {"id":"good","name":"Good","status":"ACTIVE","game":{"displayName":"Game"},"self":{"isAccountConnected":true},"timeBasedDrops":[{"id":"drop","name":"Drop","requiredMinutesWatched":60,"self":{"currentMinutesWatched":0},"benefitEdges":[]}]},
              {"id":"broken","name":"Broken","timeBasedDrops":[]}
            ]"""
            server.enqueue(inventoryResponse(records))
            server.enqueue(campaignsResponse("[]"))

            val result = runBlocking { client(server).fetchCampaignInventory(session()) }

            assertEquals(listOf("good"), result.campaigns.map { it.id })
            assertTrue(result.isPartial)
            assertEquals(2, result.sourceRecordCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `campaign summary with drops does not trigger unnecessary detail lookup`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(inventoryResponse("[]"))
            val summary = """[{"id":"summary","name":"Summary","status":"ACTIVE","game":{"displayName":"Game"},"self":{"isAccountConnected":true},"timeBasedDrops":[{"id":"drop","name":"Drop","requiredMinutesWatched":30,"self":{"currentMinutesWatched":0},"benefitEdges":[]}]}]"""
            server.enqueue(campaignsResponse(summary))

            val inventory = runBlocking { client(server).fetchCampaignInventory(session()) }

            assertEquals(listOf("summary"), inventory.campaigns.map { it.id })
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun client(server: MockWebServer) = TwitchApiClient(
        OkHttpClient(),
        gqlEndpoint = server.url("/gql").toString(),
        twitchWebBaseUrl = server.url("/").toString(),
        oauthBaseUrl = server.url("/").toString(),
    )

    private fun session() = StoredTwitchSession("token", "123", "device", Instant.EPOCH)

    private fun parse(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject

    private fun inventoryResponse(records: String) = MockResponse().setBody(
        """{"data":{"currentUser":{"inventory":{"dropCampaignsInProgress":$records,"gameEventDrops":[]}}}}""",
    )

    private fun campaignsResponse(records: String) = MockResponse().setBody(
        """{"data":{"currentUser":{"dropCampaigns":$records}}}""",
    )
}
