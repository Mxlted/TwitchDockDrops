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
