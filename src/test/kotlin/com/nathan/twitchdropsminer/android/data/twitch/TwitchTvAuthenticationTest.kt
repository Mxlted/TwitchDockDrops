package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class TwitchTvAuthenticationTest {
    @TempDir lateinit var directory: Path
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private val session = StoredTwitchSession("old", "42", "tv-device", Instant.EPOCH,
        clientId = TwitchTvClientId, refreshToken = "private-refresh", tokenExpiresAt = Instant.EPOCH)

    @Test fun `TV uses separate identity and refresh rotation remains encrypted`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TwitchApiClient(OkHttpClient(), oauthBaseUrl = server.url("/").toString(), deviceClientId = TwitchTvClientId)
            server.enqueue(response("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""))
            val rotated = api.refreshTvSession(session)
            val request = server.takeRequest()
            assertNull(request.requestUrl!!.query)
            assertContains(request.body.readUtf8(), "client_id=$TwitchTvClientId")
            assertEquals("https://android.tv.twitch.tv", request.getHeader("Origin"))
            assertEquals("42", rotated.userId)
            val store = SecureSessionStore(directory)
            store.saveTwitchSession(rotated)
            assertEquals(rotated, store.twitchSession())
            assertFalse(Files.readString(directory.resolve("session.enc")).contains("new-refresh"))
        }
    }
    @Test fun `TV acceptance rejects identity mismatch and missing direct campaign access`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TwitchApiClient(OkHttpClient(), gqlEndpoint = server.url("/gql").toString(), oauthBaseUrl = server.url("/").toString(), deviceClientId = TwitchTvClientId)
            server.enqueue(response("""{"client_id":"$TwitchClientId","user_id":"42"}"""))
            assertFailsWith<TwitchApiException> { api.validateSession(session) }
            server.enqueue(response("""{"client_id":"$TwitchTvClientId","user_id":"43"}"""))
            assertFailsWith<TwitchApiException> { api.validateSession(session) }
            server.enqueue(response("""{"client_id":"$TwitchTvClientId","user_id":"42"}"""))
            server.enqueue(response("""{"data":{"currentUser":{"inventory":{}}}}"""))
            server.enqueue(response("""{"data":{"currentUser":{}}}"""))
            assertFailsWith<IllegalStateException> { api.validateDropsAccess(session) }
        }
    }
    @Test fun `renewal throttle is bounded and cannot leak OAuth error bodies`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TwitchApiClient(OkHttpClient(), oauthBaseUrl = server.url("/").toString())
            server.enqueue(response("private-refresh").setResponseCode(429).setHeader("Retry-After", "9999"))
            assertEquals(60, assertFailsWith<TvRefreshThrottledException> { api.refreshTvSession(session) }.retryAfterSeconds)
            server.enqueue(response("private-refresh").setResponseCode(500))
            val failure = assertFailsWith<IllegalStateException> { api.refreshTvSession(session) }
            assertFalse(failure.message!!.contains("private-refresh"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `mobile sessions cannot spend refresh tokens under TV identity`() = runBlocking {
        assertFailsWith<IllegalArgumentException> { TwitchApiClient(OkHttpClient()).refreshTvSession(session.copy(clientId = null)) }
    }
}
