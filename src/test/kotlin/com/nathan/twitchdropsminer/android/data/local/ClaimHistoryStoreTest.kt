package com.nathan.twitchdropsminer.android.data.local

import com.nathan.twitchdropsminer.android.data.model.*
import java.nio.file.Files
import java.time.Instant
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ClaimHistoryStoreTest {
    @TempDir lateinit var directory: Path
    private val drop = CampaignDrop("d", "Reward", 30, 30, 1f, false, true, emptyList(), claimId = "instance", claimEvidenceKnown = true)
    private val campaign = Campaign("c", "Campaign", "Game", drops = listOf(drop))

    @Test fun `intent survives restart and incomplete inventory cannot release it`() {
        val store = ClaimHistoryStore(directory)
        store.begin("42", campaign, drop)
        val restored = ClaimHistoryStore(directory)
        restored.reconcile("42", emptyList())
        assertTrue(restored.suppressed("42", "c", "d"))
        assertFalse(restored.suppressed("43", "c", "d"))
        restored.reconcile("42", listOf(campaign.copy(drops = listOf(drop.copy(isClaimed = true)))))
        assertContains(restored.view("42"), "confirmed")
        assertFalse(restored.view("42").contains("pending"))
    }
    @Test fun `retry requires cooldown and explicit fresh claim evidence`() {
        var now = Instant.parse("2026-10-05T12:00:00Z")
        val store = ClaimHistoryStore(directory) { now }
        store.begin("42", campaign, drop)
        store.reconcile("42", listOf(campaign))
        assertTrue(store.suppressed("42", "c", "d"))
        now = now.plusSeconds(301)
        store.reconcile("42", listOf(campaign.copy(drops = listOf(drop.copy(claimEvidenceKnown = false)))))
        assertTrue(store.suppressed("42", "c", "d"))
        store.reconcile("42", listOf(campaign))
        assertFalse(store.suppressed("42", "c", "d"))
    }
    @Test fun `corrupt file is preserved and prevents claims`() {
        val path = directory.resolve("claims-42.json")
        Files.writeString(path, "broken")
        assertFailsWith<IllegalStateException> { ClaimHistoryStore(directory).begin("42", campaign, drop) }
        assertEquals("broken", Files.readString(path))
        assertFailsWith<IllegalArgumentException> { ClaimHistoryStore(directory).view("../42") }
    }
    @Test fun `oversized identifiers cannot lose pending claim identity`() {
        val store = ClaimHistoryStore(directory)
        store.begin("42", campaign, drop)
        val before = Files.readString(directory.resolve("claims-42.json"))
        assertFailsWith<IllegalArgumentException> { store.begin("42", campaign, drop.copy(id = "x".repeat(501))) }
        assertEquals(before, Files.readString(directory.resolve("claims-42.json")))
    }
}
