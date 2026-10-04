package com.nathan.twitchdropsminer.android.data.twitch

import app.twitchdockdrops.StateJson
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.model.*
import com.nathan.twitchdropsminer.android.runtime.BrowserLoginAdmission
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir

class BrowserAuthenticationTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.now()
    private fun context() = buildJsonObject {
        put("version", 1); put("captured_at", now.epochSecond); put("expires_at", now.plusSeconds(3600).epochSecond)
        put("user_agent", "TestBrowser/1.0")
        put("headers", buildJsonObject {
            put("client-id", TwitchWebClientId); put("authorization", "OAuth browser-secret")
            put("client-integrity", "integrity-secret"); put("client-version", "version")
            put("x-device-id", "browser-device"); put("client-session-id", "browser-session")
        })
    }
    private fun client(server: MockWebServer) = TwitchApiClient(OkHttpClient(),
        gqlEndpoint = server.url("/gql").toString(), twitchWebBaseUrl = server.url("/").toString(),
        oauthBaseUrl = server.url("/").toString())

    @Test fun `browser context rejects extra headers controls stale proofs and invalid types`() {
        val original = context()
        for (value in listOf(
            JsonObject(original + ("version" to JsonPrimitive("1"))),
            JsonObject(original + ("user_agent" to JsonPrimitive("bad\r\nHeader: secret"))),
            JsonObject(original + ("headers" to JsonObject(original["headers"]!!.jsonObject + ("cookie" to JsonPrimitive("secret"))))),
            JsonObject(original + ("expires_at" to JsonPrimitive(now.plusSeconds(100000).epochSecond))),
        )) {
            assertEquals("Invalid browser session context.", assertFailsWith<IllegalArgumentException> { BrowserSessionContext.parse(value) }.message)
        }
        val parsed = BrowserSessionContext.parse(original)
        assertFailsWith<IllegalArgumentException> { parsed.requireFresh(now.plusSeconds(3600)) }
        assertFalse(parsed.toString().contains("secret"))
    }

    @Test fun `web credentials require server OAuth and both Drops queries before acceptance`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"client_id":"$TwitchWebClientId","user_id":"12345"}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"inventory":{}}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"dropCampaigns":[]}}}"""))
            val session = client(server).validateBrowserContext(BrowserSessionContext.parse(context()))
            assertEquals("12345", session.userId)
            val oauth = server.takeRequest()
            assertNull(oauth.getHeader("Client-Integrity"))
            repeat(2) {
                val gql = server.takeRequest()
                assertEquals("integrity-secret", gql.getHeader("Client-Integrity"))
                assertEquals("browser-session", gql.getHeader("Client-Session-Id"))
                assertEquals("TestBrowser/1.0", gql.getHeader("User-Agent"))
                assertEquals(TwitchWebClientId, gql.getHeader("Client-Id"))
            }
        }
    }

    @Test fun `browser login rejects valid OAuth with failed Drops access`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"client_id":"$TwitchWebClientId","user_id":"12345"}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"inventory":{}}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":null}}"""))
            assertFailsWith<IllegalStateException> { client(server).validateBrowserContext(BrowserSessionContext.parse(context())) }
        }
    }

    @Test fun `browser context is encrypted and absent from public state`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
        val store = SecureSessionStore(directory, key)
        val context = BrowserSessionContext.parse(context())
        store.saveTwitchSession(StoredTwitchSession(context.accessToken, "12345", context.deviceId, now, context))
        val restored = assertNotNull(SecureSessionStore(directory, key).twitchSession())
        assertEquals(context.toJson(), restored.browserContext?.toJson())
        val disk = Files.readString(directory.resolve("session.enc"))
        assertFalse(disk.contains("browser-secret")); assertFalse(disk.contains("integrity-secret"))
        val state = StateJson().encode(RuntimeSnapshot(account = LoginSession(LoginState.LoggedIn,"Connected", method="browser")), AppSettings(), emptyList())
        assertFalse(state.contains("integrity")); assertFalse(state.contains("headers")); assertFalse(state.contains("browserContext"))
        assertTrue(state.contains("\"method\":\"browser\""))
    }

    @Test fun `pairing is single owner bounded and reset revokes renewal`() {
        var clock = now
        val admission = BrowserLoginAdmission { clock }
        val (code, _) = admission.open()
        val ticket = admission.claim(code)
        assertFailsWith<IllegalArgumentException> { admission.claim(code) }
        val lease = admission.begin(ticket)
        assertFailsWith<IllegalArgumentException> { admission.begin(ticket) }
        admission.accepted(lease.generation, "12345")
        assertEquals("ready", admission.status(ticket))
        assertEquals("12345", admission.begin(ticket).expectedAccount)
        admission.clear()
        admission.accepted(lease.generation, "12345")
        assertFailsWith<IllegalArgumentException> { admission.status(ticket) }
        val (expires, _) = admission.open()
        clock = clock.plusSeconds(601)
        assertFailsWith<IllegalArgumentException> { admission.claim(expires) }
        val (locked, _) = admission.open()
        repeat(5) { assertFailsWith<IllegalArgumentException> { admission.claim("wrong") } }
        assertFailsWith<IllegalArgumentException> { admission.claim(locked) }
    }

    @Test fun `redirect never forwards browser headers to another origin`() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/steal")))
            val context = BrowserSessionContext.parse(context())
            assertFailsWith<TwitchApiException> { client(server).fetchCampaignInventory(
                StoredTwitchSession(context.accessToken,"12345",context.deviceId,now,context)) }
            assertEquals(0, other.requestCount)
        } }
    }
}
