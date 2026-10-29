package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.local.*
import com.nathan.twitchdropsminer.android.data.model.*
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.*
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.io.TempDir

class ChannelEligibilityRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test fun `discovery and manual selection exclude confirmed account restrictions`() = runBlocking {
        val api = EligibilityApi()
        api.banned += 40L
        val runtime = runtime(api)
        try {
            runtime.startMining()
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 41L && it.phase == RuntimePhase.Watching } }
            assertFalse(runtime.snapshot.value.channels.any { it.id == 40L })
            api.banOnCheck = 42L
            runtime.selectChannel(42)
            withTimeout(3_000) { while (42L !in api.banned) delay(5) }
            withTimeout(3_000) { runtime.snapshot.first { it.currentTask.startsWith("Selected channel unavailable") } }
            assertEquals(41L, runtime.snapshot.value.currentChannel?.id)
            assertFalse(api.watched.any { it == 40L || it == 42L })
            runtime.selectChannel(40)
            delay(30)
            assertEquals(41L, runtime.snapshot.value.currentChannel?.id)
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `current channel ban triggers reselection before further delivery and survives rediscovery`() = runBlocking {
        val api = EligibilityApi()
        api.banned += 40L
        val runtime = runtime(api, channelStatusInterval = Duration.ofMillis(50))
        try {
            runtime.startMining()
            withTimeout(3_000) { while (41L !in api.watched) delay(5) }
            api.banOnCheck = 41L
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 42L && it.phase == RuntimePhase.Watching } }
            assertEquals(1, api.watched.count { it == 41L })
            assertFalse(runtime.snapshot.value.channels.any { it.id in setOf(40L, 41L) })
            runtime.findNewChannel()
            withTimeout(3_000) { runtime.snapshot.first { !it.channelSearchInProgress && it.currentTask.startsWith("No alternate") } }
            assertFalse(runtime.snapshot.value.channels.any { it.id in setOf(40L, 41L) })
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
        assertNull(api.boundAccount)
    }

    @Test fun `background promotion cannot install a banned channel and reset clears account ownership`() = runBlocking {
        val api = EligibilityApi(promote = true)
        val settings = SettingsRepository(directory)
        settings.setGamePriority("First Game", 1)
        settings.setGamePriority("Second Game", 2)
        val runtime = runtime(api, settings, promotionInterval = Duration.ofMillis(40))
        try {
            runtime.startMining()
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 41L && it.phase == RuntimePhase.Watching } }
            api.banned += 40L
            api.firstAvailable = true
            withTimeout(3_000) { while (api.firstLookups.get() < 2) delay(5) }
            delay(80)
            assertEquals(41L, runtime.snapshot.value.currentChannel?.id)
            assertFalse(api.watched.contains(40L))
            runtime.resetSessionAndJoin()
            assertNull(api.boundAccount)
            assertTrue(api.banned.isEmpty())
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `ban confirmed during status lookup does not create a failure cooldown after unban`() = runBlocking {
        val api = EligibilityApi()
        api.banned += 40L
        val runtime = runtime(api, channelStatusInterval = Duration.ofMillis(50))
        try {
            runtime.startMining()
            withTimeout(3_000) { while (41L !in api.watched) delay(5) }
            api.banDuringStatus = 41L
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 42L && it.phase == RuntimePhase.Watching } }
            api.banned.remove(41L)
            api.banned += 42L
            runtime.refreshInventory()
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 41L && it.phase == RuntimePhase.Watching } }
            assertFalse(Files.readString(directory.resolve("runtime.log")).contains("Channel changed category"))
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `ban confirmed during rejected delivery does not delay eligibility after unban`() =
        verifyBanDuringDelivery(throwFailure = false)

    @Test fun `ban confirmed during failed delivery does not enter network backoff after unban`() =
        verifyBanDuringDelivery(throwFailure = true)

    private fun verifyBanDuringDelivery(throwFailure: Boolean) = runBlocking {
        val api = EligibilityApi()
        api.banned += 40L
        api.banDuringDelivery = 41L
        api.throwDeliveryFailure = throwFailure
        val runtime = runtime(api)
        try {
            runtime.startMining()
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 42L && it.phase == RuntimePhase.Watching } }
            assertEquals(1, api.watched.count { it == 41L })
            api.banned.remove(41L)
            api.banned += 42L
            runtime.refreshInventory()
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 41L && it.phase == RuntimePhase.Watching } }
            val log = Files.readString(directory.resolve("runtime.log"))
            assertFalse(log.contains("Watch event failed"))
            assertFalse(log.contains("Watch heartbeat rejected"))
            assertFalse(log.contains("Channel watch events repeatedly rejected"))
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    @Test fun `temporary restriction expiry wakes idle discovery without a manual refresh`() = runBlocking {
        val api = EligibilityApi()
        api.banned.addAll(listOf(40L, 41L, 42L))
        api.temporaryRestriction = 41L to Instant.now().plusMillis(750)
        val runtime = runtime(api)
        try {
            runtime.startMining()
            withTimeout(3_000) { runtime.snapshot.first { it.phase == RuntimePhase.Idle && it.currentChannel == null } }
            withTimeout(3_000) { runtime.snapshot.first { it.currentChannel?.id == 41L && it.phase == RuntimePhase.Watching } }
            assertFalse(api.watched.any { it == 40L || it == 42L })
        } finally { runtime.stopMiningAndJoin(shutdown = true) }
    }

    private fun runtime(api: EligibilityApi, settings: SettingsRepository = SettingsRepository(directory),
        channelStatusInterval: Duration = Duration.ofMinutes(3), promotionInterval: Duration = Duration.ofMinutes(2)): LocalMinerRuntime {
        val store = SecureSessionStore(directory, Base64.getEncoder().encodeToString(ByteArray(32) { 8 }))
        store.saveTwitchSession(StoredTwitchSession("fixture-token", "12345", "fixture-device", Instant.now()))
        return LocalMinerRuntime(settings, store, LogRepository(directory), api,
            object : NetworkStatusProvider { override val isOnline = MutableStateFlow(true) },
            channelStatusCheckInterval = channelStatusInterval, higherPriorityCheckInterval = promotionInterval)
    }
}

