package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.model.BrowserSessionContext
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.CampaignInventory
import com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorization
import com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorizationException
import com.nathan.twitchdropsminer.android.data.twitch.DeviceTokenPollResult
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimResult
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimOutcome
import com.nathan.twitchdropsminer.android.data.twitch.TokenResponse
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiErrorType
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException
import com.nathan.twitchdropsminer.android.data.twitch.ValidatedToken
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

class LocalMinerRuntimeExecutionTest {
    @Test fun `malformed TV exchange stops polling and preserves the previous session`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val old = store.twitchSession()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"access_token":"private-access","refresh_token":"private-refresh","expires_in":-1}"""))
            val transport = com.nathan.twitchdropsminer.android.data.twitch.TwitchApiClient(
                okhttp3.OkHttpClient(), oauthBaseUrl = server.url("/").toString(),
                deviceClientId = com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId)
            val api = object : TwitchApi by AuthenticationTwitchApi() {
                override suspend fun pollDeviceToken(deviceCode: String, deviceId: String) =
                    transport.pollDeviceToken(deviceCode, deviceId)
            }
            val runtime = runtime(store, api, tvAuthenticationApi = api)
            try {
                runtime.startTvAuthentication()
                withTimeout(3000) { runtime.snapshot.first { it.currentTask == "Twitch login failed" } }
                assertEquals(1, server.requestCount)
                assertEquals(old, store.twitchSession())
                assertTrue(runtime.snapshot.value.error!!.contains("new code"))
                assertFalse(runtime.snapshot.value.error!!.contains("private-"))
                assertFalse(runtime.snapshot.value.error!!.contains("Retrying"))
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `TV session without a deadline does not rotate continuously after restore`() = runBlocking {
        val store = sessionStore()
        val old = storedSession().copy(clientId = com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId,
            refreshToken = "old-refresh", tokenExpiresAt = null)
        store.saveTwitchSession(old)
        val rotations = AtomicInteger()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun refreshTvSession(session: StoredTwitchSession): StoredTwitchSession {
                rotations.incrementAndGet()
                return session
            }
        }
        val runtime = runtime(store, api)
        try {
            runtime.bootstrap()
            withTimeout(3000) { runtime.snapshot.first { it.account.isAuthenticated } }
            delay(1200) // The previous null-as-expired scheduler rotated after one second.
            assertEquals(0, rotations.get())
            assertEquals(old.accessToken, store.twitchSession()?.accessToken)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `TV session without a deadline still renews after authoritative invalidation`() = runBlocking {
        val store = sessionStore()
        val old = storedSession().copy(clientId = com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId,
            refreshToken = "old-refresh", tokenExpiresAt = null)
        store.saveTwitchSession(old)
        val rotations = AtomicInteger()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateSession(session: StoredTwitchSession): ValidatedToken {
                if (session.accessToken == old.accessToken) throw TwitchApiException(TwitchApiErrorType.InvalidToken, "Expired")
                return ValidatedToken(session.userId, session.clientId!!)
            }
            override suspend fun refreshTvSession(session: StoredTwitchSession): StoredTwitchSession {
                rotations.incrementAndGet()
                return session.copy(accessToken = "new-access", refreshToken = "new-refresh", tokenExpiresAt = null)
            }
            override suspend fun validateDropsAccess(session: StoredTwitchSession) { validateSession(session) }
            override suspend fun fetchCampaignInventory(session: StoredTwitchSession): CampaignInventory {
                validateSession(session)
                return CampaignInventory(emptyList())
            }
        }
        val runtime = runtime(store, api)
        try {
            runtime.bootstrap()
            withTimeout(4000) { while (store.twitchSession()?.refreshToken != "new-refresh") delay(10) }
            delay(1200)
            assertEquals(1, rotations.get())
            assertEquals("new-access", store.twitchSession()?.accessToken)
            assertNull(store.twitchSession()?.tokenExpiresAt)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `experimental TV acceptance failure preserves previous encrypted session`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val old = store.twitchSession()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun pollDeviceToken(deviceCode: String, deviceId: String) = DeviceTokenPollResult.Authorized(
                TokenResponse("tv-access", "tv-refresh", Instant.now().plusSeconds(3600)))
            override suspend fun validateAccessToken(accessToken: String) = ValidatedToken("12345", com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId)
            override suspend fun validateDropsAccess(session: StoredTwitchSession) { error("Direct Twitch campaign discovery unavailable") }
        }
        val runtime = runtime(store, api, tvAuthenticationApi = api)
        try {
            runtime.startTvAuthentication()
            withTimeout(3000) { runtime.snapshot.first { it.currentTask == "Twitch login failed" } }
            assertEquals(old, store.twitchSession())
            assertFalse(runtime.snapshot.value.account.isAuthenticated)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `TV rotation validates before atomic save and cannot revive reset`() = runBlocking {
        val store = sessionStore()
        val old = storedSession().copy(clientId = com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId,
            refreshToken = "old-refresh", tokenExpiresAt = Instant.EPOCH)
        store.saveTwitchSession(old)
        val validating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun refreshTvSession(session: StoredTwitchSession) = session.copy(
                accessToken = "new-access", refreshToken = "new-refresh", tokenExpiresAt = Instant.now().plusSeconds(3600))
            override suspend fun validateDropsAccess(session: StoredTwitchSession) {
                validating.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        val runtime = runtime(store, api)
        try {
            runtime.bootstrap()
            withTimeout(3000) { validating.await() }
            assertEquals(old, store.twitchSession())
            runtime.resetSessionAndJoin()
            release.complete(Unit)
            delay(150)
            assertNull(store.twitchSession())
            assertFalse(runtime.snapshot.value.account.isAuthenticated)
        } finally { release.complete(Unit); runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `TV rotation saves both tokens together after validation`() = runBlocking {
        val store = sessionStore()
        val old = storedSession().copy(clientId = com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId,
            refreshToken = "old-refresh", tokenExpiresAt = Instant.EPOCH)
        store.saveTwitchSession(old)
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun refreshTvSession(session: StoredTwitchSession) = session.copy(
                accessToken = "new-access", refreshToken = "new-refresh", tokenExpiresAt = Instant.now().plusSeconds(3600))
            override suspend fun validateDropsAccess(session: StoredTwitchSession) { assertEquals(old.userId, session.userId) }
        }
        val runtime = runtime(store, api)
        try {
            runtime.bootstrap()
            withTimeout(3000) { while (store.twitchSession()?.refreshToken != "new-refresh") delay(10) }
            assertEquals("new-access", store.twitchSession()?.accessToken)
            assertEquals(old.userId, store.twitchSession()?.userId)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `shutdown ignores queued start and inventory commands without changing saved intent`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession())
        val settings = SettingsRepository(directory)
        settings.update { it.copy(miningRequested=false) }
        val requests = AtomicInteger()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun fetchCampaignInventory(session: StoredTwitchSession): CampaignInventory {
                requests.incrementAndGet(); return CampaignInventory(emptyList())
            }
        }
        val runtime = runtime(store,api,settings)
        runtime.stopMiningAndJoin(shutdown=true)
        // Renewal acceptance can enqueue these behind an already queued shutdown command.
        runtime.startMining(); runtime.refreshInventory()
        runtime.stopMiningAndJoin(shutdown=true) // Drain those commands before checking.
        assertFalse(settings.settings.value.miningRequested)
        assertEquals(0,requests.get())
        assertFalse(runtime.snapshot.value.miningActive)
    }

    private fun retainedContext(renewed: Boolean = false): BrowserSessionContext = BrowserSessionContext.parse(
        kotlinx.serialization.json.JsonObject(renewableContext(renewed).toJson() - "sdk_cookie" +
            ("browser_lease" to kotlinx.serialization.json.JsonPrimitive("12345678-1234-1234-1234-123456789abc"))))

    @Test fun `retained Docker renewal persists fresh proof and reset revokes its encrypted lease`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext=retainedContext()))
        val revoked = CopyOnWriteArrayList<String>()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext) = storedSession().copy(browserContext=context)
        }
        val runtime = runtime(store,api,browserRenewal={ retainedContext(true) },browserLeaseRevoke={ revoked.add(it) })
        try {
            runtime.bootstrap()
            withTimeout(3000) { while (store.twitchSession()?.browserContext?.headers?.get("client-integrity") != "new-proof") delay(10) }
            assertTrue(runtime.snapshot.value.account.statusText.contains("Docker browser renewal"))
            runtime.stopMiningAndJoin()
            assertTrue(revoked.isEmpty()) // Stop keeps authentication renewal alive.
            runtime.resetSessionAndJoin()
            assertEquals(listOf(retainedContext().browserLease),revoked)
            assertNull(store.twitchSession())
        } finally { runtime.stopMiningAndJoin(shutdown=true) }
    }

    @Test fun `missing retained browser preserves credentials and requests reconnect instead of endless retries`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext=retainedContext()))
        val attempts = AtomicInteger()
        val runtime = runtime(store,AuthenticationTwitchApi(),browserRenewal={
            attempts.incrementAndGet()
            throw com.nathan.twitchdropsminer.android.data.model.BrowserLeaseUnavailableException()
        })
        try {
            runtime.bootstrap()
            withTimeout(3000) { runtime.snapshot.first { it.error?.contains("Docker login browser") == true } }
            assertEquals("old-proof",store.twitchSession()?.browserContext?.headers?.get("client-integrity"))
            assertEquals(1,attempts.get())
        } finally { runtime.stopMiningAndJoin(shutdown=true) }
    }

    @Test fun `retained renewal rejects changed leases and replacement revokes only the previous browser`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext=retainedContext()))
        val revoked = CopyOnWriteArrayList<String>()
        val runtime = runtime(store,AuthenticationTwitchApi(),browserRenewal={
            BrowserSessionContext.parse(kotlinx.serialization.json.JsonObject(retainedContext(true).toJson() +
                ("browser_lease" to kotlinx.serialization.json.JsonPrimitive("00000000-0000-0000-0000-000000000000"))))
        },browserLeaseRevoke={ revoked.add(it) })
        try {
            runtime.bootstrap()
            withTimeout(3000) { runtime.snapshot.first { it.error?.contains("renewal is temporarily") == true } }
            assertEquals(retainedContext().browserLease,store.twitchSession()?.browserContext?.browserLease)
            runtime.startManagedBrowserAuthentication()
            assertEquals(listOf(retainedContext().browserLease),revoked)
            assertEquals("old-proof",store.twitchSession()?.browserContext?.headers?.get("client-integrity"))
        } finally { runtime.stopMiningAndJoin(shutdown=true) }
    }

    private fun renewableContext(renewed: Boolean = false): BrowserSessionContext {
        val time = Instant.now().epochSecond
        return BrowserSessionContext.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{
          "version":1,"captured_at":${time-3600},"expires_at":${if (renewed) time+3600 else time-1},
          "user_agent":"Chrome/Test","headers":{"client-id":"kimne78kx3ncx6brgo4mv6wki5h1ko",
          "authorization":"OAuth test-token","client-integrity":"${if (renewed) "new-proof" else "old-proof"}","x-device-id":"device"},
          "sdk_cookie":{"value":"${if (renewed) "rotated-sdk" else "initial-sdk"}","expires_at":${if (renewed) time+86400 else time+3600}}}
        """) as kotlinx.serialization.json.JsonObject)
    }

    @Test fun `restart renews expired proof from encrypted seed and persists the validated rotation`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext = renewableContext()))
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext) = storedSession().copy(browserContext = context)
        }
        val runtime = runtime(store, api, browserRenewal = { context ->
            assertEquals("initial-sdk", context.sdkCookie?.value)
            renewableContext(true)
        })
        try {
            runtime.bootstrap()
            withTimeout(3000) { while (store.twitchSession()?.browserContext?.sdkCookie?.value != "rotated-sdk") delay(10) }
            assertEquals("new-proof", sessionStore().twitchSession()?.browserContext?.headers?.get("client-integrity"))
            assertFalse(runtime.snapshot.value.miningActive)
            assertFalse(java.nio.file.Files.readString(directory.resolve("session.enc")).contains("rotated-sdk"))
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `transient renewal failure retains seed and automatically retries without re-login`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext = renewableContext()))
        val attempts = AtomicInteger()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext) = storedSession().copy(browserContext = context)
        }
        val runtime = runtime(store, api, browserRenewal = {
            if (attempts.incrementAndGet() == 1) error("private-sdk-secret")
            renewableContext(true)
        })
        try {
            runtime.bootstrap()
            withTimeout(3000) { runtime.snapshot.first { it.error?.contains("renewal is temporarily") == true } }
            assertEquals("initial-sdk", store.twitchSession()?.browserContext?.sdkCookie?.value)
            assertFalse(runtime.snapshot.value.error.orEmpty().contains("private-sdk"))
            withTimeout(20000) { while (store.twitchSession()?.browserContext?.sdkCookie?.value != "rotated-sdk") delay(20) }
            assertEquals(2, attempts.get())
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `renewal account mismatch cannot replace the saved account or seed`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext = renewableContext()))
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext) = storedSession().copy(userId="other-account",browserContext=context)
        }
        val runtime = runtime(store, api, browserRenewal = { renewableContext(true) })
        try {
            runtime.bootstrap()
            withTimeout(3000) { runtime.snapshot.first { it.error?.contains("renewal is temporarily") == true } }
            assertEquals("12345",store.twitchSession()?.userId)
            assertEquals("initial-sdk",store.twitchSession()?.browserContext?.sdkCookie?.value)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `Stop during renewal preserves automatic renewal without resuming mining`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession().copy(browserContext=renewableContext()))
        val settings = SettingsRepository(directory)
        settings.update { it.copy(miningRequested=true) }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext) = storedSession().copy(browserContext=context)
        }
        val runtime = runtime(store,api,settings,browserRenewal={
            started.complete(Unit); release.await(); renewableContext(true)
        })
        try {
            runtime.bootstrap(); withTimeout(3000) { started.await() }
            runtime.stopMining(); runtime.stopMiningAndJoin()
            assertFalse(settings.settings.value.miningRequested)
            release.complete(Unit)
            withTimeout(3000) { while (store.twitchSession()?.browserContext?.sdkCookie?.value != "rotated-sdk") delay(10) }
            delay(50)
            assertFalse(runtime.snapshot.value.miningActive)
            assertFalse(settings.settings.value.miningRequested)
        } finally { release.complete(Unit); runtime.stopMiningAndJoin(shutdown=true) }
    }

    @Test fun `reset and replacement reject cancellation-insensitive renewal results`() = runBlocking {
        for (replace in listOf(false,true)) {
            val store = sessionStore()
            store.saveTwitchSession(storedSession().copy(browserContext = renewableContext()))
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
            val runtime = runtime(store, AuthenticationTwitchApi(), browserRenewal = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                returned.complete(Unit)
                renewableContext(true)
            })
            try {
                runtime.bootstrap(); withTimeout(3000) { started.await() }
                if (replace) runtime.startManagedBrowserAuthentication() else runtime.resetSessionAndJoin()
                release.complete(Unit); withTimeout(3000) { returned.await() }
                delay(50)
                if (replace) {
                    assertEquals("initial-sdk",store.twitchSession()?.browserContext?.sdkCookie?.value)
                    assertEquals(LoginState.LoginRequired,runtime.snapshot.value.account.state)
                } else {
                    assertNull(store.twitchSession()); assertEquals(LoginState.LoggedOut,runtime.snapshot.value.account.state)
                }
            } finally { release.complete(Unit); runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `late username lookup cannot restore identity after session reset`() = runBlocking {
        val store = sessionStore()
        store.saveTwitchSession(storedSession())
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val lookupReturned = CompletableDeferred<Unit>()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun fetchCampaignInventory(session: StoredTwitchSession) = CampaignInventory(emptyList())
            override suspend fun validateSession(session: StoredTwitchSession): ValidatedToken = withContext(NonCancellable) {
                lookupStarted.complete(Unit)
                releaseLookup.await()
                lookupReturned.complete(Unit)
                ValidatedToken(session.userId, "client", username = "previous_account")
            }
        }
        val runtime = runtime(store, api)
        try {
            runtime.bootstrap()
            withTimeout(2_000) { lookupStarted.await() }
            runtime.resetSession()
            withTimeout(2_000) { runtime.snapshot.first { it.account.state == LoginState.LoggedOut } }
            releaseLookup.complete(Unit)
            withTimeout(2_000) { lookupReturned.await() }
            runtime.stopMiningAndJoin()
            assertNull(runtime.snapshot.value.account.username)
            assertNull(store.twitchSession())
        } finally { releaseLookup.complete(Unit); runtime.stopMiningAndJoin() }
    }

    @Test fun `dashboard capture failure reports safe guidance and preserves saved credentials`(): Unit = runBlocking {
        val store = sessionStore()
        val saved = storedSession()
        store.saveTwitchSession(saved)
        val runtime = runtime(store, AuthenticationTwitchApi())
        val cancelled = AtomicBoolean()
        var viewId = ""
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (request.path == "/cancel") cancelled.set(true)
                if (request.path == "/start") viewId = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).let {
                    (it as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"')
                }
                return okhttp3.mockwebserver.MockResponse().setBody(if (request.path == "/status")
                    """{"id":"$viewId","state":"failed","sequence":0,"error":"capture_failed","detail":"secret-upstream-body"}""" else "{}")
            }
        }
        server.start()
        val bridge = app.twitchdockdrops.DashboardLogin(runtime, server.port)
        try {
            bridge.start(runtime.startManagedBrowserAuthentication())
            withTimeout(5000) { while (!cancelled.get()) delay(20) }
            val view = bridge.view().toString()
            assertTrue(view.contains("verified Drops access"))
            assertFalse(view.contains("secret-upstream-body"))
            assertEquals(saved.accessToken, store.twitchSession()?.accessToken)
        } finally { bridge.close(); server.close() }
    }

    @Test fun `dashboard bridge validates renewals redacts view and stops a revoked lease`(): Unit = runBlocking {
        val store = sessionStore()
        val validations = AtomicInteger()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                validations.incrementAndGet()
                return storedSession().copy(browserContext = context)
            }
            override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = emptyList()
        }
        val renew = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        val released = AtomicBoolean()
        val initial = BrowserSessionContext.parse(kotlinx.serialization.json.JsonObject(retainedContext().toJson() +
            ("expires_at" to kotlinx.serialization.json.JsonPrimitive(Instant.now().plusSeconds(120).epochSecond))))
        val runtime = runtime(store, api, browserRenewal={ renew.await(); retainedContext(true) },browserLeaseRevoke={
            assertEquals(initial.browserLease,it); cancelled.set(true)
        })
        val phase = AtomicReference("capturing")
        val sequence = AtomicInteger(1)
        val withContext = AtomicBoolean(true)
        var viewId = ""
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                assertEquals("1", request.getHeader("X-DockDrops-Internal"))
                val body = when (request.path) {
                    "/start" -> { viewId = (kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()) as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"'); "{}" }
                    "/status" -> """{"id":"$viewId","state":"${phase.get()}","sequence":${sequence.get()}${if (withContext.get()) ",\"context\":${initial.toJson()}" else ""}}"""
                    "/accepted" -> { assertTrue(withContext.get()); phase.set("ready"); withContext.set(false); "{}" }
                    "/cancel" -> { cancelled.set(true); "{}" }
                    "/release" -> { released.set(true); "{}" }
                    else -> "{}"
                }
                return okhttp3.mockwebserver.MockResponse().setBody(body)
            }
        }
        server.start()
        val bridge = app.twitchdockdrops.DashboardLogin(runtime, server.port)
        try {
            val ticket = runtime.startManagedBrowserAuthentication()
            bridge.start(ticket)
            withTimeout(5000) { while (!bridge.view().toString().contains("ready")) delay(20) }
            assertEquals(1, validations.get())
            assertFalse(bridge.view().toString().contains("test-token"))
            assertFalse(bridge.view().toString().contains(ticket))
            assertFalse(bridge.view().toString().contains(initial.browserLease!!))
            withTimeout(3000) { while (!released.get()) delay(10) }
            assertFalse(cancelled.get())
            renew.complete(Unit)
            withTimeout(8000) { while (validations.get() < 2 || store.twitchSession()?.browserContext?.headers?.get("client-integrity") != "new-proof") delay(20) }
            assertEquals(2, validations.get())
            runtime.resetSessionAndJoin()
            withTimeout(8000) { while (!cancelled.get()) delay(20) }
            assertNull(store.twitchSession())
        } finally { renew.complete(Unit); bridge.close(); runtime.stopMiningAndJoin(shutdown=true); server.close() }
    }

    @Test fun `managed login atomically owns its ticket and reset or replacement revokes it`(): Unit = runBlocking {
        val runtime = runtime(sessionStore(), AuthenticationTwitchApi())
        val first = runtime.startManagedBrowserAuthentication()
        assertEquals("connected", runtime.browserLoginStatus(first))
        assertEquals("dashboard", runtime.snapshot.value.account.method)
        assertNull(runtime.snapshot.value.account.oauthCode)
        val second = runtime.startManagedBrowserAuthentication()
        kotlin.test.assertFailsWith<IllegalArgumentException> { runtime.browserLoginStatus(first) }
        assertEquals("connected", runtime.browserLoginStatus(second))
        runtime.resetSessionAndJoin()
        kotlin.test.assertFailsWith<IllegalArgumentException> { runtime.browserLoginStatus(second) }
    }

    private fun browserContext(): BrowserSessionContext = BrowserSessionContext.parse(
        kotlinx.serialization.json.Json.parseToJsonElement("""{
          "version":1,"captured_at":${Instant.now().epochSecond},"expires_at":${Instant.now().plusSeconds(3600).epochSecond},
          "user_agent":"TestBrowser","headers":{"client-id":"kimne78kx3ncx6brgo4mv6wki5h1ko",
          "authorization":"OAuth test-token","client-integrity":"proof","x-device-id":"device"}}
        """) as kotlinx.serialization.json.JsonObject,
    )

    @Test fun `browser login commits validated sessions and renews only the same account`(): Unit = runBlocking {
        val store = sessionStore()
        var user = "12345"
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext) = storedSession().copy(userId=user,browserContext=context)
            override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = emptyList()
        }
        val runtime = runtime(store,api)
        runtime.startBrowserAuthentication()
        val code = withTimeout(2000) { runtime.snapshot.first { it.account.oauthCode != null }.account.oauthCode!! }
        val ticket = runtime.claimBrowserLogin(code)
        runtime.submitBrowserSession(ticket,browserContext())
        withTimeout(2000) { while (runtime.browserLoginStatus(ticket) != "ready") delay(10) }
        assertEquals("12345",store.twitchSession()?.userId)
        user = "67890"
        runtime.submitBrowserSession(ticket,browserContext())
        withTimeout(2000) { while (runtime.browserLoginStatus(ticket) != "failed") delay(10) }
        assertEquals("12345",store.twitchSession()?.userId)
        runtime.resetSessionAndJoin()
        assertNull(store.twitchSession())
        kotlin.test.assertFailsWith<IllegalArgumentException> { runtime.browserLoginStatus(ticket) }
    }

    @Test fun `reset invalidates a delayed browser verification before persistence`() = runBlocking {
        val store = sessionStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val api = object : TwitchApi by AuthenticationTwitchApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                finished.complete(Unit)
                return storedSession().copy(browserContext=context)
            }
        }
        val runtime = runtime(store,api)
        runtime.startBrowserAuthentication()
        val code = withTimeout(2000) { runtime.snapshot.first { it.account.oauthCode != null }.account.oauthCode!! }
        val ticket = runtime.claimBrowserLogin(code)
        runtime.submitBrowserSession(ticket,browserContext())
        started.await()
        runtime.resetSessionAndJoin()
        release.complete(Unit)
        finished.await()
        runtime.stopMiningAndJoin()
        assertNull(store.twitchSession())
        assertEquals(LoginState.LoggedOut,runtime.snapshot.value.account.state)
    }

    @Test
    fun `failed replacement authorization preserves the prior encrypted credential`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = AuthenticationTwitchApi(deviceCodeFailure = DeviceAuthorizationException(
            "device_authorization_rejected", "Device authorization rejected",
        ))
        val runtime = runtime(store, api)
        runtime.startAuthentication()
        withTimeout(2_000) { runtime.snapshot.first { it.currentTask == "Twitch login failed" } }
        assertEquals(storedSession(), store.twitchSession())
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `preserved credential cannot start work while replacement authorization is active`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = AuthenticationTwitchApi()
        val runtime = runtime(store, api)
        runtime.startAuthentication()
        withTimeout(2_000) { runtime.snapshot.first { it.account.oauthCode == "CODE-1" } }
        runtime.startMining()
        runtime.refreshInventory()
        // A joined stop is a command-queue barrier after the two requests above.
        runtime.stopMiningAndJoin()
        assertEquals("CODE-1", runtime.snapshot.value.account.oauthCode)
        assertEquals(storedSession(), store.twitchSession())
        assertFalse(runtime.snapshot.value.miningActive)
        runtime.resetSession()
        withTimeout(2_000) { runtime.snapshot.first { it.account.state == LoginState.LoggedOut } }
        assertNull(store.twitchSession())
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun `rapid duplicate starts create one mining loop`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ExecutionTwitchApi()
        val runtime = runtime(store, api)

        runtime.startMining()
        runtime.startMining()

        withTimeout(2_000) { api.validationStarted.await() }
        delay(100)
        assertEquals(1, api.validationCalls.get())
        runtime.stopMiningAndJoin()
        assertEquals(false, runtime.snapshot.value.miningActive)
    }

    @Test
    fun `session reset prevents a stale noncancellable refresh from committing`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ExecutionTwitchApi(blockInventory = true)
        val runtime = runtime(store, api)

        runtime.bootstrap()
        withTimeout(2_000) { api.inventoryStarted.await() }
        runtime.resetSessionAndJoin()
        withTimeout(2_000) {
            runtime.snapshot.first { it.currentTask == "Session reset" }
        }
        api.releaseInventory.complete(Unit)
        delay(100)

        assertNull(store.twitchSession())
        assertEquals(LoginState.LoggedOut, runtime.snapshot.value.account.state)
        assertEquals(emptyList(), runtime.snapshot.value.campaigns)
    }

    @Test
    fun `invalid token clears persisted session and stops mining`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ExecutionTwitchApi(invalidValidation = true)
        val runtime = runtime(store, api)

        runtime.startMining()
        val expired = withTimeout(2_000) {
            runtime.snapshot.first { it.account.state == LoginState.Expired }
        }

        assertEquals(false, expired.miningActive)
        assertNull(store.twitchSession())
    }

    @Test
    fun `active refresh requests are coalesced without concurrent inventory fetches`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ExecutionTwitchApi(blockInventory = true)
        val runtime = runtime(store, api)

        runtime.startMining()
        withTimeout(2_000) { api.inventoryStarted.await() }
        runtime.refreshInventory()
        runtime.refreshInventory()
        delay(100)
        assertEquals(1, api.inventoryCalls.get())
        assertEquals(1, api.maxConcurrentInventoryCalls.get())

        api.releaseInventory.complete(Unit)
        withTimeout(2_000) {
            while (api.inventoryCalls.get() < 2) delay(10)
        }
        assertEquals(1, api.maxConcurrentInventoryCalls.get())
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `unknown Twitch drop refreshes inventory immediately without a refresh loop`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val campaign = watchCampaign("known", "Known Game")
        val api = UnknownDropTwitchApi(campaign)
        val runtime = runtime(store, api)

        runtime.startMining()
        withTimeout(2_000) {
            while (api.inventoryCalls.get() < 2) delay(10)
        }
        delay(150)

        assertEquals(2, api.inventoryCalls.get())
        val refreshActivity = runtime.snapshot.value.activity.first { activity ->
            activity.title == "Refreshing inventory for Twitch-reported drop"
        }
        assertTrue(refreshActivity.detail?.contains("an unrecognized drop at 0m") == true)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `ordinary authentication start is idempotent and mining commands preserve device code`() = runBlocking {
        val api = AuthenticationTwitchApi()
        val runtime = runtime(sessionStore(), api)

        runtime.startAuthentication()
        runtime.startAuthentication()
        val codeState = withTimeout(2_000) {
            runtime.snapshot.first { it.account.oauthCode == "CODE-1" }
        }
        runtime.startMining()
        runtime.refreshInventory()
        delay(100)

        assertEquals(1, api.deviceCodeRequests.get())
        assertEquals(codeState.account.oauthCode, runtime.snapshot.value.account.oauthCode)
        assertEquals(codeState.account.oauthUrl, runtime.snapshot.value.account.oauthUrl)
        assertEquals(codeState.account.expiresAt, runtime.snapshot.value.account.expiresAt)
        runtime.resetSessionAndJoin()
    }

    @Test
    fun `explicit authentication replacement supersedes cancellation insensitive old request`() = runBlocking {
        val api = AuthenticationTwitchApi(blockFirstDeviceCode = true)
        val store = sessionStore()
        val runtime = runtime(store, api)

        runtime.startAuthentication()
        api.firstDeviceCodeStarted.await()
        runtime.replaceAuthentication()
        val replacement = withTimeout(2_000) {
            runtime.snapshot.first { it.account.oauthCode == "CODE-2" }
        }
        api.releaseFirstDeviceCode.complete(Unit)
        delay(100)

        assertEquals(2, api.deviceCodeRequests.get())
        assertEquals("CODE-2", replacement.account.oauthCode)
        assertEquals("CODE-2", runtime.snapshot.value.account.oauthCode)
        assertNull(store.twitchSession())
        runtime.resetSessionAndJoin()
    }

    @Test
    fun `stale authentication success cannot save credentials after replacement`() = runBlocking {
        val api = AuthenticationTwitchApi(blockFirstPoll = true)
        val store = sessionStore()
        val runtime = runtime(store, api)

        runtime.startAuthentication()
        withTimeout(2_000) { runtime.snapshot.first { it.account.oauthCode == "CODE-1" } }
        api.firstPollStarted.await()
        runtime.replaceAuthentication()
        withTimeout(2_000) { runtime.snapshot.first { it.account.oauthCode == "CODE-2" } }
        api.releaseFirstPoll.complete(Unit)
        delay(150)

        assertNull(store.twitchSession())
        assertEquals("CODE-2", runtime.snapshot.value.account.oauthCode)
        runtime.resetSessionAndJoin()
    }

    @Test
    fun `stale authentication failure cannot overwrite session reset`() = runBlocking {
        val api = AuthenticationTwitchApi(blockFirstDeviceCode = true, failFirstDeviceCode = true)
        val runtime = runtime(sessionStore(), api)

        runtime.startAuthentication()
        api.firstDeviceCodeStarted.await()
        runtime.resetSessionAndJoin()
        withTimeout(2_000) { runtime.snapshot.first { it.currentTask == "Session reset" } }
        api.releaseFirstDeviceCode.complete(Unit)
        delay(100)

        assertEquals(LoginState.LoggedOut, runtime.snapshot.value.account.state)
        assertEquals("Session reset", runtime.snapshot.value.currentTask)
        assertNull(runtime.snapshot.value.error)
    }

    @Test
    fun `terminal device authorization denial is surfaced without retrying`() = runBlocking {
        val api = AuthenticationTwitchApi(
            pollFailure = DeviceAuthorizationException(
                oauthError = "access_denied",
                message = "Twitch device authorization was denied.",
            ),
        )
        val runtime = runtime(sessionStore(), api)

        runtime.startAuthentication()
        val failed = withTimeout(3_000) {
            runtime.snapshot.first { snapshot ->
                snapshot.error?.contains("denied", ignoreCase = true) == true
            }
        }
        delay(150)

        assertEquals("Twitch login failed", failed.currentTask)
        assertEquals(1, api.pollCalls.get())
        runtime.resetSessionAndJoin()
    }

    @Test
    fun `inventory refresh never removes a saved absent game priority`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val settings = SettingsRepository(directory)
        settings.update { it.copy(selectedGamePriority = listOf("Saved Future Game")) }
        val api = ExecutionTwitchApi(campaigns = listOf(watchCampaign("other", "Other Game")))
        val runtime = runtime(store, api, settings)

        runtime.bootstrap()
        withTimeout(2_000) { runtime.snapshot.first { it.currentTask == "Inventory refreshed" } }

        assertEquals(listOf("Saved Future Game"), settings.settings.value.selectedGamePriority)
    }

    @Test
    fun `partial inventory preserves last known good campaign data`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val known = watchCampaign("known", "Known Game")
        val api = PartialInventoryTwitchApi(known)
        val runtime = runtime(store, api)

        runtime.bootstrap()
        withTimeout(2_000) { runtime.snapshot.first { it.campaigns.any { campaign -> campaign.id == "known" } } }
        runtime.refreshInventory()
        withTimeout(2_000) {
            while (api.inventoryCalls.get() < 2) delay(10)
            runtime.snapshot.first { it.error?.contains("partially parsed") == true }
        }

        assertEquals(listOf("known"), runtime.snapshot.value.campaigns.map { it.id })
    }

    @Test
    fun `partial campaign data retains previously known drops`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val firstDrop = watchCampaign("known", "Known Game").drops.single()
        val secondDrop = firstDrop.copy(id = "known-drop-2", name = "second drop", requiredMinutes = 90)
        val known = watchCampaign("known", "Known Game").copy(
            drops = listOf(firstDrop, secondDrop),
            totalDrops = 2,
        )
        val partial = known.copy(
            drops = listOf(firstDrop.copy(currentMinutes = 10, progress = 10f / 60f)),
            totalDrops = 1,
        )
        val api = PartialInventoryTwitchApi(known, partial)
        val runtime = runtime(store, api)

        runtime.bootstrap()
        withTimeout(2_000) { runtime.snapshot.first { it.campaigns.singleOrNull()?.drops?.size == 2 } }
        runtime.refreshInventory()
        withTimeout(2_000) {
            while (api.inventoryCalls.get() < 2) delay(10)
            runtime.snapshot.first { it.error?.contains("partially parsed") == true }
        }

        val merged = runtime.snapshot.value.campaigns.single()
        assertEquals(2, merged.drops.size)
        assertEquals(10, merged.drops.first { it.id == firstDrop.id }.currentMinutes)
        assertTrue(merged.drops.any { it.id == secondDrop.id })
    }

    @Test
    fun `miner promotes from second prioritized game when first becomes live`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val settings = SettingsRepository(directory)
        settings.update {
            it.copy(selectedGamePriority = listOf("Game One", "Game Two"))
        }
        val api = PromotionTwitchApi()
        val runtime = runtime(
            store = store,
            api = api,
            settings = settings,
            higherPriorityCheckInterval = Duration.ofMillis(25),
        )

        runtime.startMining()
        withTimeout(2_000) {
            runtime.snapshot.first { it.activeCampaign?.gameName == "Game Two" }
        }
        api.firstGameLive.set(true)
        val promoted = withTimeout(2_000) {
            runtime.snapshot.first { it.activeCampaign?.gameName == "Game One" }
        }

        assertEquals("Game One", promoted.activeCampaign?.gameName)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `access token is revalidated after one hour on the next inventory reload`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val clock = AtomicReference(Instant.parse("2026-08-27T12:00:00Z"))
        val api = RevalidationTwitchApi {
            clock.updateAndGet { instant -> instant.plus(Duration.ofMinutes(61)) }
        }
        val runtime = runtime(store, api, clock = clock::get)

        runtime.startMining()
        withTimeout(2_000) { api.firstInventoryLoaded.await() }
        runtime.refreshInventory()
        withTimeout(2_000) {
            while (api.validationCalls.get() < 2) delay(10)
        }

        assertEquals(2, api.validationCalls.get())
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `special campaign live recheck retains ACL participant across categories`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val channel = testChannel(5L, "participant", "broadcast", viewers = 100)
        val campaign = watchCampaign("special", "Special Events").copy(
            gameId = "509663", allowedChannels = listOf(channel),
        )
        val api = ChannelStatusTwitchApi(campaign, listOf(channel)) {
            channel.copy(game = "Another category", gameId = "123", dropsEnabled = false)
        }
        val runtime = runtime(store, api, channelStatusCheckInterval = Duration.ZERO)
        runtime.startMining()
        val refreshed = withTimeout(2_000) {
            runtime.snapshot.first { it.currentChannel?.gameId == "123" }
        }
        assertEquals(channel.id, refreshed.currentChannel?.id)
        assertTrue(refreshed.currentChannel?.dropsEnabled == true)
        assertFalse(refreshed.activity.any { it.title == "Channel changed category" })
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `offline channel status recheck skips the channel and selects another`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val campaign = watchCampaign("status", "Status Game")
        val first = testChannel(1L, "first", "old-broadcast", viewers = 100)
        val second = testChannel(2L, "second", "second-broadcast", viewers = 50)
        val api = ChannelStatusTwitchApi(campaign, listOf(first, second)) { login ->
            if (login == first.login) first.copy(online = false) else second
        }
        val runtime = runtime(
            store,
            api,
            channelStatusCheckInterval = Duration.ofMillis(25),
        )

        runtime.startMining()
        val recovered = withTimeout(2_000) {
            runtime.snapshot.first { snapshot ->
                snapshot.currentChannel?.id == second.id &&
                    snapshot.activity.any { it.title == "Channel went offline" }
            }
        }

        assertEquals(second.id, recovered.currentChannel?.id)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `unchanged channel status recheck does not update the snapshot`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val campaign = watchCampaign("quiet-status", "Status Game")
        val channel = testChannel(5L, "steady", "steady-broadcast", viewers = 100)
        val statusChecked = CompletableDeferred<Unit>()
        val api = ChannelStatusTwitchApi(campaign, listOf(channel)) {
            statusChecked.complete(Unit)
            channel
        }
        val runtime = runtime(
            store,
            api,
            channelStatusCheckInterval = Duration.ofMillis(500),
        )

        runtime.startMining()
        withTimeout(2_000) {
            while (api.watchedBroadcastIds.isEmpty()) delay(10)
        }
        delay(50)
        val lastUpdateBeforeRecheck = runtime.snapshot.value.lastUpdate
        withTimeout(2_000) { statusChecked.await() }
        delay(50)

        assertEquals(lastUpdateBeforeRecheck, runtime.snapshot.value.lastUpdate)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `stream restart refreshes broadcast id before the next watch minute`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val campaign = watchCampaign("restart", "Restart Game")
        val original = testChannel(10L, "restartable", "old-broadcast", viewers = 100)
        val api = ChannelStatusTwitchApi(campaign, listOf(original)) {
            original.copy(
                broadcastId = "new-broadcast",
                title = "Restarted stream",
                viewers = 125,
            )
        }
        val runtime = runtime(
            store,
            api,
            channelStatusCheckInterval = Duration.ZERO,
        )

        runtime.startMining()
        withTimeout(2_000) {
            while (api.watchedBroadcastIds.isEmpty()) delay(10)
        }

        assertEquals("new-broadcast", api.watchedBroadcastIds.first())
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `ambiguous claim watches other work and retries only after fresh unclaimed evidence`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ClaimRetryRuntimeTwitchApi()
        val runtime = runtime(
            store = store,
            api = api,
            claimFailureCooldown = Duration.ofMillis(100),
        )

        runtime.startMining()
        val usefulWork = withTimeout(2_000) {
            runtime.snapshot.first { it.activeCampaign?.id == "watch-campaign" && it.currentChannel != null }
        }
        withTimeout(2_000) {
            while (api.claimCalls.get() < 2) delay(10)
        }

        assertEquals("watch-campaign", usefulWork.activeCampaign?.id)
        assertEquals(2, api.claimCalls.get())
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `session reset prevents stale noncancellable claim from committing`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ClaimRetryRuntimeTwitchApi(blockFirstClaim = true)
        val runtime = runtime(store, api, claimFailureCooldown = Duration.ofMillis(50))

        runtime.startMining()
        api.firstClaimStarted.await()
        runtime.resetSessionAndJoin()
        withTimeout(2_000) { runtime.snapshot.first { it.currentTask == "Session reset" } }
        api.releaseFirstClaim.complete(Unit)
        delay(100)

        assertEquals(LoginState.LoggedOut, runtime.snapshot.value.account.state)
        assertEquals(0, runtime.snapshot.value.dropsClaimedThisSession)
        assertEquals(emptyList(), runtime.snapshot.value.campaigns)
        assertNull(store.twitchSession())
    }

    @Test
    fun `stop waits for cancellation insensitive work and then completes deterministically`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = ClaimRetryRuntimeTwitchApi(blockFirstClaim = true)
        val runtime = runtime(store, api)

        runtime.startMining()
        api.firstClaimStarted.await()
        val stopping = async { runtime.stopMiningAndJoin() }
        delay(50)
        assertEquals(false, stopping.isCompleted)
        api.releaseFirstClaim.complete(Unit)
        withTimeout(2_000) { stopping.await() }

        assertEquals(false, runtime.snapshot.value.miningActive)
        assertEquals("Local miner stopped", runtime.snapshot.value.currentTask)
    }

    @Test
    fun `invalid token during claim still expires and clears session`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val runtime = runtime(store, ClaimRetryRuntimeTwitchApi(invalidFirstClaim = true))

        runtime.startMining()
        val expired = withTimeout(2_000) {
            runtime.snapshot.first { it.account.state == LoginState.Expired }
        }

        assertEquals(false, expired.miningActive)
        assertNull(store.twitchSession())
    }

    @Test
    fun `first candidate discovery failure does not block a later healthy campaign`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = CandidateFailureTwitchApi(
            listOf(watchCampaign("first", "First Game"), watchCampaign("second", "Second Game")),
        )
        val runtime = runtime(store, api)

        runtime.startMining()
        val watching = withTimeout(2_000) {
            runtime.snapshot.first { it.activeCampaign?.id == "second" && it.currentChannel != null }
        }

        assertEquals("second", watching.activeCampaign?.id)
        assertEquals(listOf("first", "second"), api.channelAttempts)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `watch endpoint authorization rejection does not clear a valid stored session`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = CandidateFailureTwitchApi(
            campaigns = listOf(watchCampaign("watch", "Watch Game")),
            watchFailure = TwitchApiException(TwitchApiErrorType.Http, "watch configuration rejected with 403"),
        )
        val runtime = runtime(store, api)

        runtime.startMining()
        withTimeout(2_000) { api.watchAttempted.await() }
        runtime.stopMiningAndJoin()

        assertEquals(storedSession(), store.twitchSession())
    }

    @Test
    fun `advisory false negative network probe does not block working http traffic`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = CandidateFailureTwitchApi(listOf(watchCampaign("proxy", "Proxy Game")))
        val runtime = LocalMinerRuntime(
            SettingsRepository(directory),
            store,
            LogRepository(directory),
            api,
            AdvisoryOfflineNetwork,
        )

        runtime.startMining()
        val watching = withTimeout(2_000) {
            runtime.snapshot.first { it.currentChannel != null }
        }

        assertEquals("proxy", watching.activeCampaign?.id)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `authoritative offline state pauses and resumes cleanly after network recovery`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val api = CandidateFailureTwitchApi(listOf(watchCampaign("online", "Online Game")))
        val network = MutableExecutionNetwork(false)
        val runtime = LocalMinerRuntime(
            SettingsRepository(directory),
            store,
            LogRepository(directory),
            api,
            network,
        )

        runtime.startMining()
        withTimeout(2_000) { runtime.snapshot.first { it.currentTask == "Waiting for internet connection" } }
        assertEquals(emptyList(), api.channelAttempts)
        network.online.value = true
        val recovered = withTimeout(2_000) { runtime.snapshot.first { it.currentChannel != null } }

        assertEquals("online", recovered.activeCampaign?.id)
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `verbose setting emits bounded debug events without secrets`() = runBlocking {
        val store = sessionStore().also { it.saveTwitchSession(storedSession()) }
        val settings = SettingsRepository(directory)
        val logs = LogRepository(directory)
        val quietApi = CandidateFailureTwitchApi(listOf(watchCampaign("quiet", "Quiet Game")))
        val quietRuntime = LocalMinerRuntime(settings, store, logs, quietApi, AlwaysOnlineForExecutionTests)

        quietRuntime.startMining()
        withTimeout(2_000) { quietApi.watchAttempted.await() }
        quietRuntime.stopMiningAndJoin()
        assertEquals(false, logs.entries.value.any { it.level == "DEBUG" })

        logs.clear()
        settings.update { it.copy(debugLogging = true) }
        val api = CandidateFailureTwitchApi(listOf(watchCampaign("verbose", "Verbose Game")))
        val runtime = LocalMinerRuntime(settings, store, logs, api, AlwaysOnlineForExecutionTests)

        runtime.startMining()
        withTimeout(2_000) { api.watchAttempted.await() }
        runtime.stopMiningAndJoin()

        assertTrue(logs.entries.value.any { it.level == "DEBUG" })
        val text = logs.visibleText()
        assertEquals(false, text.contains(storedSession().accessToken))
        assertEquals(false, text.contains(storedSession().deviceId))
    }

    private fun runtime(
        store: SecureSessionStore,
        api: TwitchApi,
        settings: SettingsRepository = SettingsRepository(directory),
        claimFailureCooldown: Duration = DefaultClaimFailureCooldown,
        higherPriorityCheckInterval: Duration = Duration.ofMinutes(2),
        channelStatusCheckInterval: Duration = Duration.ofMinutes(3),
        clock: () -> Instant = Instant::now,
        browserRenewal: (suspend (BrowserSessionContext) -> BrowserSessionContext)? = null,
        browserLeaseRevoke: (suspend (String) -> Unit)? = null,
        tvAuthenticationApi: TwitchApi? = null,
    ) = LocalMinerRuntime(
        settingsRepository = settings,
        secureSessionStore = store,
        logRepository = LogRepository(directory),
        twitchApiClient = api,
        networkStatusProvider = AlwaysOnlineForExecutionTests,
        claimFailureCooldown = claimFailureCooldown,
        higherPriorityCheckInterval = higherPriorityCheckInterval,
        channelStatusCheckInterval = channelStatusCheckInterval,
        clock = clock,
        browserRenewal = browserRenewal,
        browserRenewalMinimumDelay = Duration.ofMillis(10),
        browserLeaseRevoke = browserLeaseRevoke,
        tvAuthenticationApi = tvAuthenticationApi,
    )

    private fun sessionStore(): SecureSessionStore {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { index -> index.toByte() })
        return SecureSessionStore(directory, key)
    }

    private fun storedSession() = StoredTwitchSession(
        accessToken = "test-token",
        userId = "12345",
        deviceId = "device-1",
        savedAt = Instant.parse("2026-08-12T00:00:00Z"),
    )

    private fun watchCampaign(id: String, gameName: String) = Campaign(
        id = id,
        name = id,
        gameName = gameName,
        linked = true,
        active = true,
        drops = listOf(
            CampaignDrop(
                id = "$id-drop",
                name = "$id drop",
                currentMinutes = 0,
                requiredMinutes = 60,
                progress = 0f,
                isClaimed = false,
                canClaim = false,
                rewards = emptyList(),
            ),
        ),
        totalDrops = 1,
    )

    private fun testChannel(
        id: Long,
        login: String,
        broadcastId: String,
        viewers: Int,
    ) = Channel(
        id = id,
        name = login,
        login = login,
        game = "Status Game",
        viewers = viewers,
        online = true,
        dropsEnabled = true,
        broadcastId = broadcastId,
        gameId = "game-id",
        title = "Live stream",
    )
}

