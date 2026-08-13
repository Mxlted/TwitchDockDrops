package app.twitchdockdrops

import app.twitchdockdrops.security.SafeText
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.DropReward
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.RuntimeActivity
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StateJson(
    private val startedAt: Instant = Instant.now(),
) {
    private val json = Json { explicitNulls = true }

    fun encode(
        snapshot: RuntimeSnapshot,
        settings: AppSettings,
        logs: List<LocalLogEntry>,
    ): String {
        val now = Instant.now()
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                putJson("server") {
                    put("version", "0.1.0")
                    put("now", now.toString())
                    put("uptimeSeconds", Duration.between(startedAt, now).seconds.coerceAtLeast(0))
                }
                put("settings", settings.toJson())
                put("snapshot", snapshot.toJson(settings))
                put("logs", logs.tailToJsonArray(250, LocalLogEntry::toJson))
            },
        )
    }
}

private fun AppSettings.toJson(): JsonObject = buildJsonObject {
    put("watchIntervalSeconds", watchIntervalSeconds)
    put("inventoryRefreshMinutes", inventoryRefreshMinutes)
    put("fallbackToOtherGames", fallbackToOtherGames)
    put("debugLogging", debugLogging)
    put("selectedGamePriority", selectedGamePriority.toJsonArray { buildStringJson(it) })
    put("excludedCampaignIds", excludedCampaignIds.sorted().toJsonArray { buildStringJson(it) })
    put(
        "autoModePriorityOrder",
        autoModePriorityOrder.toJsonArray { option ->
            buildJsonObject {
                put("key", option.storageKey)
                put("title", option.title)
                put("description", option.description)
            }
        },
    )
}

private fun RuntimeSnapshot.toJson(settings: AppSettings): JsonObject = buildJsonObject {
    put("phase", phase.name.lowercase())
    put("currentTask", SafeText.diagnostic(currentTask))
    put("progressSummary", SafeText.diagnostic(progressSummary))
    putInstant("lastUpdate", lastUpdate)
    put("account", account.toJson())
    put("campaigns", campaigns.toJsonArray { it.toJson(settings) })
    put("channels", channels.toJsonArray(Channel::toJson))
    put("activity", activity.tailToJsonArray(100, RuntimeActivity::toJson))
    put("currentChannel", currentChannel?.toJson() ?: JsonNull)
    put("activeCampaign", activeCampaign?.toJson(settings) ?: JsonNull)
    put("activeDrop", activeDrop?.toJson() ?: JsonNull)
    put("dropsClaimedThisSession", dropsClaimedThisSession)
    put("miningActive", miningActive)
    put("channelSearchInProgress", channelSearchInProgress)
    putNullable("error", error?.let { SafeText.diagnostic(it) })
    put("activeCampaignCount", activeCampaignCount)
}

private fun LoginSession.toJson(): JsonObject = buildJsonObject {
    put("state", state.name.lowercase())
    put("statusText", SafeText.diagnostic(statusText))
    putNullable("userId", userId)
    putNullable("oauthUrl", oauthUrl?.takeIf(::isExpectedTwitchActivationUrl))
    putNullable("oauthCode", oauthCode)
    putInstant("expiresAt", expiresAt)
    put("authenticated", isAuthenticated)
    put("actionRequired", isActionRequired)
}

private fun Campaign.toJson(settings: AppSettings): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("gameName", gameName)
    putNullable("gameBoxArtUrl", gameBoxArtUrl)
    putNullable("campaignUrl", campaignUrl)
    putNullable("linkUrl", linkUrl)
    putInstant("startsAt", startsAt)
    putInstant("endsAt", endsAt)
    put("linked", linked)
    put("linkStatusKnown", linkStatusKnown)
    put("active", active)
    put("upcoming", upcoming)
    put("expired", expired)
    put("claimedDrops", claimedDrops)
    put("totalDrops", totalDrops)
    put("remainingMinutes", remainingMinutes)
    put("progress", progress.toDouble())
    put("selected", settings.isCampaignSelected(this@toJson))
    put("excluded", settings.isCampaignExcluded(this@toJson))
    put("priorityIndex", settings.gamePriorityIndex(gameName) ?: -1)
    put("earnable", canEarnLocally)
    put("drops", drops.toJsonArray(CampaignDrop::toJson))
    put("allowedChannels", allowedChannels.toJsonArray(Channel::toJson))
}

private fun CampaignDrop.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("currentMinutes", currentMinutes)
    put("requiredMinutes", requiredMinutes)
    put("remainingMinutes", remainingMinutes)
    put("progress", progressFraction.toDouble())
    put("claimed", isClaimed)
    put("canClaim", canClaim)
    put("completed", hasCompletedProgress)
    putInstant("startsAt", startsAt)
    putInstant("endsAt", endsAt)
    put("rewards", rewards.toJsonArray(DropReward::toJson))
}

private fun DropReward.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("type", type)
    putNullable("imageUrl", imageUrl)
}

private fun Channel.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    putNullable("game", game)
    viewers?.let { put("viewers", it) } ?: put("viewers", JsonNull)
    put("online", online)
    put("dropsEnabled", dropsEnabled)
    put("aclBased", aclBased)
    put("watching", watching)
    putNullable("title", title)
    put("statusLabel", statusLabel)
}

private fun RuntimeActivity.toJson(): JsonObject = buildJsonObject {
    put("timestamp", timestamp.toString())
    put("state", state.name.lowercase())
    put("title", SafeText.diagnostic(title))
    putNullable("detail", detail?.let { SafeText.diagnostic(it) })
}

private fun LocalLogEntry.toJson(): JsonObject = buildJsonObject {
    put("timestamp", timestamp.toString())
    put("level", level)
    put("message", SafeText.diagnostic(message, 1_024))
}

private inline fun JsonObjectBuilder.putJson(name: String, block: JsonObjectBuilder.() -> Unit) {
    put(name, buildJsonObject(block))
}

private fun JsonObjectBuilder.putNullable(name: String, value: String?) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun JsonObjectBuilder.putInstant(name: String, value: Instant?) {
    putNullable(name, value?.toString())
}

private fun buildStringJson(value: String) = kotlinx.serialization.json.JsonPrimitive(value)

private inline fun <T> Iterable<T>.toJsonArray(transform: (T) -> kotlinx.serialization.json.JsonElement): JsonArray =
    buildJsonArray { forEach { add(transform(it)) } }

private inline fun <T> List<T>.tailToJsonArray(
    maximumSize: Int,
    transform: (T) -> kotlinx.serialization.json.JsonElement,
): JsonArray = buildJsonArray {
    val firstIndex = (this@tailToJsonArray.size - maximumSize).coerceAtLeast(0)
    for (index in firstIndex until this@tailToJsonArray.size) {
        add(transform(this@tailToJsonArray[index]))
    }
}

private fun isExpectedTwitchActivationUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host?.lowercase() in setOf("www.twitch.tv", "twitch.tv") &&
        uri.path.orEmpty().trimEnd('/') == "/activate" &&
        uri.rawUserInfo == null
}
