package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.runBlocking
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
                                assertEquals(context.toJson(),body["context"]); "{}"
                            }
                            "/status" -> buildJsonObject {
                                put("id", if (replace) "different-session" else id)
                                put("state", "capturing"); put("context", context.toJson())
                            }.toString()
                            "/cancel" -> { cancelled = body!!.getValue("id").jsonPrimitive.content; "{}" }
                            else -> error("Unexpected route")
                        })
                    }
                }
                server.start()
                BrowserRenewalClient(server.port).use { client ->
                    if (replace) assertFailsWith<IllegalStateException> { client.renew(context) }
                    else assertEquals(context.toJson(),client.renew(context).toJson())
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
                val error = assertFailsWith<IllegalStateException> { client.renew(context()) }
                assertEquals("Browser renewal temporarily unavailable.",error.message)
            }
            assertEquals("/cancel",server.takeRequest().let { server.takeRequest().path })
        }
    }
}