private object AlwaysOnlineForExecutionTests : NetworkStatusProvider {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
}

private object AdvisoryOfflineNetwork : NetworkStatusProvider {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(false)
    override val advisoryOnly: Boolean = true
}

private class MutableExecutionNetwork(initiallyOnline: Boolean) : NetworkStatusProvider {
    val online = MutableStateFlow(initiallyOnline)
    override val isOnline: StateFlow<Boolean> = online
}

private class CandidateFailureTwitchApi(
    private val campaigns: List<Campaign>,
    private val watchFailure: TwitchApiException? = null,
) : TwitchApi {
    val channelAttempts = mutableListOf<String>()
    val watchAttempted = CompletableDeferred<Unit>()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        ValidatedToken("12345", "client")

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = campaigns

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> {
        channelAttempts += campaign.id
        if (campaign.id == "first") {
            throw TwitchApiException(TwitchApiErrorType.Network, "candidate unavailable")
        }
        return listOf(
            Channel(
                id = campaign.id.hashCode().toLong().let { if (it == 0L) 1L else kotlin.math.abs(it) },
                name = "channel-${campaign.id}",
                game = campaign.gameName,
                online = true,
                dropsEnabled = true,
                broadcastId = "broadcast-${campaign.id}",
            ),
        )
    }

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean {
        watchAttempted.complete(Unit)
        watchFailure?.let { throw it }
        return true
    }

    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = null
    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class PartialInventoryTwitchApi(
    private val knownCampaign: Campaign,
    private val partialCampaign: Campaign? = null,
) : TwitchApi {
    val inventoryCalls = AtomicInteger()

    override suspend fun fetchCampaignInventory(session: StoredTwitchSession): CampaignInventory =
        if (inventoryCalls.incrementAndGet() == 1) {
            CampaignInventory(listOf(knownCampaign))
        } else {
            CampaignInventory(
                campaigns = listOfNotNull(partialCampaign),
                sourceRecordCount = 1,
                diagnostics = listOf("Campaign record could not be parsed safely."),
            )
        }

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = unused()
    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun validateAccessToken(accessToken: String): ValidatedToken = unused()
    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = unused()
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class UnknownDropTwitchApi(
    private val campaign: Campaign,
) : TwitchApi {
    val inventoryCalls = AtomicInteger()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        ValidatedToken("12345", "client")

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> {
        inventoryCalls.incrementAndGet()
        return listOf(campaign)
    }

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> = listOf(
        Channel(
            id = 42,
            name = "channel-known",
            game = campaign.gameName,
            online = true,
            dropsEnabled = true,
            broadcastId = "broadcast-known",
        ),
    )

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = true

    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress =
        CurrentDropProgress(dropId = "", currentMinutes = 0)

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class ExecutionTwitchApi(
    private val invalidValidation: Boolean = false,
    private val blockInventory: Boolean = false,
    private val campaigns: List<Campaign> = emptyList(),
) : TwitchApi {
    val validationCalls = AtomicInteger()
    val inventoryCalls = AtomicInteger()
    val maxConcurrentInventoryCalls = AtomicInteger()
    val validationStarted = CompletableDeferred<Unit>()
    val inventoryStarted = CompletableDeferred<Unit>()
    val releaseInventory = CompletableDeferred<Unit>()
    private val activeInventoryCalls = AtomicInteger()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken {
        validationCalls.incrementAndGet()
        validationStarted.complete(Unit)
        if (invalidValidation) {
            throw TwitchApiException(TwitchApiErrorType.InvalidToken, "Expired test token")
        }
        return ValidatedToken("12345", "client")
    }

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> {
        inventoryCalls.incrementAndGet()
        val active = activeInventoryCalls.incrementAndGet()
        maxConcurrentInventoryCalls.updateAndGet { previous -> maxOf(previous, active) }
        inventoryStarted.complete(Unit)
        return try {
            if (blockInventory && inventoryCalls.get() == 1) {
                withContext(NonCancellable) { releaseInventory.await() }
            }
            campaigns
        } finally {
            activeInventoryCalls.decrementAndGet()
        }
    }

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> = emptyList()
    override suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String?,
    ): Channel = unused()
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = unused()
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device-new"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class RevalidationTwitchApi(
    private val afterFirstInventory: () -> Unit,
) : TwitchApi {
    val validationCalls = AtomicInteger()
    val firstInventoryLoaded = CompletableDeferred<Unit>()
    private val inventoryCalls = AtomicInteger()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken {
        validationCalls.incrementAndGet()
        return ValidatedToken("12345", "client")
    }

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> {
        if (inventoryCalls.incrementAndGet() == 1) {
            afterFirstInventory()
            firstInventoryLoaded.complete(Unit)
        }
        return emptyList()
    }

    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> = emptyList()
    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = unused()
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class ChannelStatusTwitchApi(
    private val campaign: Campaign,
    private val channels: List<Channel>,
    private val channelStatus: (String) -> Channel,
) : TwitchApi {
    val watchedBroadcastIds = CopyOnWriteArrayList<String?>()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        ValidatedToken("12345", "client")

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = listOf(campaign)

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> = channels

    override suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String?,
    ): Channel = channelStatus(login)

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean {
        watchedBroadcastIds += channel.broadcastId
        return true
    }

    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress =
        CurrentDropProgress("${campaign.id}-drop", 0)

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class AuthenticationTwitchApi(
    private val blockFirstDeviceCode: Boolean = false,
    private val failFirstDeviceCode: Boolean = false,
    private val blockFirstPoll: Boolean = false,
    private val pollFailure: Throwable? = null,
    private val deviceCodeFailure: Throwable? = null,
) : TwitchApi {
    val deviceCodeRequests = AtomicInteger()
    val firstDeviceCodeStarted = CompletableDeferred<Unit>()
    val releaseFirstDeviceCode = CompletableDeferred<Unit>()
    val firstPollStarted = CompletableDeferred<Unit>()
    val releaseFirstPoll = CompletableDeferred<Unit>()
    val pollCalls = AtomicInteger()

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization {
        deviceCodeFailure?.let { throw it }
        val request = deviceCodeRequests.incrementAndGet()
        if (request == 1) {
            firstDeviceCodeStarted.complete(Unit)
            if (blockFirstDeviceCode) {
                withContext(NonCancellable) { releaseFirstDeviceCode.await() }
            }
            if (failFirstDeviceCode) {
                throw IllegalStateException("stale device-code failure")
            }
        }
        return DeviceAuthorization(
            deviceCode = "device-code-$request",
            userCode = "CODE-$request",
            verificationUri = "https://www.twitch.tv/activate",
            expiresAt = Instant.now().plusSeconds(3_600),
            intervalSeconds = 1,
        )
    }

    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult {
        pollCalls.incrementAndGet()
        if (deviceCode == "device-code-1" && blockFirstPoll) {
            firstPollStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirstPoll.await() }
            return DeviceTokenPollResult.Authorized(TokenResponse("stale-token"))
        }
        pollFailure?.let { throw it }
        return DeviceTokenPollResult.AuthorizationPending
    }

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        ValidatedToken("stale-user", "client")

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = unused()
    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = unused()
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "new-device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class PromotionTwitchApi : TwitchApi {
    val firstGameLive = AtomicBoolean(false)
    private val firstCampaign = campaign("first", "Game One")
    private val secondCampaign = campaign("second", "Game Two")

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        ValidatedToken("12345", "client")

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> =
        listOf(firstCampaign, secondCampaign)

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> = when (campaign.id) {
        "first" -> if (firstGameLive.get()) listOf(channel(1, "one", "first")) else emptyList()
        "second" -> listOf(channel(2, "two", "second"))
        else -> emptyList()
    }

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = true

    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress =
        CurrentDropProgress(
            dropId = if (channelId == 1L) "first-drop" else "second-drop",
            currentMinutes = 0,
        )

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()
    override fun newDeviceId(): String = "device"

    private fun campaign(id: String, game: String) = Campaign(
        id = id,
        name = id,
        gameName = game,
        linked = true,
        active = true,
        drops = listOf(
            CampaignDrop(
                id = "$id-drop",
                name = "$id drop",
                currentMinutes = 0,
                requiredMinutes = 60,
                progress = 0f,
                isClaimed = false,
                canClaim = false,
                rewards = emptyList(),
            ),
        ),
        totalDrops = 1,
    )

    private fun channel(id: Long, name: String, campaign: String) = Channel(
        id = id,
        name = name,
        online = true,
        dropsEnabled = true,
        broadcastId = "$campaign-broadcast",
    )

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}

private class ClaimRetryRuntimeTwitchApi(
    private val blockFirstClaim: Boolean = false,
    private val invalidFirstClaim: Boolean = false,
) : TwitchApi {
    val claimCalls = AtomicInteger()
    val firstClaimStarted = CompletableDeferred<Unit>()
    val releaseFirstClaim = CompletableDeferred<Unit>()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken =
        ValidatedToken("12345", "client")

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = listOf(
        Campaign(
            id = "claim-campaign",
            name = "claim campaign",
            gameName = "Claim Game",
            linked = false,
            linkStatusKnown = true,
            linkUrl = "https://example.test/link",
            active = true,
            drops = listOf(
                CampaignDrop(
                    id = "claim-drop",
                    name = "claim drop",
                    currentMinutes = 60,
                    requiredMinutes = 60,
                    progress = 1f,
                    isClaimed = false,
                    canClaim = true,
                    rewards = emptyList(),
                    claimId = "claim-id",
                    claimEvidenceKnown = true,
                ),
            ),
            totalDrops = 1,
        ),
        Campaign(
            id = "watch-campaign",
            name = "watch campaign",
            gameName = "Watch Game",
            linked = true,
            active = true,
            drops = listOf(
                CampaignDrop(
                    id = "watch-drop",
                    name = "watch drop",
                    currentMinutes = 0,
                    requiredMinutes = 60,
                    progress = 0f,
                    isClaimed = false,
                    canClaim = false,
                    rewards = emptyList(),
                ),
            ),
            totalDrops = 1,
        ),
    )

    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult {
        val call = claimCalls.incrementAndGet()
        if (call == 1) {
            firstClaimStarted.complete(Unit)
            if (invalidFirstClaim) {
                throw TwitchApiException(TwitchApiErrorType.InvalidToken, "expired claim token")
            }
            if (blockFirstClaim) {
                withContext(NonCancellable) { releaseFirstClaim.await() }
                return DropClaimResult(DropClaimOutcome.Claimed)
            }
            throw TwitchApiException(TwitchApiErrorType.Network, "temporary claim failure")
        }
        return DropClaimResult(DropClaimOutcome.Claimed)
    }

    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> =
        if (campaign.id == "watch-campaign") {
            listOf(
                Channel(
                    id = 22,
                    name = "watcher",
                    online = true,
                    dropsEnabled = true,
                    broadcastId = "broadcast",
                ),
            )
        } else {
            emptyList()
        }

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = true
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress =
        CurrentDropProgress("watch-drop", 0)

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}
