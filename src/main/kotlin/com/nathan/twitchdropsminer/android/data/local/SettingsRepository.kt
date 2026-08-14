package com.nathan.twitchdropsminer.android.data.local

import app.twitchdockdrops.storage.AtomicFiles
import app.twitchdockdrops.security.SafeText
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.AutoModePriority
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SettingsRepository(dataDirectory: Path) {
    private val settingsFile = dataDirectory.resolve("settings.json")
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val initialLoad = load()
    private val mutableSettings = MutableStateFlow(initialLoad.settings)

    val settings: StateFlow<AppSettings> = mutableSettings
    val loadStatus: PersistenceStatus = initialLoad.status

    suspend fun update(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        val updated = transform(mutableSettings.value).normalized()
        withContext(Dispatchers.IO) {
            AtomicFiles.writeString(settingsFile, encode(updated), ownerOnly = true)
        }
        mutableSettings.value = updated
    }

    suspend fun completeOnboarding() = update { it.copy(hasCompletedOnboarding = true) }

    suspend fun resetSessionSettings() = update {
        it.copy(
            selectedCampaignIds = emptySet(),
            selectedGames = emptySet(),
            selectedGamePriority = emptyList(),
            excludedCampaignIds = emptySet(),
            runInForeground = true,
            monitorInForeground = true,
        )
    }

    suspend fun resetSettings() = update {
        AppSettings(hasCompletedOnboarding = it.hasCompletedOnboarding).normalized()
    }

    suspend fun toggleGamePriority(gameName: String) = update { current ->
        current.withGamePriority(GamePriorityOrder.toggle(current.selectedGamePriority, gameName))
    }

    suspend fun moveGamePriority(gameName: String, offset: Int) {
        if (offset == 0) return
        update { current ->
            current.withGamePriority(GamePriorityOrder.move(current.selectedGamePriority, gameName, offset))
        }
    }

    suspend fun setGamePriority(gameName: String, priorityNumber: Int) = update { current ->
        current.withGamePriority(
            GamePriorityOrder.set(current.selectedGamePriority, gameName, priorityNumber),
        )
    }

    suspend fun clearGamePriority() = update {
        it.copy(
            selectedCampaignIds = emptySet(),
            selectedGames = emptySet(),
            selectedGamePriority = emptyList(),
        )
    }

    suspend fun setCampaignExclusion(campaignIds: Collection<String>, excluded: Boolean) =
        update { current ->
            current.copy(
                excludedCampaignIds = CampaignExclusionIds.update(
                    current.excludedCampaignIds,
                    campaignIds,
                    excluded,
                ),
            )
        }

    suspend fun moveAutoModePriority(option: AutoModePriority, offset: Int) {
        if (offset == 0) return
        update { current ->
            val order = AutoModePriority.normalize(current.autoModePriorityOrder).toMutableList()
            val currentIndex = order.indexOf(option)
            val targetIndex = (currentIndex + offset).coerceIn(0, order.lastIndex)
            if (currentIndex != targetIndex) {
                order.add(targetIndex, order.removeAt(currentIndex))
            }
            current.copy(autoModePriorityOrder = order)
        }
    }

    private fun load(): SettingsLoadResult {
        if (!Files.exists(settingsFile)) {
            return SettingsLoadResult(
                AppSettings(hasCompletedOnboarding = true).normalized(),
                PersistenceStatus(PersistenceFileState.Absent),
            )
        }
        return try {
            require(Files.size(settingsFile) <= MaxSettingsBytes) { "Settings file exceeds the safe size limit." }
            val root = Json.parseToJsonElement(Files.readString(settingsFile)) as JsonObject
            val loaded = AppSettings(
                hasCompletedOnboarding = root.boolean("hasCompletedOnboarding", true),
                watchIntervalSeconds = root.int("watchIntervalSeconds", 59),
                inventoryRefreshMinutes = root.int("inventoryRefreshMinutes", 60),
                runInForeground = root.boolean("runInForeground", true),
                keepActiveScreenMode = root.boolean("keepActiveScreenMode", false),
                fallbackToOtherGames = root.boolean("fallbackToOtherGames", true),
                autoModePriorityOrder = root.stringList("autoModePriorityOrder")
                    .mapNotNull(AutoModePriority::fromStorageKey),
                excludedCampaignIds = root.stringList("excludedCampaignIds").toSet(),
                selectedCampaignIds = root.stringList("selectedCampaignIds").toSet(),
                selectedGames = root.stringList("selectedGames").toSet(),
                selectedGamePriority = root.stringList("selectedGamePriority"),
                debugLogging = root.boolean("debugLogging", false),
                advancedBackendMode = false,
                backendUrl = "",
                pollIntervalSeconds = root.int("pollIntervalSeconds", 59),
                monitorInForeground = root.boolean("monitorInForeground", true),
            ).normalized()
            SettingsLoadResult(loaded, PersistenceStatus(PersistenceFileState.Loaded))
        } catch (error: java.nio.file.AccessDeniedException) {
            SettingsLoadResult(
                AppSettings(hasCompletedOnboarding = true).normalized(),
                PersistenceStatus(
                    PersistenceFileState.Unreadable,
                    "Saved settings are unreadable; correct data-directory permissions before changing settings.",
                ),
            )
        } catch (error: SecurityException) {
            SettingsLoadResult(
                AppSettings(hasCompletedOnboarding = true).normalized(),
                PersistenceStatus(
                    PersistenceFileState.Unreadable,
                    "Saved settings are unreadable; correct data-directory permissions before changing settings.",
                ),
            )
        } catch (error: Throwable) {
            val quarantined = quarantineCorruptSettings()
            val action = if (quarantined) {
                "The corrupt file was quarantined and defaults were loaded."
            } else {
                "The corrupt file was preserved; correct or remove it before saving settings."
            }
            SettingsLoadResult(
                AppSettings(hasCompletedOnboarding = true).normalized(),
                PersistenceStatus(
                    PersistenceFileState.Corrupt,
                    "Saved settings could not be parsed. $action " + SafeText.diagnostic(error.message, 160),
                ),
            )
        }
    }

    private fun quarantineCorruptSettings(): Boolean = runCatching {
        val suffix = Instant.now().toEpochMilli()
        val quarantine = settingsFile.resolveSibling("settings.json.corrupt-$suffix")
        Files.move(settingsFile, quarantine, StandardCopyOption.ATOMIC_MOVE)
        AtomicFiles.restrictToOwner(quarantine)
        true
    }.getOrElse { false }

    private fun encode(settings: AppSettings): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", 1)
            put("hasCompletedOnboarding", settings.hasCompletedOnboarding)
            put("watchIntervalSeconds", settings.watchIntervalSeconds)
            put("inventoryRefreshMinutes", settings.inventoryRefreshMinutes)
            put("runInForeground", settings.runInForeground)
            put("keepActiveScreenMode", settings.keepActiveScreenMode)
            put("fallbackToOtherGames", settings.fallbackToOtherGames)
            put("autoModePriorityOrder", settings.autoModePriorityOrder.map { it.storageKey }.toJsonArray())
            put("excludedCampaignIds", settings.excludedCampaignIds.sorted().toJsonArray())
            put("selectedCampaignIds", settings.selectedCampaignIds.sorted().toJsonArray())
            put("selectedGames", settings.selectedGames.sorted().toJsonArray())
            put("selectedGamePriority", settings.selectedGamePriority.toJsonArray())
            put("debugLogging", settings.debugLogging)
            put("pollIntervalSeconds", settings.pollIntervalSeconds)
            put("monitorInForeground", settings.monitorInForeground)
        },
    )
}

