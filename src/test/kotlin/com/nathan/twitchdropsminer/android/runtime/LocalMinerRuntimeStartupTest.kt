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
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    ): LocalMinerRuntime = LocalMinerRuntime(
        settingsRepository = SettingsRepository(directory),
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

private class RecordingTwitchApi : TwitchApi {
    val inventoryRequest = CompletableDeferred<StoredTwitchSession>()

    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> {
        inventoryRequest.complete(session)
        return listOf(
            Campaign(
                id = "campaign-1",
                name = "Test campaign",
                gameName = "Test game",
                active = true,
            ),
        )
    }

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()

    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()

    override suspend fun validateAccessToken(accessToken: String): ValidatedToken = unused()

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
