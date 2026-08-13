package com.nathan.twitchdropsminer.android.data.local

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class SettingsRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `settings are normalized and survive a new repository instance`() = runBlocking {
        val repository = SettingsRepository(directory)
        repository.update {
            it.copy(
                watchIntervalSeconds = 4,
                inventoryRefreshMinutes = 999,
                selectedGamePriority = listOf(" Warframe ", "warframe", "Palia"),
                fallbackToOtherGames = true,
            )
        }

        val loaded = SettingsRepository(directory).settings.value
        assertEquals(20, loaded.watchIntervalSeconds)
        assertEquals(180, loaded.inventoryRefreshMinutes)
        assertEquals(listOf("Warframe", "Palia"), loaded.selectedGamePriority)
        assertTrue(loaded.fallbackToOtherGames)
    }

    @Test
    fun `reset settings does not clear onboarding state`() = runBlocking {
        val repository = SettingsRepository(directory)
        repository.update { it.copy(hasCompletedOnboarding = true, debugLogging = true) }
        repository.resetSettings()

        assertTrue(repository.settings.value.hasCompletedOnboarding)
        assertFalse(repository.settings.value.debugLogging)
    }

    @Test
    fun `corrupt settings are quarantined and reported without deleting saved bytes`() {
        val saved = "{not-json accessToken=filesystem-secret"
        Files.writeString(directory.resolve("settings.json"), saved)

        val repository = SettingsRepository(directory)

        assertEquals(PersistenceFileState.Corrupt, repository.loadStatus.state)
        assertTrue(repository.loadStatus.diagnostic?.contains("accessToken=filesystem-secret") == false)
        val quarantine = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("settings.json.corrupt-") }.findFirst().orElseThrow()
        }
        assertEquals(saved, Files.readString(quarantine))
        assertFalse(Files.exists(directory.resolve("settings.json")))
    }

    @Test
    fun `saved priorities survive partial inventory gaps and repository restarts`() = runBlocking {
        val repository = SettingsRepository(directory)
        repository.update { current ->
            current.copy(selectedGamePriority = listOf("Gap Game", "Available Game"))
        }

        val restarted = SettingsRepository(directory)

        assertEquals(listOf("Gap Game", "Available Game"), restarted.settings.value.selectedGamePriority)
    }

    @Test
    fun `persisted priority and exclusion collections remain resource bounded`() = runBlocking {
        val repository = SettingsRepository(directory)
        repository.update { current ->
            current.copy(
                selectedGamePriority = (1..700).map { index -> "Game $index" },
                excludedCampaignIds = (1..700).map { index -> "campaign-$index" }.toSet(),
            )
        }

        val restarted = SettingsRepository(directory).settings.value
        assertEquals(500, restarted.selectedGamePriority.size)
        assertEquals(500, restarted.excludedCampaignIds.size)
        assertTrue(Files.size(directory.resolve("settings.json")) < 512 * 1024)
    }
}