private class EligibilityApi(private val promote: Boolean = false) : TwitchApi {
    val banned = ConcurrentHashMap.newKeySet<Long>()
    val watched = CopyOnWriteArrayList<Long>()
    val firstLookups = AtomicInteger()
    @Volatile var banOnCheck: Long? = null
    @Volatile var banDuringStatus: Long? = null
    @Volatile var banDuringDelivery: Long? = null
    @Volatile var throwDeliveryFailure = false
    @Volatile var temporaryRestriction: Pair<Long, Instant>? = null
    @Volatile var firstAvailable = false
    @Volatile var boundAccount: String? = "12345"
    override fun bindChannelAccount(userId: String?) {
        if (boundAccount != userId) banned.clear()
        boundAccount = userId
    }
    override fun isChannelAllowed(userId: String, channelId: Long): Boolean {
        val temporary = temporaryRestriction
        if (temporary?.first == channelId && !Instant.now().isBefore(temporary.second)) banned.remove(channelId)
        return userId == boundAccount && channelId !in banned
    }
    override fun nextChannelRestrictionExpiry(userId: String): Instant? = temporaryRestriction?.second
        ?.takeIf { userId == boundAccount && it.isAfter(Instant.now()) }
    override suspend fun checkChannelEligibility(session: StoredTwitchSession, channelId: Long): Boolean {
        if (banOnCheck == channelId) banned += channelId
        return isChannelAllowed(session.userId, channelId)
    }
    override suspend fun validateAccessToken(accessToken: String) = ValidatedToken("12345", TwitchClientId)
    override suspend fun fetchCampaigns(session: StoredTwitchSession) = if (promote) listOf(campaign("first", "First Game"), campaign("second", "Second Game")) else listOf(campaign("second", "Second Game"))
    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> {
        if (promote && campaign.id == "first") {
            firstLookups.incrementAndGet()
            return if (firstAvailable) listOf(channel(40, "First Game")) else emptyList()
        }
        return if (promote) listOf(channel(41, "Second Game")) else listOf(channel(40, "Second Game"), channel(41, "Second Game"), channel(42, "Second Game"))
    }
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel {
        val id = login.removePrefix("channel").toLong()
        if (banDuringStatus == id) {
            banDuringStatus = null
            banned += id
        }
        return channel(id, expectedGame ?: "Second Game").copy(dropsEnabled = id !in banned)
    }
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean {
        watched += channel.id
        if (banDuringDelivery == channel.id) {
            banDuringDelivery = null
            banned += channel.id
            if (throwDeliveryFailure) throw IllegalStateException("Synthetic delivery failure after restriction")
            return false
        }
        return true
    }
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long) = CurrentDropProgress(if (channelId == 40L && promote) "first-drop" else "second-drop", 1, channelId)
    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = error("Unused fixture")
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = error("Unused fixture")
    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult = error("Unused fixture")
    override fun newDeviceId() = "fixture-device"
    private fun channel(id: Long, game: String) = Channel(id, "channel$id", login = "channel$id", game = game,
        viewers = (100 - id).toInt(), online = true, dropsEnabled = true, broadcastId = "broadcast$id")
    private fun campaign(id: String, game: String) = Campaign(id, id, game, linked = true, active = true,
        drops = listOf(CampaignDrop("$id-drop", "Drop", 0, 60, 0f, false, false, emptyList())), totalDrops = 1)
}
