package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.AutoModePriority
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RuntimeExecutionPolicyTest {
    @Test
    fun `default fallback order exhausts linked groups before unlinked groups`() {
        assertEquals(
            listOf(
                AutoModePriority.LinkedClaimedProgress,
                AutoModePriority.LinkedViewingProgress,
                AutoModePriority.LinkedFresh,
                AutoModePriority.UnlinkedClaimedProgress,
                AutoModePriority.UnlinkedViewingProgress,
                AutoModePriority.UnlinkedFresh,
            ),
            AutoModePriority.DefaultOrder,
        )

        val decision = assertIs<CampaignCandidateDecision.Try>(
            CampaignPrioritySelector.initialDecision(
                settings = AppSettings(fallbackToOtherGames = true).normalized(),
                campaigns = listOf(
                    campaign("unlinked-claimed", linked = false, claimedDrop = true),
                    campaign("linked-fresh", linked = true),
                ),
            ),
        )
        assertEquals(CampaignSelectionMode.Auto, decision.mode)
        assertEquals(listOf("linked-fresh"), decision.candidates.map(Campaign::id))
    }

    @Test
    fun `active drop ignores future and expired watch windows`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val active = drop("active", startsAt = now.minusSeconds(60), endsAt = now.plusSeconds(60))
        val future = drop("future", startsAt = now.plusSeconds(1), endsAt = now.plusSeconds(120))
        val expired = drop("expired", startsAt = now.minusSeconds(120), endsAt = now.minusSeconds(1))
        val campaign = campaign("campaign", linked = true).copy(
            drops = listOf(future, expired, active),
            totalDrops = 3,
        )

        assertEquals("active", campaign.activeDrop(now = now)?.id)
        assertEquals("active", campaign.activeDrop(preferredDropId = "future", now = now)?.id)
        assertEquals("active", campaign.activeDrop(preferredDropId = "expired", now = now)?.id)
    }

    @Test
    fun `completed claimable drop remains claimable after watch window closes`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val completed = drop(
            id = "completed",
            currentMinutes = 60,
            startsAt = now.minusSeconds(120),
            endsAt = now.minusSeconds(1),
        ).copy(canClaim = true)
        val campaign = campaign("campaign", linked = true).copy(drops = listOf(completed))

        assertEquals("completed", campaign.activeDrop(now = now)?.id)
        assertNull(campaign.watchableDrop(now = now))
    }

    @Test
    fun `unknown progress is not treated as confirmed no-progress evidence`() {
        assertEquals(true, ProgressObservation.Confirmed.isConfirmed)
        assertEquals(true, ProgressObservation.NoActiveDrop.isConfirmed)
        assertEquals(false, ProgressObservation.UnexpectedDrop.isConfirmed)
        assertEquals(false, ProgressObservation.Unavailable("offline").isConfirmed)
    }

    @Test
    fun `unlinked fallback requires multiple confirmed observations before skipping`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val settings = AppSettings(watchIntervalSeconds = 20).normalized()
        val campaign = campaign("unlinked", linked = false)
        var probe = UnlinkedProgressProbe.start(campaign, settings, now)

        probe = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(campaign, settings, now.plus(Duration.ofMinutes(2))),
        ).probe
        probe = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(campaign, settings, now.plus(Duration.ofMinutes(2)).plusSeconds(1)),
        ).probe
        assertIs<UnlinkedProgressProbeResult.Stalled>(
            probe.observe(campaign, settings, now.plus(Duration.ofMinutes(2)).plusSeconds(2)),
        )
    }

    @Test
    fun `unlinked progress remains supervised and sustained later stall is detected`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val settings = AppSettings(watchIntervalSeconds = 20).normalized()
        val original = campaign("unlinked", linked = false)
        val progressed = original.copy(
            drops = original.drops.map { it.copy(currentMinutes = 1, progress = 1f / 60f) },
        )
        var probe = UnlinkedProgressProbe.start(original, settings, now)

        val progress = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(progressed, settings, now.plusSeconds(30)),
        )
        assertEquals(true, progress.progressDetectedNow)
        assertEquals(true, progress.probe.progressDetected)
        probe = progress.probe

        val stalledAt = now.plus(Duration.ofMinutes(6))
        probe = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(progressed, settings, stalledAt),
        ).probe
        probe = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(progressed, settings, stalledAt.plusSeconds(1)),
        ).probe
        val stalled = assertIs<UnlinkedProgressProbeResult.Stalled>(
            probe.observe(progressed, settings, stalledAt.plusSeconds(2)),
        )
        assertEquals(true, stalled.progressHadBeenDetected)
    }

    @Test
    fun `unavailable progress does not advance evidence and resumed progress resets stall window`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val settings = AppSettings(watchIntervalSeconds = 20).normalized()
        val original = campaign("unlinked", linked = false)
        var probe = UnlinkedProgressProbe.start(original, settings, now)

        // Endpoint failures do not call observe, even if wall-clock time passes the check boundary.
        probe = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(original, settings, now.plus(Duration.ofMinutes(3))),
        ).probe
        assertEquals(1, probe.confirmedObservations)

        val progressed = original.copy(
            drops = original.drops.map { it.copy(currentMinutes = 2, progress = 2f / 60f) },
        )
        probe = assertIs<UnlinkedProgressProbeResult.Continue>(
            probe.observe(progressed, settings, now.plus(Duration.ofMinutes(3)).plusSeconds(1)),
        ).probe
        assertEquals(0, probe.confirmedObservations)
        assertEquals(2, probe.baselineMinutes)
        assertEquals(now.plus(Duration.ofMinutes(8)).plusSeconds(1), probe.checkAt)
    }

    @Test
    fun `watch configuration renewal precedes channel abandonment`() {
        assertEquals(
            ProgressRecoveryAction.RenewWatchConfiguration,
            ProgressRecoveryPolicy.afterConfirmedStall(0),
        )
        assertEquals(
            ProgressRecoveryAction.AbandonChannel,
            ProgressRecoveryPolicy.afterConfirmedStall(1),
        )
    }

    @Test
    fun `idle and active scheduling honor eligibility claim and drop boundaries`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val futureStart = now.plusSeconds(300)
        val futureCampaign = campaign("future", linked = true).copy(
            active = false,
            upcoming = true,
            startsAt = futureStart,
            drops = listOf(drop("future-drop", startsAt = futureStart)),
        )

        assertEquals(
            now.plusSeconds(180),
            RuntimeTemporalSchedule.nextIdleDeadline(
                campaigns = listOf(futureCampaign),
                now = now,
                regularDeadline = now.plusSeconds(3_600),
                inventoryRefreshAt = now.plusSeconds(3_600),
                claimRetryAt = now.plusSeconds(180),
            ),
        )
        assertEquals(
            futureStart,
            RuntimeTemporalSchedule.nextIdleDeadline(
                campaigns = listOf(futureCampaign),
                now = now,
                regularDeadline = now.plusSeconds(3_600),
                inventoryRefreshAt = now.plusSeconds(3_600),
                claimRetryAt = null,
            ),
        )
        assertEquals(
            now.plusSeconds(45),
            RuntimeTemporalSchedule.nextActiveDeadline(
                nextWatchAt = now.plusSeconds(59),
                nextPromotionCheckAt = now.plusSeconds(120),
                refreshAt = now.plusSeconds(3_600),
                activeCampaignEndsAt = null,
                activeDropEndsAt = now.plusSeconds(45),
            ),
        )
        assertEquals(
            "Campaign has later scheduled drops",
            CampaignTemporalPolicy.idleStatus(futureCampaign, now).activityTitle,
        )
    }

    @Test
    fun `stale upcoming campaign becomes selectable at its time boundary`() {
        val startsAt = Instant.parse("2026-08-12T12:05:00Z")
        val campaign = campaign("future", linked = true).copy(
            active = false,
            upcoming = true,
            startsAt = startsAt,
            drops = listOf(drop("future-drop", startsAt = startsAt)),
        )

        assertNull(CampaignPrioritySelector.select(AppSettings().normalized(), listOf(campaign), startsAt.minusMillis(1)))
        assertEquals(
            campaign.id,
            CampaignPrioritySelector.select(AppSettings().normalized(), listOf(campaign), startsAt)?.id,
        )
    }

    @Test
    fun `campaign boundaries govern drops even when drop timestamps are missing`() {
        val boundary = Instant.parse("2026-08-12T12:00:00Z")
        val drop = CampaignDrop(
            id = "drop",
            name = "Drop",
            currentMinutes = 0,
            requiredMinutes = 60,
            progress = 0f,
            isClaimed = false,
            canClaim = false,
            rewards = emptyList(),
        )
        val future = campaign("future", linked = true).copy(
            active = true,
            startsAt = boundary.plusSeconds(1),
            drops = listOf(drop),
        )
        val ending = campaign("ending", linked = true).copy(
            active = true,
            endsAt = boundary,
            drops = listOf(drop),
        )
        val beginsNow = future.copy(startsAt = boundary)
        val missingTimes = future.copy(startsAt = null, endsAt = null, active = true)

        assertEquals(null, future.watchableDrop(now = boundary))
        assertEquals(null, ending.watchableDrop(now = boundary))
        assertEquals("drop", beginsNow.watchableDrop(now = boundary)?.id)
        assertEquals("drop", missingTimes.watchableDrop(now = boundary)?.id)
        assertEquals(
            boundary.plusSeconds(20),
            RuntimeTemporalSchedule.nextActiveDeadline(
                nextWatchAt = boundary.plusSeconds(59),
                nextPromotionCheckAt = boundary.plusSeconds(120),
                refreshAt = boundary.plusSeconds(3_600),
                activeCampaignEndsAt = boundary.plusSeconds(20),
                activeDropEndsAt = null,
            ),
        )
    }

    @Test
    fun `prioritized promotion only moves toward earlier saved games`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val first = campaign("first", linked = true).copy(gameName = "Game One")
        val second = campaign("second", linked = true).copy(gameName = "Game Two")
        val forward = AppSettings(
            selectedGamePriority = listOf("Game One", "Game Two"),
        ).normalized()

        assertEquals(
            "second",
            CampaignPrioritySelector.select(forward, listOf(second), now)?.id,
        )
        val promotion = CampaignPrioritySelector.higherPriorityDecisions(
            settings = forward,
            campaigns = listOf(first, second),
            currentMode = CampaignSelectionMode.Prioritized,
            currentCampaign = second,
            now = now,
        )
        assertEquals(listOf("first"), promotion.flatMap { it.candidates }.map(Campaign::id))

        val reversed = forward.copy(selectedGamePriority = listOf("Game Two", "Game One")).normalized()
        assertEquals(
            emptyList(),
            CampaignPrioritySelector.higherPriorityDecisions(
                settings = reversed,
                campaigns = listOf(first, second),
                currentMode = CampaignSelectionMode.Prioritized,
                currentCampaign = second,
                now = now,
            ),
        )
    }

    @Test
    fun `linked progress watchdog confirms a stall and resets after progress`() {
        val now = Instant.parse("2026-08-12T12:00:00Z")
        val settings = AppSettings(watchIntervalSeconds = 20).normalized()
        val campaign = campaign("linked", linked = true)
        var probe = LinkedProgressProbe.start(campaign, settings, now)
        val checkAt = now.plus(Duration.ofMinutes(5))

        probe = assertIs<LinkedProgressProbeResult.Continue>(
            probe.observe(campaign, settings, checkAt),
        ).probe
        probe = assertIs<LinkedProgressProbeResult.Continue>(
            probe.observe(campaign, settings, checkAt.plusSeconds(1)),
        ).probe
        assertIs<LinkedProgressProbeResult.Stalled>(
            probe.observe(campaign, settings, checkAt.plusSeconds(2)),
        )

        val progressed = campaign.copy(
            drops = campaign.drops.map { it.copy(currentMinutes = 1) },
        )
        val progressResult = assertIs<LinkedProgressProbeResult.Continue>(
            LinkedProgressProbe.start(campaign, settings, now)
                .observe(progressed, settings, checkAt),
        )
        assertEquals(true, progressResult.progressDetectedNow)
        assertEquals(1, progressResult.probe.baselineMinutes)
    }

    private fun campaign(
        id: String,
        linked: Boolean,
        currentMinutes: Int = 0,
        claimedDrop: Boolean = false,
    ): Campaign {
        val unclaimed = drop("$id-drop", currentMinutes = currentMinutes)
        val claimed = drop("$id-claimed", currentMinutes = 30).copy(
            requiredMinutes = 30,
            isClaimed = true,
            progress = 1f,
        )
        val drops = if (claimedDrop) listOf(claimed, unclaimed) else listOf(unclaimed)
        return Campaign(
            id = id,
            name = id,
            gameName = id,
            linked = linked,
            linkStatusKnown = true,
            linkUrl = if (linked) null else "https://example.test/link",
            active = true,
            drops = drops,
            claimedDrops = drops.count(CampaignDrop::isClaimed),
            totalDrops = drops.size,
        )
    }

    private fun drop(
        id: String,
        currentMinutes: Int = 0,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
    ) = CampaignDrop(
        id = id,
        name = id,
        currentMinutes = currentMinutes,
        requiredMinutes = 60,
        progress = currentMinutes / 60f,
        isClaimed = false,
        canClaim = false,
        rewards = emptyList(),
        startsAt = startsAt,
        endsAt = endsAt,
    )
}
