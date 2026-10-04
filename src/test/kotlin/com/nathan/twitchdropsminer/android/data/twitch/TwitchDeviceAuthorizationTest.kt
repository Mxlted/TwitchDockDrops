package com.nathan.twitchdropsminer.android.data.twitch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TwitchDeviceAuthorizationTest {
    @Test
    fun `device request rejection is terminal while throttling and server failures are retryable`() {
        withClient { server, client ->
            for (code in listOf(400, 401, 403)) {
                server.enqueue(json("{}", code))
                assertEquals("device_authorization_rejected", assertFailsWith<DeviceAuthorizationException> {
                    runBlocking { client.requestDeviceCode("device") }
                }.oauthError)
            }
            for (code in listOf(429, 500, 503)) {
                server.enqueue(json("{}", code))
                assertEquals(TwitchApiErrorType.Http, assertFailsWith<TwitchApiException> {
                    runBlocking { client.requestDeviceCode("device") }
                }.type)
            }
        }
    }

    @Test
    fun `documented Twitch message responses follow the device polling lifecycle`() {
        withClient { server, client ->
            for ((message, expected) in listOf(
                "authorization_pending" to DeviceTokenPollResult.AuthorizationPending,
                "slow_down" to DeviceTokenPollResult.SlowDown,
            )) {
                server.enqueue(json("""{"status":400,"message":"$message"}""", 400))
                assertEquals(expected, runBlocking { client.pollDeviceToken("secret", "device") })
            }
            for ((message, expected) in listOf(
                "access_denied" to "access_denied",
                "expired_token" to "expired_token",
                "invalid device code" to "expired_token",
            )) {
                server.enqueue(json("""{"status":400,"message":"$message"}""", 400))
                assertEquals(expected, assertFailsWith<DeviceAuthorizationException> {
                    runBlocking { client.pollDeviceToken("secret", "device") }
                }.oauthError)
            }
            server.enqueue(json("""{"access_token":"issued-token"}"""))
            assertEquals(DeviceTokenPollResult.Authorized(TokenResponse("issued-token")),
                runBlocking { client.pollDeviceToken("secret", "device") })
        }
    }

    @Test
    fun `OAuth error field takes precedence over a conflicting message`() {
        withClient { server, client ->
            server.enqueue(json("""{"error":"access_denied","message":"authorization_pending"}""", 400))
            assertEquals("access_denied", assertFailsWith<DeviceAuthorizationException> {
                runBlocking { client.pollDeviceToken("secret", "device") }
            }.oauthError)
        }
    }

    @Test
    fun `temporary HTTP errors remain retryable regardless of body`() {
        withClient { server, client ->
            for (code in listOf(429, 500, 502, 503)) {
                server.enqueue(json("""{"error":"access_denied"}""", code))
                val error = assertFailsWith<TwitchApiException> {
                    runBlocking { client.pollDeviceToken("secret", "device") }
                }
                assertEquals(TwitchApiErrorType.Http, error.type)
            }
        }
    }

    @Test
    fun `OAuth failures never surface untrusted response text`() {
        withClient { server, client ->
            server.enqueue(json("""{"error":"sensitive-upstream-value"}""", 400))
            val requestError = assertFailsWith<IllegalStateException> {
                runBlocking { client.requestDeviceCode("device") }
            }
            assertFalse(requestError.message.orEmpty().contains("sensitive-upstream-value"))
            server.enqueue(json("""{"error":"sensitive-upstream-value"}""", 400))
            val pollError = assertFailsWith<DeviceAuthorizationException> {
                runBlocking { client.pollDeviceToken("secret", "device") }
            }
            assertEquals("unsupported_error", pollError.oauthError)
            assertFalse(pollError.message.orEmpty().contains("sensitive-upstream-value"))
            for (body in listOf("sensitive-upstream-value", "[]", "null")) {
                server.enqueue(json(body))
                val malformed = assertFailsWith<TwitchApiException> {
                    runBlocking { client.requestDeviceCode("device") }
                }
                assertFalse(malformed.message.orEmpty().contains("sensitive-upstream-value"))
                assertEquals(null, malformed.cause)
            }
        }
    }

    @Test
    fun `device response requires nonblank fields and a trusted activation URL`() {
        withClient { server, client ->
            server.enqueue(
                json(
                    """{"device_code":"device-secret","user_code":"ABCD","verification_uri":"${server.url("/activate")}","expires_in":900,"interval":5}""",
                ),
            )
            val authorization = runBlocking { client.requestDeviceCode("device-id") }
            assertEquals("ABCD", authorization.userCode)

            server.enqueue(
                json(
                    """{"device_code":"","user_code":"ABCD","verification_uri":"${server.url("/activate")}","expires_in":900,"interval":5}""",
                ),
            )
            val missing = assertFailsWith<IllegalStateException> {
                runBlocking { client.requestDeviceCode("device-id") }
            }
            assertFalse(missing.message.orEmpty().contains("device-secret"))

            server.enqueue(
                json(
                    """{"device_code":"secret-two","user_code":"ABCD","verification_uri":"https://evil.example/activate","expires_in":900,"interval":5}""",
                ),
            )
            val untrusted = assertFailsWith<IllegalStateException> {
                runBlocking { client.requestDeviceCode("device-id") }
            }
            assertFalse(untrusted.message.orEmpty().contains("secret-two"))
        }
    }

    @Test
    fun `token polling parses pending slowdown denial expiry and malformed errors`() {
        withClient { server, client ->
            server.enqueue(json("""{"error":"authorization_pending"}""", 400))
            server.enqueue(json("""{"error":"slow_down"}""", 400))
            server.enqueue(json("""{"error":"access_denied"}""", 400))
            server.enqueue(json("""{"error":"expired_token"}""", 400))
            server.enqueue(json("{}", 400))

            assertEquals(
                DeviceTokenPollResult.AuthorizationPending,
                runBlocking { client.pollDeviceToken("device-secret", "device-id") },
            )
            assertEquals(
                DeviceTokenPollResult.SlowDown,
                runBlocking { client.pollDeviceToken("device-secret", "device-id") },
            )
            assertEquals(
                "access_denied",
                assertFailsWith<DeviceAuthorizationException> {
                    runBlocking { client.pollDeviceToken("device-secret", "device-id") }
                }.oauthError,
            )
            assertEquals(
                "expired_token",
                assertFailsWith<DeviceAuthorizationException> {
                    runBlocking { client.pollDeviceToken("device-secret", "device-id") }
                }.oauthError,
            )
            val malformed = assertFailsWith<TwitchApiException> {
                runBlocking { client.pollDeviceToken("device-secret", "device-id") }
            }
            assertEquals(TwitchApiErrorType.UnexpectedResponse, malformed.type)
            assertFalse(malformed.message.orEmpty().contains("device-secret"))
        }
    }

    @Test
    fun `oauth response size is bounded`() {
        withClient { server, client ->
            server.enqueue(json("{\"padding\":\"${"x".repeat(70_000)}\"}"))
            val error = assertFailsWith<TwitchApiException> {
                runBlocking { client.requestDeviceCode("device-id") }
            }
            assertEquals(TwitchApiErrorType.UnexpectedResponse, error.type)
        }
    }

    private fun withClient(block: (MockWebServer, TwitchApiClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val root = server.url("/").toString()
            block(
                server,
                TwitchApiClient(
                    OkHttpClient(),
                    gqlEndpoint = server.url("/gql").toString(),
                    twitchWebBaseUrl = root,
                    oauthBaseUrl = root,
                ),
            )
        } finally {
            server.shutdown()
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
