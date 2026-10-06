package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.model.*
import java.time.Instant
import kotlin.test.*

class RewardEligibilityTest {
    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private fun drop(id: String, vararg deps: String, type: String = "BADGE") = CampaignDrop(
        id, id, 0, 30, 0f, false, false, listOf(DropReward(id, type)), preconditionDropIds = deps.toList())
    private fun campaign(vararg drops: CampaignDrop) = Campaign("c", "Campaign", "Game", active = true, linked = true, drops = drops.toList())

    @Test fun `missing dependencies never unlock`() {
        val c = campaign(drop("a", "missing"))
        assertNull(c.watchableDrop(now = now))
        assertContains(c.withRewardEligibility(AppSettings(), now).drops.single().blockedReason!!, "Missing prerequisite")
    }
    @Test fun `cycles and dependents are blocked but independent work survives`() {
        val c = campaign(drop("a", "b"), drop("b", "a"), drop("c", "a"), drop("d")).withRewardEligibility(AppSettings(), now)
        assertEquals("d", c.watchableDrop(now = now)?.id)
        assertTrue(c.drops.take(3).all { !it.eligibleByFilter })
    }
    @Test fun `type selection retains shared prerequisites and name exclusion blocks dependents`() {
        val c = campaign(drop("base", type = "OTHER"), drop("badge", "base"), drop("second", "base"))
        val settings = AppSettings(allowedRewardTypes = setOf("BADGE"))
        assertEquals("base", c.withRewardEligibility(settings, now).watchableDrop(now = now)?.id)
        val blocked = c.withRewardEligibility(settings.copy(excludedRewardNames = listOf("BASE")), now)
        assertNull(blocked.watchableDrop(now = now))
        assertTrue(blocked.drops.all { !it.eligibleByFilter })
    }
    @Test fun `claimed prerequisites remain satisfied despite filters and old dates`() {
        val c = campaign(drop("base").copy(isClaimed = true, endsAt = now.minusSeconds(60)), drop("next", "base"))
        assertEquals("next", c.withRewardEligibility(AppSettings(excludedRewardNames = listOf("base")), now).watchableDrop(now = now)?.id)
    }
    @Test fun `non overlapping prerequisite windows block target`() {
        val c = campaign(drop("base").copy(startsAt = now.plusSeconds(3600)), drop("next", "base").copy(endsAt = now.plusSeconds(60)))
        assertContains(c.withRewardEligibility(AppSettings(), now).drops.last().blockedReason!!, "starts after")
    }
    @Test fun `filter changes are recomputed and normalized`() {
        val settings = AppSettings(excludedRewardNames = listOf(" A ", "a", ""), allowedRewardTypes = setOf(" badge ")).normalized()
        assertEquals(listOf("A"), settings.excludedRewardNames)
        assertEquals(setOf("BADGE"), settings.allowedRewardTypes)
        val c = campaign(drop("a")).withRewardEligibility(settings, now).withRewardEligibility(AppSettings(), now)
        assertNotNull(c.watchableDrop(now = now))
    }
    @Test fun `remaining prerequisite watch time must fit before the target deadline`() {
        val c = campaign(drop("base"), drop("next", "base").copy(endsAt = now.plusSeconds(2700)))
        val filtered = c.withRewardEligibility(AppSettings(), now)
        assertTrue(filtered.drops.first().eligibleByFilter)
        assertContains(filtered.drops.last().blockedReason!!, "Insufficient time")
        val progressed = c.copy(drops = listOf(c.drops.first().copy(currentMinutes = 20), c.drops.last()))
        assertTrue(progressed.withRewardEligibility(AppSettings(), now).drops.last().eligibleByFilter)
    }
}
