package com.nathan.twitchdropsminer.android.data.model

import java.time.Instant

enum class RuntimePhase {
    Stopped,
    Connecting,
    Idle,
    Fetching,
    Authenticating,
    LoadingInventory,
    SelectingCampaign,
    FindingChannel,
    Watching,
    Claiming,
    Error,
}
data class RuntimeSnapshot(
    val phase: RuntimePhase = RuntimePhase.Stopped,
    val account: LoginSession = LoginSession(),
    val currentTask: String = "Not connected",
    val progressSummary: String = "No campaign data",
    val lastUpdate: Instant? = null,
    val campaigns: List<Campaign> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val backendConsole: List<String> = emptyList(),
    val activity: List<RuntimeActivity> = emptyList(),
    val selectedCampaignIds: Set<String> = emptySet(),
    val currentChannel: Channel? = null,
    val activeCampaign: Campaign? = null,
    val activeDrop: CampaignDrop? = null,
    val dropsClaimedThisSession: Int = 0,
    val miningActive: Boolean = false,
    val channelSearchInProgress: Boolean = false,
    val error: String? = null,
) {
    val isRunning: Boolean
        get() = miningActive

    val activeCampaignCount: Int
        get() = campaigns.count { it.active }

    val watchingChannel: Channel?
        get() = currentChannel ?: channels.firstOrNull { it.watching }
}

data class RuntimeActivity(
    val timestamp: Instant,
    val state: RuntimePhase,
    val title: String,
    val detail: String? = null,
) {
    fun toLine(): String =
        "${timestamp} [$state] $title${detail?.let { ": $it" } ?: ""}"
}

data class LocalLogEntry(
    val timestamp: Instant,
    val level: String,
    val message: String,
) {
    fun toLine(): String = "${timestamp} [$level] $message"

    companion object {
        fun fromLine(line: String): LocalLogEntry {
            val parts = line.split(" ", limit = 3)
            return if (parts.size == 3) {
                runCatching {
                    LocalLogEntry(Instant.parse(parts[0]), parts[1].trim('[', ']'), parts[2])
                }.getOrDefault(LocalLogEntry(Instant.EPOCH, "INFO", line))
            } else {
                LocalLogEntry(Instant.EPOCH, "INFO", line)
            }
        }
    }
}
