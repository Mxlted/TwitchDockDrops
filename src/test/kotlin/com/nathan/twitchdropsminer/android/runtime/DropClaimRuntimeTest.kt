package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorization
import com.nathan.twitchdropsminer.android.data.twitch.DeviceTokenPollResult
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimOutcome
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimResult
import com.nathan.twitchdropsminer.android.data.twitch.TokenResponse
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiErrorType
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException
import com.nathan.twitchdropsminer.android.data.twitch.ValidatedToken
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class DropClaimRuntimeTest {
    @Test
    fun `transient unlinked network failure retries and then succeeds`() = runBlocking {
        var now = Instant.parse("2026-08-12T12:00:00Z")
        val api = ClaimTwitchApi(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(TwitchApiException(TwitchApiErrorType.Network, "offline")),
                    Result.success(DropClaimResult(DropClaimOutcome.Claimed)),
                ),
            ),
        )
        val tracker = ClaimAttemptTracker(Duration.ofMinutes(5)) { now }
        val handler = DropClaimHandler(api, tracker)
        val campaign = completedCampaign(linked = false)
        val drop = campaign.drops.single()

        val failed = handler.claim(session(), campaign, drop)
        assertEquals(RuntimeClaimOutcome.NetworkFailure, failed.outcome)
        assertEquals(now.plus(Duration.ofMinutes(5)), failed.retryAt)
        assertEquals(RuntimeClaimOutcome.Suppressed, handler.claim(session(), campaign, drop).outcome)

        now = failed.retryAt!!
        val claimed = handler.claim(session(), campaign, drop)
        assertEquals(RuntimeClaimOutcome.Claimed, claimed.outcome)
        assertEquals(2, api.claimCalls)
    }

    @Test
    fun `claim retry becomes eligible exactly at cooldown boundary`() {
        var now = Instant.parse("2026-08-12T12:00:00Z")
        val tracker = ClaimAttemptTracker(Duration.ofSeconds(30)) { now }
        val retryAt = tracker.recordFailure("claim")

        now = retryAt.minusMillis(1)
        assertEquals(retryAt, tracker.suppressionFor("claim")?.retryAt)
        now = retryAt
        assertNull(tracker.suppressionFor("claim"))
    }

    @Test
    fun `invalid token remains an expiry result without claim cooldown`() = runBlocking {
        val api = ClaimTwitchApi(
            outcomes = ArrayDeque(
                listOf(Result.failure(TwitchApiException(TwitchApiErrorType.InvalidToken, "expired"))),
            ),
        )
        val handler = DropClaimHandler(api)
        val campaign = completedCampaign(linked = true)
        val drop = campaign.drops.single()

        assertEquals(RuntimeClaimOutcome.InvalidToken, handler.claim(session(), campaign, drop).outcome)
        assertNull(handler.nextRetryAt())
        assertNull(handler.suppressionFor(session(), campaign, drop))
    }

    @Test
    fun `definitive eligibility rejection is terminal while ambiguous failures retry`() = runBlocking {
        var now = Instant.parse("2026-08-12T00:00:00Z")
        val api = ClaimTwitchApi(
            ArrayDeque(
                listOf(
                Result.success(DropClaimResult(DropClaimOutcome.Ineligible, message = "not linked")),
                ),
            ),
        )
        val handler = DropClaimHandler(api, ClaimAttemptTracker(Duration.ofSeconds(10)) { now })
        val campaign = completedCampaign(linked = false)
        val drop = campaign.drops.single()
        val rejected = handler.claim(session(), campaign, drop)

        assertEquals(RuntimeClaimOutcome.TerminalRejection, rejected.outcome)
        assertEquals(null, rejected.retryAt)
        now = now.plusSeconds(60)
        assertEquals(RuntimeClaimOutcome.Suppressed, handler.claim(session(), campaign, drop).outcome)
        assertEquals(1, api.claimCalls)
    }

    @Test
    fun `superseded noncancellable claim cannot record cooldown or terminal state`() = runBlocking {
        val api = ClaimTwitchApi(blockClaim = true)
        val handler = DropClaimHandler(api)
        val campaign = completedCampaign(linked = false)
        val drop = campaign.drops.single()
        var current = true

        val pending = async {
            handler.claim(session(), campaign, drop) {
                if (!current) throw kotlinx.coroutines.CancellationException("superseded")
            }
        }
        api.claimStarted.await()
        current = false
        api.releaseClaim.complete(Unit)

        assertFailsWith<kotlinx.coroutines.CancellationException> { pending.await() }
        assertNull(handler.nextRetryAt())
        assertNull(handler.suppressionFor(session(), campaign, drop))
    }

    private fun completedCampaign(linked: Boolean) = Campaign(
        id = "campaign",
        name = "Campaign",
        gameName = "Game",
        linked = linked,
        linkStatusKnown = true,
        linkUrl = if (linked) null else "https://example.test/link",
        active = true,
        drops = listOf(
            CampaignDrop(
                id = "drop",
                name = "Drop",
                currentMinutes = 60,
                requiredMinutes = 60,
                progress = 1f,
                isClaimed = false,
                canClaim = true,
                rewards = emptyList(),
                claimId = "claim-id",
            ),
        ),
        totalDrops = 1,
    )

    private fun session() = StoredTwitchSession(
        accessToken = "token",
        userId = "user",
        deviceId = "device",
        savedAt = Instant.EPOCH,
    )
}

private class ClaimTwitchApi(
    private val outcomes: ArrayDeque<Result<DropClaimResult>> = ArrayDeque(
        listOf(Result.success(DropClaimResult(DropClaimOutcome.Failed))),
    ),
    private val blockClaim: Boolean = false,
) : TwitchApi {
    var claimCalls: Int = 0
    val claimStarted = CompletableDeferred<Unit>()
    val releaseClaim = CompletableDeferred<Unit>()

    override suspend fun claimDrop(session: StoredTwitchSession, dropInstanceId: String): DropClaimResult {
        claimCalls += 1
        claimStarted.complete(Unit)
        if (blockClaim) {
            withContext(NonCancellable) { releaseClaim.await() }
        }
        return outcomes.removeFirst().getOrThrow()
    }

    override suspend fun requestDeviceCode(deviceId: String): DeviceAuthorization = unused()
    override suspend fun pollDeviceToken(deviceCode: String, deviceId: String): DeviceTokenPollResult = unused()
    override suspend fun validateAccessToken(accessToken: String): ValidatedToken = unused()
    override suspend fun fetchCampaigns(session: StoredTwitchSession): List<Campaign> = unused()
    override suspend fun fetchEligibleChannels(session: StoredTwitchSession, campaign: Campaign, limit: Int): List<Channel> = unused()
    override suspend fun fetchChannel(session: StoredTwitchSession, login: String, expectedGame: String?): Channel = unused()
    override suspend fun sendWatchMinute(session: StoredTwitchSession, channel: Channel): Boolean = unused()
    override suspend fun currentDrop(session: StoredTwitchSession, channelId: Long): CurrentDropProgress? = unused()
    override fun newDeviceId(): String = "device"

    private fun <T> unused(): T = error("Unexpected Twitch API call")
}
