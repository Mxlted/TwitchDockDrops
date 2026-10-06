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

    @Test fun `TV login and renewal accept unspecified and long token lifetimes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TwitchApiClient(OkHttpClient(), oauthBaseUrl = server.url("/").toString(), deviceClientId = TwitchTvClientId)
            for (lifetime in listOf(null, "null", "0", "3600", "31536001", "4294967295", "\"4294967295\"")) {
                val body = """{"access_token":"new-access","refresh_token":"new-refresh"${lifetime?.let { ",\"expires_in\":$it" }.orEmpty()}}"""
                val before = Instant.now()
                server.enqueue(response(body))
                val issued = assertIs<DeviceTokenPollResult.Authorized>(api.pollDeviceToken("code", "device")).token
                server.enqueue(response(body))
                val renewed = api.refreshTvSession(session)
                for (expiry in listOf(issued.expiresAt, renewed.tokenExpiresAt)) {
                    val seconds = lifetime?.trim('"')?.toLongOrNull()
                    if (seconds == null || seconds == 0L) assertNull(expiry)
                    else {
                        assertNotNull(expiry)
                        assertTrue(expiry >= before.plusSeconds(seconds))
                        assertTrue(expiry <= Instant.now().plusSeconds(seconds))
                    }
                }
                assertEquals("new-refresh", issued.refreshToken)
                val store = SecureSessionStore(directory)
                store.saveTwitchSession(renewed)
                assertEquals(renewed, store.twitchSession())
            }
        }
    }

    @Test fun `malformed successful exchanges are terminal and do not expose credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TwitchApiClient(OkHttpClient(), oauthBaseUrl = server.url("/").toString(), deviceClientId = TwitchTvClientId)
            val invalidLifetimes = listOf("-1", "1.5", "true", "{}", "[]", "\"secret-lifetime\"", "9223372036854775807", "18446744073709551615")
            val bodies = invalidLifetimes.map {
                """{"access_token":"secret-access","refresh_token":"secret-refresh","expires_in":$it}"""
            } + listOf("secret-body", "[]", "{}", """{"access_token":"secret-access","expires_in":3600}""")
            for (body in bodies) {
                server.enqueue(response(body))
                val failure = assertFailsWith<DeviceAuthorizationException> { api.pollDeviceToken("secret-code", "device") }
                assertEquals("invalid_token_response", failure.oauthError)
                assertContains(failure.message!!, "new code")
                assertFalse(failure.message!!.contains("secret-"))
                assertNull(failure.cause)
                server.enqueue(response(body))
                assertFailsWith<IllegalStateException> { api.refreshTvSession(session) }
            }
            assertEquals(bodies.size * 2, server.requestCount)
        }
    }

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
    @Test fun `TV acceptance rejects identity mismatch and unusable account inventory`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = TwitchApiClient(OkHttpClient(), gqlEndpoint = server.url("/gql").toString(), oauthBaseUrl = server.url("/").toString(), deviceClientId = TwitchTvClientId)
            server.enqueue(response("""{"client_id":"$TwitchClientId","user_id":"42"}"""))
            assertFailsWith<TwitchApiException> { api.validateSession(session) }
            server.enqueue(response("""{"client_id":"$TwitchTvClientId","user_id":"43"}"""))
            assertFailsWith<TwitchApiException> { api.validateSession(session) }
            server.enqueue(response("""{"client_id":"$TwitchTvClientId","user_id":"42"}"""))
            server.enqueue(response("""{"data":{"currentUser":{"inventory":{}}}}"""))
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
