package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.local.*
import com.nathan.twitchdropsminer.android.data.model.*
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.io.TempDir

class BrowserRenewalRuntimeTest {
    @TempDir lateinit var directory: Path
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private fun store() = SecureSessionStore(directory, key)
    private fun context(renewed: Boolean = false): BrowserSessionContext {
        val time = Instant.now().epochSecond
        return BrowserSessionContext.parse(buildJsonObject {
            put("version", 1)
            put("captured_at", if (renewed) time else time - 7200)
            put("expires_at", if (renewed) time + 3600 else time - 3600)
            put("user_agent", "FixtureBrowser")
            put("headers", buildJsonObject {
                put("authorization", "OAuth fixture-token"); put("client-id", TwitchWebClientId)
                put("client-integrity", if (renewed) "fresh-proof" else "expired-proof")
                put("device-id", "fixture-device"); put("client-session-id", "fixture-session")
                put("client-version", "fixture-version"); put("accept-language", "en-US")
            })
            put("sdk_cookie", buildJsonObject {
                put("value", if (renewed) "fresh-cookie" else "expired-cookie")
                put("expires_at", if (renewed) time + 86400 else time - 1800)
            })
        })
    }
    private fun session(context: BrowserSessionContext = context()) = StoredTwitchSession(
        context.accessToken, "12345", context.deviceId, Instant.now(), context, username = "fixture_account")
    private fun runtime(
        store: SecureSessionStore, api: TwitchApi,
        settings: SettingsRepository = SettingsRepository(directory),
        logs: LogRepository = LogRepository(directory),
        clock: () -> Instant = Instant::now,
        revoke: (suspend (String) -> Unit)? = null,
        renew: suspend (BrowserSessionContext, String) -> BrowserSessionContext,
    ) = LocalMinerRuntime(settings, store, logs, api, object : NetworkStatusProvider {
        override val isOnline = MutableStateFlow(true)
    }, clock = clock, browserRenewal = renew, browserLeaseRevoke = revoke,
        browserRenewalMinimumDelay = Duration.ofMillis(10))
    private fun wireClient(server: MockWebServer) = TwitchApiClient(OkHttpClient(),
        gqlEndpoint = server.url("/gql").toString(), oauthBaseUrl = server.url("/").toString())
    private suspend fun awaitFailure(runtime: LocalMinerRuntime) = withTimeout(3000) {
        runtime.snapshot.first { it.error?.contains("renewal is temporarily unavailable") == true }
    }
    private suspend fun awaitSaved(store: SecureSessionStore) = withTimeout(3000) {
        while (store.twitchSession()?.browserContext?.headers?.get("client-integrity") != "fresh-proof") delay(10)
    }
    private fun identity() = ValidatedToken("12345", TwitchWebClientId, username = "fixture_account")

