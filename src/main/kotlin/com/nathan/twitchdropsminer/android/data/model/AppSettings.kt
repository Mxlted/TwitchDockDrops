package com.nathan.twitchdropsminer.android.data.model

private const val MinWatchIntervalSeconds = 20
private const val MaxWatchIntervalSeconds = 300
private const val DefaultWatchIntervalSeconds = 59
private const val MinInventoryRefreshMinutes = 15
private const val MaxInventoryRefreshMinutes = 180
private const val DefaultInventoryRefreshMinutes = 60
private const val MaxSelectedGamePriorities = 500
private const val MaxExcludedCampaignIds = 500
private const val MaxSavedGameNameLength = 200
private const val MaxSavedCampaignIdLength = 256

data class AppSettings(
    val hasCompletedOnboarding: Boolean = false,
    val watchIntervalSeconds: Int = DefaultWatchIntervalSeconds,
    val inventoryRefreshMinutes: Int = DefaultInventoryRefreshMinutes,
    val runInForeground: Boolean = true,
    val keepActiveScreenMode: Boolean = false,
    val fallbackToOtherGames: Boolean = false,
    val autoModePriorityOrder: List<AutoModePriority> = AutoModePriority.DefaultOrder,
    val excludedCampaignIds: Set<String> = emptySet(),
    // Legacy campaign IDs are retained so older saved preferences keep loading.
    val selectedCampaignIds: Set<String> = emptySet(),
    // Legacy unordered game set. selectedGamePriority is the source of truth.
    val selectedGames: Set<String> = emptySet(),
    val selectedGamePriority: List<String> = emptyList(),
    val debugLogging: Boolean = false,
    val advancedBackendMode: Boolean = false,
    val backendUrl: String = "",
    // Kept for compatibility with the optional backend/debug helpers and older tests.
    val pollIntervalSeconds: Int = DefaultWatchIntervalSeconds,
    val monitorInForeground: Boolean = true,
) {
    val normalizedBackendUrl: String
        get() = backendUrl.trim().trimEnd('/')

    val hasBackend: Boolean
        get() = normalizedBackendUrl.isNotBlank()

    val canConnect: Boolean
        get() = true

    val gamePriorityLabel: String
        get() = if (selectedGamePriority.isEmpty()) {
            "Auto"
        } else {
            "${selectedGamePriority.size} games"
        }

    val hasGamePriority: Boolean
        get() = selectedGamePriority.isNotEmpty()

    fun isGamePrioritized(gameName: String): Boolean =
        selectedGamePriority.any { it.equals(gameName, ignoreCase = true) }

    fun gamePriorityIndex(gameName: String): Int? =
        selectedGamePriority.indexOfFirst { it.equals(gameName, ignoreCase = true) }
            .takeIf { it >= 0 }

    fun allowsCampaign(campaign: Campaign): Boolean =
        !isCampaignExcluded(campaign) && (!hasGamePriority || isGamePrioritized(campaign.gameName))

    fun isCampaignSelected(campaign: Campaign): Boolean =
        isGamePrioritized(campaign.gameName)

    fun isCampaignExcluded(campaign: Campaign): Boolean =
        excludedCampaignIds.any { it.equals(campaign.id, ignoreCase = true) }

    fun normalized(): AppSettings {
        val normalizedWatchInterval = watchIntervalSeconds.coerceIn(
            MinWatchIntervalSeconds,
            MaxWatchIntervalSeconds,
        )
        val normalizedRefresh = inventoryRefreshMinutes.coerceIn(
            MinInventoryRefreshMinutes,
            MaxInventoryRefreshMinutes,
        )
        val normalizedGamePriority = selectedGamePriority
            .ifEmpty { selectedGames.sortedWith(String.CASE_INSENSITIVE_ORDER) }
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= MaxSavedGameNameLength }
            .distinctBy { it.lowercase() }
            .take(MaxSelectedGamePriorities)
        val normalizedExcludedCampaignIds = excludedCampaignIds
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= MaxSavedCampaignIdLength }
            .distinctBy { it.lowercase() }
            .take(MaxExcludedCampaignIds)
            .toSet()
        return copy(
            backendUrl = normalizedBackendUrl,
            watchIntervalSeconds = normalizedWatchInterval,
            inventoryRefreshMinutes = normalizedRefresh,
            autoModePriorityOrder = AutoModePriority.normalize(autoModePriorityOrder),
            excludedCampaignIds = normalizedExcludedCampaignIds,
            selectedGames = normalizedGamePriority.toSet(),
            selectedGamePriority = normalizedGamePriority,
            pollIntervalSeconds = pollIntervalSeconds.coerceIn(
                MinWatchIntervalSeconds,
                MaxInventoryRefreshMinutes * 60,
            ),
            monitorInForeground = runInForeground,
        )
    }
}
