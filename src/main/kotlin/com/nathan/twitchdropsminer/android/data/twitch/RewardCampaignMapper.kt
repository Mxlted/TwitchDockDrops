package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.RewardCampaign
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class RewardCampaignListing(
    val campaigns: List<RewardCampaign>,
    val available: Boolean,
)

internal fun mapRewardCampaigns(value: JsonElement?): RewardCampaignListing {
    val records = value as? JsonArray ?: return RewardCampaignListing(emptyList(), false)
    var complete = records.size <= 500
    val campaigns = records.take(500).mapNotNull { element ->
        val record = element as? JsonObject
        val id = record?.text("id", 200)
        val name = record?.text("name")
        val start = record?.text("startsAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val end = record?.text("endsAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (record == null || id == null || name == null || start == null || end == null || end <= start) {
            complete = false
            return@mapNotNull null
        }
        val groups = (record["rewardGroups"] as? JsonArray).orEmpty()
        val rewards = groups.flatMap { ((it as? JsonObject)?.get("rewards") as? JsonArray).orEmpty() } +
            (record["rewards"] as? JsonArray).orEmpty()
        RewardCampaign(
            id = id,
            name = name,
            brand = record.text("brand"),
            gameName = (record["game"] as? JsonObject)?.text("displayName"),
            summary = record.text("summary", 2000),
            startsAt = start,
            endsAt = end,
            rewardNames = rewards.mapNotNull { (it as? JsonObject)?.text("name") }.distinct().take(50),
        )
    }.distinctBy { it.id }
    return RewardCampaignListing(campaigns, complete)
}

private fun JsonObject.text(key: String, limit: Int = 300): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(limit)