    @Test fun `expired cookie restores from encrypted disk only after saved identity validation`() = runBlocking {
        store().saveTwitchSession(session())
        val restored = store()
        val order = mutableListOf<String>()
        val api = object : TwitchApi by RenewalFixtureApi() {
            override suspend fun validateSession(session: StoredTwitchSession): ValidatedToken {
                order += "identity"; assertFalse(session.browserContext!!.sdkCookie!!.expiresAt.isAfter(Instant.now()))
                return identity()
            }
            override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                order += "catalog"; return session(context)
            }
        }
        val runtime = runtime(restored, api) { previous, account ->
            order += "issue"; assertEquals("12345", account)
            assertEquals("expired-cookie", previous.sdkCookie?.value); context(true)
        }
        try {
            runtime.bootstrap(); awaitSaved(restored)
            assertEquals(listOf("identity", "issue", "catalog"), order)
            assertEquals("fresh-cookie", store().twitchSession()?.browserContext?.sdkCookie?.value)
            assertFalse(Files.readString(directory.resolve("session.enc")).contains("fresh-cookie"))
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `restored retained lease is revoked before renewal is due on shutdown reset or replacement`() = runBlocking {
        val lease = "12345678-1234-1234-1234-123456789abc"
        for (action in listOf("shutdown", "reset", "replace")) {
            val retained = BrowserSessionContext.parse(JsonObject(context(true).toJson() - "sdk_cookie" +
                ("browser_lease" to JsonPrimitive(lease))))
            val store = store().also { it.saveTwitchSession(session(retained)) }
            val original = Files.readAllBytes(directory.resolve("session.enc"))
            val revoked = mutableListOf<String>()
            val runtime = runtime(store, RenewalFixtureApi(), revoke = { revoked += it }) { _, _ ->
                error("Renewal is not due in this fixture")
            }
            try {
                runtime.bootstrap()
                runtime.stopMiningAndJoin()
                assertTrue(revoked.isEmpty(), "Ordinary Stop must retain the browser")
                when (action) {
                    "shutdown" -> runtime.stopMiningAndJoin(shutdown = true)
                    "reset" -> runtime.resetSessionAndJoin()
                    else -> runtime.startManagedBrowserAuthentication()
                }
                assertEquals(listOf(lease), revoked, action)
                if (action == "reset") assertNull(store.twitchSession())
                else assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")), action)
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
            assertEquals(listOf(lease), revoked, "A released lease must not be revoked twice")
        }
    }

    @Test fun `normal renewal waits for the five minute lead while proof is still usable`() = runBlocking {
        val time = Instant.now().epochSecond
        val before = BrowserSessionContext.parse(JsonObject(context().toJson() + mapOf(
            "captured_at" to JsonPrimitive(time - 1000),
            "expires_at" to JsonPrimitive(time + 600),
            "sdk_cookie" to buildJsonObject { put("value", "valid-cookie"); put("expires_at", time + 1800) },
        )))
        // This clock positions the existing scheduler one second before its lead deadline.
        // It is synthetic scheduling evidence, not an unattended Twitch expiry observation.
        val clock = { before.expiresAt.minusSeconds(301) }
        val store = store().also { it.saveTwitchSession(session(before)) }
        val issued = CompletableDeferred<Unit>()
        val runtime = runtime(store, RenewalFixtureApi(), clock = clock) { previous, account ->
            previous.requireFresh(clock())
            assertEquals("12345", account)
            issued.complete(Unit)
            context(true)
        }
        try {
            runtime.bootstrap()
            assertNull(withTimeoutOrNull(250) { issued.await() })
            withTimeout(3000) { issued.await() }
            awaitSaved(store)
            assertEquals("fresh-cookie", store().twitchSession()?.browserContext?.sdkCookie?.value)
            assertFalse(runtime.snapshot.value.miningActive)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `replayed nonadvancing expired or changed identity proof never reaches catalog or storage`() = runBlocking {
        for (variation in listOf("replayed", "expiry", "capture", "expired", "oauth", "device")) {
            val time = Instant.now().epochSecond
            val before = BrowserSessionContext.parse(JsonObject(context().toJson() + mapOf(
                "captured_at" to JsonPrimitive(time - 180),
                "expires_at" to JsonPrimitive(time + 180),
                "sdk_cookie" to buildJsonObject { put("value", "valid-cookie"); put("expires_at", time + 3600) },
            )))
            val raw = context(true).toJson()
            val headers = raw.getValue("headers").jsonObject
            val changed = when (variation) {
                "replayed" -> JsonObject(raw + ("headers" to JsonObject(headers +
                    ("client-integrity" to JsonPrimitive(before.headers.getValue("client-integrity"))))))
                "expiry" -> JsonObject(raw + ("expires_at" to JsonPrimitive(before.expiresAt.epochSecond)))
                "capture" -> JsonObject(raw + ("captured_at" to JsonPrimitive(before.capturedAt.epochSecond)))
                "expired" -> JsonObject(raw + mapOf(
                    "captured_at" to JsonPrimitive(time - 120), "expires_at" to JsonPrimitive(time - 30)))
                "oauth" -> JsonObject(raw + ("headers" to JsonObject(headers +
                    ("authorization" to JsonPrimitive("OAuth other-fixture-token")))))
                else -> JsonObject(raw + ("headers" to JsonObject(headers +
                    ("device-id" to JsonPrimitive("other-fixture-device")))))
            }
            val store = store().also { it.saveTwitchSession(session(before)) }
            val original = Files.readAllBytes(directory.resolve("session.enc"))
            val catalogCalls = AtomicInteger()
            val api = object : TwitchApi by RenewalFixtureApi() {
                override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                    catalogCalls.incrementAndGet()
                    return session(context)
                }
            }
            val runtime = runtime(store, api) { _, _ -> BrowserSessionContext.parse(changed) }
            try {
                runtime.bootstrap(); awaitFailure(runtime)
                assertEquals(0, catalogCalls.get(), variation)
                assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")), variation)
                assertFalse(runtime.snapshot.value.miningActive)
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `authoritative OAuth 401 invalidates restored session before browser issuance`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-upstream-body")); server.start()
            val store = store().also { it.saveTwitchSession(session()) }
            val wire = wireClient(server)
            val calls = AtomicInteger()
            val api = object : TwitchApi by RenewalFixtureApi() {
                override suspend fun validateSession(session: StoredTwitchSession) = wire.validateSession(session)
            }
            val runtime = runtime(store, api) { _, _ -> calls.incrementAndGet(); context(true) }
            try {
                runtime.bootstrap()
                withTimeout(3000) { runtime.snapshot.first { it.account.state == LoginState.Expired } }
                assertNull(store.twitchSession()); assertEquals(0, calls.get())
                assertEquals("/oauth2/validate", server.takeRequest().path)
                assertEquals(1, server.requestCount)
                assertFalse(runtime.snapshot.value.error.orEmpty().contains("private-upstream"))
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `inconclusive OAuth responses preserve exact encrypted bytes without starting a browser`() = runBlocking {
        val replies = listOf(
            MockResponse().setResponseCode(503).setBody("private-network-body"),
            MockResponse().setBody("{malformed-private-token"),
            MockResponse().setBody("""{"client_id":"$TwitchWebClientId","user_id":"98765"}"""),
            MockResponse().setBody("""{"client_id":"wrong-client","user_id":"12345"}"""),
        )
        for (reply in replies) MockWebServer().use { server ->
            server.enqueue(reply); server.start()
            val store = store().also { it.saveTwitchSession(session()) }
            val original = Files.readAllBytes(directory.resolve("session.enc"))
            val wire = wireClient(server)
            val calls = AtomicInteger()
            val logs = LogRepository(directory)
            val api = object : TwitchApi by RenewalFixtureApi() {
                override suspend fun validateSession(session: StoredTwitchSession) = wire.validateSession(session)
            }
            val runtime = runtime(store, api, logs = logs) { _, _ -> calls.incrementAndGet(); context(true) }
            try {
                runtime.bootstrap(); awaitFailure(runtime); delay(75)
                assertEquals(0, calls.get()); assertEquals(1, server.requestCount)
                assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")))
                assertFalse((runtime.snapshot.value.error.orEmpty() + logs.visibleText()).contains("private-"))
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `failed malformed and errored catalog cannot replace expired seed`() = runBlocking {
        val catalogReplies = listOf(
            """{"data":{"currentUser":null}}""",
            """{"data":{"currentUser":{"dropCampaigns":{}}}}""",
            """{"data":{"currentUser":{"dropCampaigns":[]}},"errors":[{"message":"private-catalog-error"}]}""",
        )
        for (catalog in catalogReplies) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"client_id":"$TwitchWebClientId","user_id":"12345"}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"currentUser":{"inventory":{}}}}"""))
            server.enqueue(MockResponse().setBody(catalog)); server.start()
            val store = store().also { it.saveTwitchSession(session()) }
            val original = Files.readAllBytes(directory.resolve("session.enc"))
            val wire = wireClient(server)
            val calls = AtomicInteger()
            val api = object : TwitchApi by RenewalFixtureApi() {
                override suspend fun validateBrowserContext(context: BrowserSessionContext) = wire.validateBrowserContext(context)
            }
            val runtime = runtime(store, api) { _, _ -> calls.incrementAndGet(); context(true) }
            try {
                runtime.bootstrap(); awaitFailure(runtime)
                assertEquals(1, calls.get()); assertEquals(3, server.requestCount)
                assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")))
                assertFalse(runtime.snapshot.value.error.orEmpty().contains("private-catalog"))
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `retained browser may rotate observed GraphQL metadata under its original lease`() = runBlocking {
        fun retained(renewed: Boolean): BrowserSessionContext {
            val raw = context(renewed).toJson()
            val headers = raw.getValue("headers").jsonObject
            return BrowserSessionContext.parse(JsonObject(raw - "sdk_cookie" + mapOf(
                "browser_lease" to JsonPrimitive("12345678-1234-1234-1234-123456789abc"),
                "headers" to JsonObject(headers + mapOf(
                    "client-session-id" to JsonPrimitive(if (renewed) "new-observed-session" else "old-observed-session"),
                    "client-version" to JsonPrimitive(if (renewed) "new-observed-version" else "old-observed-version"),
                )),
            )))
        }
        val store = store().also { it.saveTwitchSession(session(retained(false))) }
        val runtime = runtime(store, RenewalFixtureApi()) { _, _ -> retained(true) }
        try {
            runtime.bootstrap(); awaitSaved(store)
            assertEquals("new-observed-session", store.twitchSession()?.browserContext?.headers?.get("client-session-id"))
            assertEquals("new-observed-version", store.twitchSession()?.browserContext?.headers?.get("client-version"))
            assertEquals(retained(false).browserLease, store.twitchSession()?.browserContext?.browserLease)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `SDK renewal cannot replace saved GraphQL context with changed or omitted metadata`() = runBlocking {
        for (omit in listOf(false, true)) {
            val store = store().also { it.saveTwitchSession(session()) }
            val original = Files.readAllBytes(directory.resolve("session.enc"))
            val validations = AtomicInteger()
            val api = object : TwitchApi by RenewalFixtureApi() {
                override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                    validations.incrementAndGet(); return session(context)
                }
            }
            val runtime = runtime(store, api) { _, _ ->
                val raw = context(true).toJson()
                val headers = raw.getValue("headers").jsonObject
                BrowserSessionContext.parse(JsonObject(raw + ("headers" to JsonObject(
                    if (omit) headers - "accept-language"
                    else headers + ("client-session-id" to JsonPrimitive("unexpected-session")),
                ))))
            }
            try {
                runtime.bootstrap(); awaitFailure(runtime)
                assertEquals(0, validations.get())
                assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")))
            } finally { runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `storage failure retains previous encrypted session and fixed diagnostics`() = runBlocking {
        val store = store().also { it.saveTwitchSession(session()) }
        val original = Files.readAllBytes(directory.resolve("session.enc"))
        val backup = directory.resolve("fixture-preserved-session.enc")
        val logs = LogRepository(directory)
        val api = object : TwitchApi by RenewalFixtureApi() {
            override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                Files.move(directory.resolve("session.enc"), backup)
                Files.createDirectory(directory.resolve("session.enc"))
                return session(context)
            }
        }
        val runtime = runtime(store, api, logs = logs) { _, _ -> context(true) }
        try {
            runtime.bootstrap(); awaitFailure(runtime)
            assertContentEquals(original, Files.readAllBytes(backup))
            val diagnostics = runtime.snapshot.value.error.orEmpty() + logs.visibleText()
            assertFalse(diagnostics.contains(directory.toString()))
            assertFalse(diagnostics.contains("expired-cookie")); assertFalse(diagnostics.contains("fresh-cookie"))
            assertTrue(runtime.snapshot.value.error.orEmpty().contains("Saved credentials were preserved"))
        } finally {
            runtime.stopMiningAndJoin(shutdown = true)
            Files.deleteIfExists(directory.resolve("session.enc"))
            Files.move(backup, directory.resolve("session.enc"))
        }
        assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")))
        assertEquals("expired-cookie", store.twitchSession()?.browserContext?.sdkCookie?.value)
    }

    @Test fun `cancelled identity or catalog validation cannot install after reset replacement or shutdown`() = runBlocking {
        for (stage in listOf("identity", "catalog")) for (action in listOf("reset", "replace", "shutdown")) {
            val store = store().also { it.saveTwitchSession(session()) }
            val original = Files.readAllBytes(directory.resolve("session.enc"))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>(); val issued = AtomicInteger()
            suspend fun delayed() { entered.complete(Unit); withContext(NonCancellable) { release.await() }; returned.complete(Unit) }
            val api = object : TwitchApi by RenewalFixtureApi() {
                override suspend fun validateSession(session: StoredTwitchSession): ValidatedToken {
                    if (stage == "identity") delayed(); return identity()
                }
                override suspend fun validateBrowserContext(context: BrowserSessionContext): StoredTwitchSession {
                    if (stage == "catalog") delayed(); return session(context)
                }
            }
            val runtime = runtime(store, api) { _, _ -> issued.incrementAndGet(); context(true) }
            try {
                runtime.bootstrap(); withTimeout(3000) { entered.await() }
                val shutdown = if (action == "shutdown") async { runtime.stopMiningAndJoin(shutdown = true) } else null
                if (action == "reset") runtime.resetSessionAndJoin()
                if (action == "replace") runtime.startManagedBrowserAuthentication()
                if (shutdown != null) { yield(); delay(30); assertFalse(shutdown.isCompleted) }
                release.complete(Unit); withTimeout(3000) { returned.await(); shutdown?.await() }; delay(30)
                if (action == "reset") assertNull(store.twitchSession())
                else assertContentEquals(original, Files.readAllBytes(directory.resolve("session.enc")))
                assertEquals(if (stage == "identity") 0 else 1, issued.get())
                assertFalse(runtime.snapshot.value.miningActive)
            } finally { release.complete(Unit); runtime.stopMiningAndJoin(shutdown = true) }
        }
    }

    @Test fun `Stop stays authoritative when saving intent fails and renewal succeeds`() = runBlocking {
        val store = store().also { it.saveTwitchSession(session()) }
        val settings = SettingsRepository(directory).also { it.update { value -> value.copy(miningRequested = true) } }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val backup = directory.resolve("fixture-preserved-settings.json")
        val runtime = runtime(store, RenewalFixtureApi(), settings) { _, _ ->
            entered.complete(Unit); release.await(); context(true)
        }
        try {
            runtime.bootstrap(); withTimeout(3000) { entered.await() }
            Files.move(directory.resolve("settings.json"), backup)
            Files.createDirectory(directory.resolve("settings.json"))
            runtime.stopMining(); runtime.stopMiningAndJoin()
            assertTrue(settings.settings.value.miningRequested)
            release.complete(Unit); awaitSaved(store); delay(75)
            assertFalse(runtime.snapshot.value.miningActive)
            assertNull(runtime.snapshot.value.currentChannel)
            assertTrue(settings.settings.value.miningRequested)
        } finally {
            release.complete(Unit); runtime.stopMiningAndJoin(shutdown = true)
            Files.deleteIfExists(directory.resolve("settings.json")); Files.move(backup, directory.resolve("settings.json"))
        }
    }
}

private class RenewalFixtureApi : TwitchApi {
    override suspend fun validateAccessToken(accessToken: String) = ValidatedToken("12345", TwitchWebClientId)
    override suspend fun validateBrowserContext(context: BrowserSessionContext) = StoredTwitchSession(
        context.accessToken, "12345", context.deviceId, Instant.now(), context, username = "fixture_account")
    override suspend fun fetchCampaigns(session: StoredTwitchSession) = emptyList<Campaign>()
    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int) = emptyList<Channel>()
    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = error("Unexpected fixture call")
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = error("Unexpected fixture call")
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = error("Unexpected fixture call")
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = error("Unexpected fixture call")
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = error("Unexpected fixture call")
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = error("Unexpected fixture call")
    override fun newDeviceId() = "fixture-device"
}
