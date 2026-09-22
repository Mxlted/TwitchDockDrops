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
    private val response = page("""{"node":{"id":"42","name":"Future Game"}}""")

    private suspend fun lookup(query: String, after: String?): TwitchCategoryPage =
        search.search(CategorySearchRequest(query, after))

    private fun page(edges: String, hasNext: Boolean = false): String =
        """{"data":{"searchCategories":{"edges":[$edges],"pageInfo":{"hasNextPage":$hasNext}}}}"""

    @AfterTest
    fun close() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `search uses variables bounded results and no account credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody(response))
        assertEquals(TwitchCategoryPage(listOf(TwitchCategory("42", "Future Game"))), lookup(" Future \"Game\" ", null))
        val request = server.takeRequest()
        assertEquals("/gql", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertEquals(TwitchClientId, request.getHeader("Client-ID"))
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Future \"Game\"", payload.getValue("variables").jsonObject.getValue("query").jsonPrimitive.content)
        assertEquals("50", payload.getValue("variables").jsonObject.getValue("first").jsonPrimitive.content)
    }

    @Test
    fun `cache is case insensitive expires and does not cache failures`() = runBlocking {
        server.enqueue(MockResponse().setBody(response))
        lookup("game", null)
        lookup(" GAME ", null)
        assertEquals(1, server.requestCount)
        clock = TimeUnit.MINUTES.toNanos(6)
        server.enqueue(MockResponse().setResponseCode(503).setBody("private diagnostic"))
        val error = assertFailsWith<CategorySearchException> { lookup("game", null) }
        assertTrue(!error.message.orEmpty().contains("private diagnostic"))
        server.enqueue(MockResponse().setBody(response))
        lookup("game", null)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `cache evicts the oldest page after thirty two entries`() = runBlocking {
        repeat(33) { index ->
            server.enqueue(MockResponse().setBody(response))
            lookup("game", if (index == 0) null else "page$index")
        }
        lookup("GAME", "page1")
        lookup("GAME", "page32")
        assertEquals(33, server.requestCount)
        server.enqueue(MockResponse().setBody(response))
        lookup("game", null)
        assertEquals(34, server.requestCount)
    }

    @Test
    fun `invalid page shapes fail safely and release search capacity`() = runBlocking {
        val result = """{"edges":[],"pageInfo":{"hasNextPage":false}}"""
        val malformed = listOf(
            "[]",
            """{"errors":{},"data":{"searchCategories":$result}}""",
            """{"data":{"searchCategories":{"edges":{},"pageInfo":{"hasNextPage":false}}}}""",
            """{"data":{"searchCategories":{"edges":[],"pageInfo":{"hasNextPage":"false"}}}}""",
            """{"data":{"searchCategories":{"edges":[],"pageInfo":{}}}}""",
            page("""{"node":{"id":"not-numeric","name":"Game"}}"""),
        )
        for (body in malformed) {
            server.enqueue(MockResponse().setBody(body))
            val error = assertFailsWith<CategorySearchException> { lookup("game", null) }
            assertTrue(!error.busy)
        }
        server.enqueue(MockResponse().setBody("""{"errors":[],"data":{"searchCategories":$result}}"""))
        assertTrue(lookup("game", null).categories.isEmpty())
        assertEquals(malformed.size + 1, server.requestCount)
    }

    @Test
    fun `malformed oversized and redirected replies fail safely and empty results are valid`() = runBlocking {
        for (body in listOf("not json", "{}", """{"errors":[{"message":"sensitive"}]}""", "x".repeat(128 * 1024 + 1))) {
            server.enqueue(MockResponse().setBody(body))
            assertFailsWith<CategorySearchException> { lookup("game", null) }
        }
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
        assertFailsWith<CategorySearchException> { lookup("game", null) }
        assertEquals(5, server.requestCount)
        server.enqueue(MockResponse().setBody(page("")))
        assertTrue(lookup("game", null).categories.isEmpty())
    }

    @Test
    fun `bad records are skipped and duplicate or excessive results are bounded`() = runBlocking {
        val nodes = (1..45).map { """{"node":{"id":"$it","name":"Game $it"}}""" }
        val edges = (listOf("null", """{"node":{"id":{},"name":[]}}""", nodes[0]) + nodes).joinToString(",")
        server.enqueue(MockResponse().setBody(page(edges)))
        val found = lookup("game", null).categories
        assertEquals(45, found.size)
        assertEquals(45, found.map { it.id }.distinct().size)
        server.enqueue(MockResponse().setBody(page((nodes + nodes).joinToString(","))))
        assertFailsWith<CategorySearchException> { lookup("other", null) }
        Unit
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
        val first = async(Dispatchers.IO) { lookup("first", null) }
        val second = async(Dispatchers.IO) { lookup("second", null) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(assertFailsWith<CategorySearchException> { lookup("third", null) }.busy)
        } finally {
            release.countDown()
        }
        first.await(); second.await()
        assertEquals(1, lookup("third", null).categories.size)
    }

    @Test
    fun `short searches keep twelve results and cannot page`() = runBlocking {
        for (query in listOf("st", "sta")) {
            val edges = (1..12).joinToString(",") { """{"cursor":"MTI=","node":{"id":"$it","name":"Star $it"}}""" }
            server.enqueue(MockResponse().setBody(page(edges, true)))
            val found = lookup(query, null)
            assertEquals(12, found.categories.size)
            assertNull(found.nextCursor)
            val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("12", payload.getValue("variables").jsonObject.getValue("first").jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> { lookup(query, "MTI=") }
        }
    }

    @Test
    fun `four character searches expose subsequent pages and cache each cursor separately`() = runBlocking {
        server.enqueue(MockResponse().setBody(page("""{"cursor":"NTA=","node":{"id":"1","name":"Star First"}}""", true)))
        val first = lookup("star", null)
        assertEquals("NTA=", first.nextCursor)
        server.enqueue(MockResponse().setBody(page("""{"node":{"id":"2","name":"Star Last"}}""")))
        val second = lookup("star", first.nextCursor)
        assertEquals("Star Last", second.categories.single().name)
        assertNull(second.nextCursor)
        assertEquals(first, lookup("STAR", null))
        assertEquals(second, lookup("STAR", "NTA="))
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val payload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("NTA=", payload.getValue("variables").jsonObject.getValue("after").jsonPrimitive.content)
    }

    @Test
    fun `missing invalid and repeated continuation cursors fail without caching partial pages`() = runBlocking {
        for (cursor in listOf("", "NTA=", "bad cursor")) {
            server.enqueue(MockResponse().setBody(page("""{"cursor":"$cursor","node":{"id":"1","name":"Star"}}""", true)))
            assertFailsWith<CategorySearchException> { lookup("star", "NTA=") }
        }
        server.enqueue(MockResponse().setBody(page("", true)))
        assertFailsWith<CategorySearchException> { lookup("star", "NTA=") }
        server.enqueue(MockResponse().setBody(response))
        assertEquals(1, lookup("star", "NTA=").categories.size)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `search rejects unbounded queries and non Twitch production endpoints`() = runBlocking<Unit> {
        for (query in listOf("", "a", "a".repeat(101), "ab\ncd")) {
            assertFailsWith<IllegalArgumentException> { lookup(query, null) }
        }
        assertEquals(0, server.requestCount)
        assertFailsWith<IllegalArgumentException> { TwitchCategorySearch(client, "https://example.com/gql") }
        assertFailsWith<IllegalArgumentException> { TwitchCategorySearch(client, "http://gql.twitch.tv/gql") }
    }
}
