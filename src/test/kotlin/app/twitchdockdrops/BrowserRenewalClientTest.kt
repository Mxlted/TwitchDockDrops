package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*

class BrowserRenewalClientTest {
    private fun context(): BrowserSessionContext {
        val now = Instant.now().epochSecond
        return BrowserSessionContext.parse(Json.parseToJsonElement("""{
          "version":1,"captured_at":$now,"expires_at":${now+3600},"user_agent":"Chrome/Test",
          "headers":{"authorization":"OAuth fixture","client-id":"kimne78kx3ncx6brgo4mv6wki5h1ko",
          "client-integrity":"fixture-proof","x-device-id":"fixture-device"},
          "sdk_cookie":{"value":"fixture-cookie","expires_at":${now+86400}}}
        """) as JsonObject)
    }

    @Test fun `private renewal transport bounds ownership and cleans up its own browser`() = runBlocking {
        for (replace in listOf(false,true)) {
            MockWebServer().use { server ->
                var id = ""; var cancelled = ""
                val context = context()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        assertEquals("1", request.getHeader("X-DockDrops-Internal"))
                        assertNull(request.getHeader("Origin"))
                        val body = if (request.method == "POST") Json.parseToJsonElement(request.body.readUtf8()).jsonObject else null
                        return MockResponse().setBody(when (request.path) {
                            "/renew" -> {
                                id = body!!.getValue("id").jsonPrimitive.content
                                assertEquals(context.toJson(),body["context"])
                                assertEquals("12345", body["user_id"]?.jsonPrimitive?.content); "{}"
                            }
                            "/status" -> buildJsonObject {
                                put("id", if (replace) "different-session" else id)
                                put("state", "capturing"); put("context", context.toJson())
                            }.toString()
                            "/release" -> { cancelled = body!!.getValue("id").jsonPrimitive.content; "{}" }
                            else -> error("Unexpected route")
                        })
                    }
                }
                server.start()
                BrowserRenewalClient(server.port).use { client ->
                    if (replace) assertFailsWith<IllegalStateException> { client.renew(context, "12345") }
                    else assertEquals(context.toJson(),client.renew(context, "12345").toJson())
                }
                assertEquals(id,cancelled)
                assertEquals(3,server.requestCount)
            }
        }
    }

    @Test fun `companion errors never disclose private diagnostics`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setBody("private-cookie-and-token"))
            server.enqueue(MockResponse().setBody("{}"))
            BrowserRenewalClient(server.port).use { client ->
                val error = assertFailsWith<IllegalStateException> { client.renew(context(), "12345") }
                assertEquals("Browser renewal temporarily unavailable.",error.message)
            }
            assertEquals("/release",server.takeRequest().let { server.takeRequest().path })
        }
    }

    @Test fun `missing retained browser is terminal and revocation is lease scoped`() = runBlocking {
        MockWebServer().use { server ->
            var id = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (request.method == "POST") Json.parseToJsonElement(request.body.readUtf8()).jsonObject else null
                    return MockResponse().setBody(when (request.path) {
                        "/renew" -> { id = body!!.getValue("id").jsonPrimitive.content; "{}" }
                        "/status" -> """{"id":"$id","state":"failed","error":"browser_missing"}"""
                        "/release" -> { assertEquals(id,body!!.getValue("id").jsonPrimitive.content); "{}" }
                        "/revoke" -> { assertEquals("12345678-1234-1234-1234-123456789abc",body!!.getValue("lease").jsonPrimitive.content); "{}" }
                        else -> error("Unexpected route")
                    })
                }
            }
            server.start()
            BrowserRenewalClient(server.port).use { client ->
                assertFailsWith<com.nathan.twitchdropsminer.android.data.model.BrowserLeaseUnavailableException> { client.renew(context(), "12345") }
                client.revoke("12345678-1234-1234-1234-123456789abc")
            }
        }
    }

    @Test fun `only correlated OAuth invalid status marks the saved token invalid`() = runBlocking {
        for (correlated in listOf(true, false)) MockWebServer().use { server ->
            var id = ""; var released = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (request.method == "POST") Json.parseToJsonElement(request.body.readUtf8()).jsonObject else null
                    return MockResponse().setBody(when (request.path) {
                        "/renew" -> { id = body!!.getValue("id").jsonPrimitive.content; "{}" }
                        "/status" -> buildJsonObject {
                            put("id", if (correlated) id else "superseded-operation")
                            put("state", "failed"); put("error", "oauth_invalid")
                            put("detail", "private-cookie-and-token")
                        }.toString()
                        "/release" -> { released = body!!.getValue("id").jsonPrimitive.content; "{}" }
                        else -> error("Unexpected route")
                    })
                }
            }
            server.start()
            BrowserRenewalClient(server.port).use { client ->
                val failure = assertFailsWith<IllegalStateException> { client.renew(context(), "12345") }
                if (correlated) assertEquals(
                    com.nathan.twitchdropsminer.android.data.twitch.TwitchApiErrorType.InvalidToken,
                    (failure as com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException).type)
                else assertFalse(failure is com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException)
                assertFalse(failure.message.orEmpty().contains("private-cookie"))
            }
            assertEquals(id, released); assertEquals(3, server.requestCount)
        }
    }

    @Test fun `cancelled polling releases only the owned operation before completing`() = runBlocking {
        MockWebServer().use { server ->
            val polling = CompletableDeferred<Unit>()
            var id = ""; var released = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (request.method == "POST") Json.parseToJsonElement(request.body.readUtf8()).jsonObject else null
                    return MockResponse().setBody(when (request.path) {
                        "/renew" -> { id = body!!.getValue("id").jsonPrimitive.content; "{}" }
                        "/status" -> { polling.complete(Unit); """{"id":"$id","state":"capturing"}""" }
                        "/release" -> { released = body!!.getValue("id").jsonPrimitive.content; "{}" }
                        else -> error("Unexpected route")
                    })
                }
            }
            server.start()
            BrowserRenewalClient(server.port).use { client ->
                val job = launch { client.renew(context(), "12345") }
                withTimeout(3000) { polling.await(); job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
            assertEquals(id, released); assertEquals(3, server.requestCount)
        }
    }
}
