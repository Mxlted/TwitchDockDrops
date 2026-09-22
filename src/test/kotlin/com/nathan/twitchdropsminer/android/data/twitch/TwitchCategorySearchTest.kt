package com.nathan.twitchdropsminer.android.data.twitch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class TwitchCategorySearchTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder().build()
    private var clock = 0L
    private val search = TwitchCategorySearch(client, server.url("/gql").toString()) { clock }
    private val response = """{"data":{"searchCategories":{"edges":[{"node":{"id":"42","name":"Future Game"}}]}}}"""

    @AfterTest
    fun close() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `search uses variables bounded results and no account credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody(response))
        assertEquals(listOf(TwitchCategory("42", "Future Game")), search.search(" Future \"Game\" "))
        val request = server.takeRequest()
        assertEquals("/gql", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertEquals(TwitchClientId, request.getHeader("Client-ID"))
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Future \"Game\"", payload.getValue("variables").jsonObject.getValue("query").jsonPrimitive.content)
        assertTrue(payload.getValue("query").jsonPrimitive.content.contains("first: 12"))
    }

    @Test
    fun `cache is case insensitive expires and does not cache failures`() = runBlocking {
        server.enqueue(MockResponse().setBody(response))
        search.search("game")
        search.search(" GAME ")
        assertEquals(1, server.requestCount)
        clock = TimeUnit.MINUTES.toNanos(6)
        server.enqueue(MockResponse().setResponseCode(503).setBody("private diagnostic"))
        val error = assertFailsWith<CategorySearchException> { search.search("game") }
        assertTrue(!error.message.orEmpty().contains("private diagnostic"))
        server.enqueue(MockResponse().setBody(response))
        search.search("game")
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `malformed oversized and redirected replies fail safely and empty results are valid`() = runBlocking {
        for (body in listOf("not json", "{}", """{"errors":[{"message":"sensitive"}]}""", "x".repeat(128 * 1024 + 1))) {
            server.enqueue(MockResponse().setBody(body))
            assertFailsWith<CategorySearchException> { search.search("game") }
        }
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
        assertFailsWith<CategorySearchException> { search.search("game") }
        assertEquals(5, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"data":{"searchCategories":{"edges":[]}}}"""))
        assertTrue(search.search("game").isEmpty())
    }

    @Test
    fun `bad records are skipped and duplicate or excessive results are bounded`() = runBlocking {
        val nodes = (1..20).map { """{"node":{"id":"$it","name":"Game $it"}}""" }
        val edges = (listOf("null", """{"node":{"id":{},"name":[]}}""", nodes[0]) + nodes).joinToString(",")
        server.enqueue(MockResponse().setBody("""{"data":{"searchCategories":{"edges":[$edges]}}}"""))
        val found = search.search("game")
        assertEquals(12, found.size)
        assertEquals(12, found.map { it.id }.distinct().size)
    }

    @Test
    fun `only two catalog requests run concurrently and capacity is released`() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody(response)
            }
        }
        val first = async(Dispatchers.IO) { search.search("first") }
        val second = async(Dispatchers.IO) { search.search("second") }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(assertFailsWith<CategorySearchException> { search.search("third") }.busy)
        } finally {
            release.countDown()
        }
        first.await(); second.await()
        assertEquals(1, search.search("third").size)
    }

    @Test
    fun `search rejects unbounded queries and non Twitch production endpoints`() = runBlocking<Unit> {
        for (query in listOf("", "a", "a".repeat(101), "ab\ncd")) {
            assertFailsWith<IllegalArgumentException> { search.search(query) }
        }
        assertEquals(0, server.requestCount)
        assertFailsWith<IllegalArgumentException> { TwitchCategorySearch(client, "https://example.com/gql") }
        assertFailsWith<IllegalArgumentException> { TwitchCategorySearch(client, "http://gql.twitch.tv/gql") }
    }
}