private const val MaxSettingsBytes = 512L * 1_024L

private data class SettingsLoadResult(
    val settings: AppSettings,
    val status: PersistenceStatus,
)

private object GamePriorityOrder {
    fun normalize(current: List<String>): List<String> = current
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)

    fun toggle(current: List<String>, gameName: String): List<String> {
        val normalizedName = gameName.trim()
        val result = normalize(current).toMutableList()
        if (normalizedName.isEmpty()) return result
        val index = result.indexOfFirst { it.equals(normalizedName, ignoreCase = true) }
        if (index >= 0) result.removeAt(index) else result.add(normalizedName)
        return result
    }

    fun move(current: List<String>, gameName: String, offset: Int): List<String> {
        val result = normalize(current).toMutableList()
        val index = result.indexOfFirst { it.equals(gameName.trim(), ignoreCase = true) }
        if (index < 0 || offset == 0) return result
        val target = (index + offset).coerceIn(0, result.lastIndex)
        if (index != target) result.add(target, result.removeAt(index))
        return result
    }

    fun set(current: List<String>, gameName: String, priorityNumber: Int): List<String> {
        val normalizedName = gameName.trim()
        val result = normalize(current).toMutableList()
        if (normalizedName.isEmpty()) return result
        val index = result.indexOfFirst { it.equals(normalizedName, ignoreCase = true) }
        val label = if (index >= 0) result.removeAt(index) else normalizedName
        result.add((priorityNumber.coerceAtLeast(1) - 1).coerceIn(0, result.size), label)
        return result
    }
}

private object CampaignExclusionIds {
    fun update(current: Set<String>, requested: Collection<String>, excluded: Boolean): Set<String> {
        val existing = normalize(current)
        val incoming = normalize(requested)
        val keys = incoming.map(String::lowercase).toSet()
        val retained = existing.filterNot { it.lowercase() in keys }
        return normalize(if (excluded) retained + incoming else retained)
    }

    private fun normalize(values: Collection<String>): Set<String> = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
        .toSet()
}

private fun AppSettings.withGamePriority(priority: List<String>): AppSettings = copy(
    selectedCampaignIds = emptySet(),
    selectedGames = priority.toSet(),
    selectedGamePriority = priority,
)

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    for (value in this@toJsonArray) add(JsonPrimitive(value))
}

private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

private fun JsonObject.int(key: String, fallback: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: fallback

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
