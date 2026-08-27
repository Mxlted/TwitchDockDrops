package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorization
import com.nathan.twitchdropsminer.android.data.twitch.DeviceTokenPollResult
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimResult
import com.nathan.twitchdropsminer.android.data.twitch.TokenResponse
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.ValidatedToken
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir

class LocalMinerRuntimeStartupTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `bootstrap refreshes inventory when a stored Twitch session exists`() = runBlocking {
        val sessionStore = sessionStore()
        val storedSession = StoredTwitchSession(
            accessToken = "test-token",
            userId = "user-123",
            deviceId = "device-123",
            savedAt = Instant.parse("2026-08-10T12:00:00Z"),
        )
        sessionStore.saveTwitchSession(storedSession)
        val twitchApi = RecordingTwitchApi()
        val runtime = runtime(sessionStore, twitchApi)

        runtime.bootstrap()

        assertEquals(storedSession, withTimeout(2_000) { twitchApi.inventoryRequest.await() })
        val refreshed = withTimeout(2_000) {
            runtime.snapshot.first { snapshot -> snapshot.currentTask == "Inventory refreshed" }
        }
        assertEquals(LoginState.LoggedIn, refreshed.account.state)
        assertEquals(listOf("campaign-1"), refreshed.campaigns.map(Campaign::id))
        assertFalse(refreshed.miningActive)
        assertNull(withTimeoutOrNull(100) { twitchApi.validationRequest.await() })
    }

    @Test
    fun `bootstrap resumes mining when stored intent requests it`() = runBlocking {
        val sessionStore = sessionStore()
        val storedSession = StoredTwitchSession(
            accessToken = "test-token",
            userId = "user-123",
            deviceId = "device-123",
            savedAt = Instant.parse("2026-08-10T12:00:00Z"),
        )
        sessionStore.saveTwitchSession(storedSession)
        val settings = SettingsRepository(directory)
        settings.update { it.copy(miningRequested = true) }
        val twitchApi = RecordingTwitchApi(campaigns = emptyList())
        val runtime = runtime(sessionStore, twitchApi, settings)

        runtime.bootstrap()

        assertEquals("test-token", withTimeout(2_000) { twitchApi.validationRequest.await() })
        val resumed = withTimeout(2_000) {
            runtime.snapshot.first { snapshot -> snapshot.miningActive }
        }
        assertTrue(resumed.activity.any { it.title == "Resuming mining after restart" })
        runtime.stopMiningAndJoin()
        assertTrue(settings.settings.value.miningRequested)
    }

    @Test
    fun `user start and stop commands persist mining intent`() = runBlocking {
        val settings = SettingsRepository(directory)
        val runtime = runtime(sessionStore(), RecordingTwitchApi(), settings)

        runtime.startMining()
        withTimeout(2_000) { settings.settings.first { it.miningRequested } }
        runtime.stopMining()
        withTimeout(2_000) { settings.settings.first { !it.miningRequested } }

        assertFalse(settings.settings.value.miningRequested)
    }

    @Test
    fun `start mining continues when intent cannot be persisted`() = runBlocking {
        val sessionStore = sessionStore().also { store ->
            store.saveTwitchSession(
                StoredTwitchSession(
                    accessToken = "test-token",
                    userId = "user-123",
                    deviceId = "device-123",
                    savedAt = Instant.parse("2026-08-10T12:00:00Z"),
                ),
            )
        }
        val settings = SettingsRepository(directory)
        Files.createDirectory(directory.resolve("settings.json"))
        val logs = LogRepository(directory)
        val twitchApi = RecordingTwitchApi(campaigns = emptyList())
        val runtime = LocalMinerRuntime(
            settingsRepository = settings,
            secureSessionStore = sessionStore,
            logRepository = logs,
            twitchApiClient = twitchApi,
            networkStatusProvider = OnlineNetworkStatusProvider,
        )

        runtime.startMining()

        withTimeout(2_000) { runtime.snapshot.first { snapshot -> snapshot.miningActive } }
        val warning = withTimeout(2_000) {
            logs.entries.first { entries ->
                entries.any { entry ->
                    entry.level == "WARN" && entry.message.contains("Mining intent could not be saved")
                }
            }
        }
        assertTrue(warning.any { it.message.contains("it will not survive a restart") })
        runtime.stopMiningAndJoin()
    }

    @Test
    fun `bootstrap does not refresh inventory without a stored Twitch session`() = runBlocking {
        val twitchApi = RecordingTwitchApi()
        val runtime = runtime(sessionStore(), twitchApi)

        runtime.bootstrap()

        assertEquals(LoginState.LoggedOut, runtime.snapshot.value.account.state)
        assertNull(withTimeoutOrNull(100) { twitchApi.inventoryRequest.await() })
    }

    private fun runtime(
        sessionStore: SecureSessionStore,
        twitchApi: TwitchApi,
        settings: SettingsRepository = SettingsRepository(directory),
    ): LocalMinerRuntime = LocalMinerRuntime(
        settingsRepository = settings,
        secureSessionStore = sessionStore,
        logRepository = LogRepository(directory),
        twitchApiClient = twitchApi,
        networkStatusProvider = OnlineNetworkStatusProvider,
    )

    private fun sessionStore(): SecureSessionStore {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { index -> index.toByte() })
        return SecureSessionStore(directory, key)
    }
}

private object OnlineNetworkStatusProvider : NetworkStatusProvider {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
}

private class RecordingTwitchApi(
    private val campaigns: List<Campaign> = listOf(
        Campaign(
            id = "campaign-1",
            name = "Test campaign",
            gameName = "Test game",
            active = true,
        ),
    ),
) : TwitchApi {
    val inventoryRequest = CompletableDeferred<StoredTwitchSession>()
    val validationRequest = CompletableDeferred<String>()

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> {
        inventoryRequest.complete(session)
        return campaigns
    }

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()

    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken {
        validationRequest.complete(accessToken)
        return ValidatedToken("user-123", "client")
    }

    override suspend fun fetchEligibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        limit: Int,
    ): List<Channel> = unused()

    override suspend fun fetchChannel(
        session: StoredTwitchSession,
        login: String,
        expectedGame: String?,
    ): Channel = unused()

    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = unused()

    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = unused()

    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = unused()

    override fun newDeviceId(): String = unused()

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}
