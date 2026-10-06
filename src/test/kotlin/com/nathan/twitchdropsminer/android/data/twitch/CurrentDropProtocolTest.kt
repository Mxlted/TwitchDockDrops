package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CurrentDropProtocolTest {
    private val sentinel = """{"__typename":"DropCurrentSession","channel":null,"dropID":"","currentMinutesWatched":0,"game":null,"requiredMinutesWatched":0}"""
    private fun envelope(value: String) = """{"data":{"currentUser":{"dropCurrentSession":$value}}}"""
    private fun observe(body: String): CurrentDropProgress? = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(body))
            val result = TwitchApiClient(OkHttpClient(), gqlEndpoint = server.url("/gql").toString())
                .currentDrop(StoredTwitchSession("synthetic", "42", "device", Instant.EPOCH, clientId = TwitchTvClientId), 10)
            val request = server.takeRequest()
            val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("DropCurrentSessionContext", payload["operationName"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement("""{"channelID":"10","channelLogin":""}"""), payload["variables"])
            assertEquals(TwitchTvClientId, request.getHeader("Client-Id"))
            result
        }
    }
    @Test fun `explicit absence is accepted`() {
        assertNull(observe(envelope("null")))
        assertNull(observe(envelope(sentinel)))
    }
    @Test fun `integral progress and decimal digit strings are accepted`() {
        for (minutes in listOf("0", "3", "\"3\"", Int.MAX_VALUE.toString())) {
            val result = observe(envelope("""{"channel":{"id":"10"},"dropID":"reward","currentMinutesWatched":$minutes}"""))!!
            assertEquals(minutes.trim('"').toInt(), result.currentMinutes)
        }
    }
    @Test fun `malformed sessions and envelopes never become absence`() {
        for (body in listOf("{}", """{"data":null}""", """{"data":{"currentUser":null}}""",
            """{"data":{"currentUser":{}}}""", """{"errors":{},"data":{"currentUser":{"dropCurrentSession":null}}}""",
            envelope("{}"), envelope("[]"), envelope("false"), envelope("\"session\""))) {
            assertFailsWith<TwitchApiException>(body) { observe(body) }
        }
        val record = Json.parseToJsonElement(sentinel).jsonObject
        for (key in listOf("channel", "dropID", "currentMinutesWatched", "game", "requiredMinutesWatched")) {
            assertFailsWith<TwitchApiException>(key) { observe(envelope(JsonObject(record - key).toString())) }
        }
        for ((key, value) in listOf("currentMinutesWatched" to "\"0\"", "requiredMinutesWatched" to "1", "game" to "{}", "channel" to "{}")) {
            assertFailsWith<TwitchApiException>(key) { observe(envelope(JsonObject(record + (key to Json.parseToJsonElement(value))).toString())) }
        }
    }
    @Test fun `invalid minutes and identities are rejected`() {
        for (minutes in listOf("true", "null", "1.5", "-1", "2147483648", "\"1.0\"", "\"-1\"", "\" 1\"")) {
            assertFailsWith<TwitchApiException>(minutes) { observe(envelope("""{"channel":{"id":"10"},"dropID":"reward","currentMinutesWatched":$minutes}""")) }
        }
        for (id in listOf("", " ", "x".repeat(2049))) {
            assertFailsWith<TwitchApiException> { observe(envelope("""{"channel":{"id":"10"},"dropID":"$id","currentMinutesWatched":0}""")) }
        }
        for (channel in listOf("null", "{}", """{"id":0}""", """{"id":true}""", """{"id":"9223372036854775808"}""")) {
            assertFailsWith<TwitchApiException> { observe(envelope("""{"channel":$channel,"dropID":"reward","currentMinutesWatched":0}""")) }
        }
    }
    @Test fun `different channel remains distinct from absence and same channel`() {
        val progress = observe(envelope("""{"channel":{"id":"11"},"dropID":"reward","currentMinutesWatched":60}"""))!!
        assertEquals(11L, progress.channelId)
    }
}
