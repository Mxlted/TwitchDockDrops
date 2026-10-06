package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import java.time.Instant
import kotlinx.serialization.json.*

internal data class TvAccountInventory(
    val campaigns: List<Campaign>,
    val awards: Map<String, Instant>,
    val rejectedIds: Set<String>,
    val diagnostics: List<String>,
)

internal fun parseTvAccountInventory(response: JsonObject): TvAccountInventory {
    val inventory = (response["data"] as? JsonObject)?.get("currentUser") as? JsonObject
    val data = inventory?.get("inventory") as? JsonObject
    val records = data?.get("dropCampaignsInProgress") as? JsonArray
    val awards = data?.get("gameEventDrops") as? JsonArray
    if (response["errors"]?.let { it !is JsonArray || it.isNotEmpty() } == true || records == null || awards == null || records.size > 2000 || awards.size > 10000) {
        throw TwitchApiException(TwitchApiErrorType.UnexpectedResponse, "Twitch inventory is unavailable or malformed; TV credentials preserved.")
    }
    var partial = false
    val awardMap = linkedMapOf<String, Instant>()
    awards.forEach { element ->
        val parsed = runCatching {
            val award = element.jsonObject
            val id = award.getValue("id").jsonPrimitive.content.also { require(it.isNotBlank()) }
            id to Instant.parse(award.getValue("lastAwardedAt").jsonPrimitive.content)
        }.getOrNull()
        if (parsed == null) partial = true else awardMap[parsed.first] = parsed.second
    }
    val seen = mutableSetOf<String>()
    val rejected = mutableSetOf<String>()
    val campaigns = linkedMapOf<String, Campaign>()
    records.forEach { element ->
        val record = element as? JsonObject
        val id = (record?.get("id") as? JsonPrimitive)?.contentOrNull
        if (id != null && !seen.add(id)) { rejected += id; campaigns.remove(id); partial = true; return@forEach }
        val campaign = runCatching {
            requireNotNull(record)
            val metadata = validateCampaignMetadata(record)
            require(record["self"] == null || record["self"] == JsonNull || record["self"] is JsonObject)
            val self = record["self"] as? JsonObject
            val linked = (self?.get("isAccountConnected") as? JsonPrimitive)?.booleanOrNull
            require(self == null || (linked != null && !(self["isAccountConnected"] as JsonPrimitive).isString))
            val dropRecords = record.getValue("timeBasedDrops").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
            dropRecords.values.forEach {
                val state = it.jsonObject["self"]
                if (state != null && state != JsonNull) {
                    val obj = state.jsonObject
                    require(!obj.getValue("isClaimed").jsonPrimitive.isString && obj.getValue("isClaimed").jsonPrimitive.booleanOrNull != null)
                    require(!obj.getValue("currentMinutesWatched").jsonPrimitive.isString && obj.getValue("currentMinutesWatched").jsonPrimitive.intOrNull in 0..100000)
                    require(obj["dropInstanceID"] == null || obj["dropInstanceID"] == JsonNull || obj["dropInstanceID"]!!.jsonPrimitive.isString)
                    require((obj["dropInstanceID"] as? JsonPrimitive)?.contentOrNull.orEmpty().length <= 2048)
                }
            }
            val mapped = TwitchCampaignMapper.mapCampaign(JsonObject(record + ("allow" to metadata.getValue("allow"))), awardMap)
            require(mapped.diagnostics.isEmpty())
            requireNotNull(mapped.campaign).let { value -> value.copy(
                publicCatalog = true, linked = linked == true, linkStatusKnown = linked != null,
                drops = value.drops.map { drop ->
                    val state = dropRecords.getValue(drop.id).jsonObject["self"] as? JsonObject
                    drop.copy(progressKnown = state != null || drop.isClaimed,
                        claimEvidenceKnown = state != null || drop.isClaimed,
                        requiredMinutes = if ((dropRecords.getValue(drop.id).jsonObject["requiredSubs"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true) 0 else drop.requiredMinutes)
                },
            ) }
        }.getOrNull()
        if (campaign == null) { partial = true; if (id != null) rejected += id }
        else campaigns[campaign.id] = campaign
    }
    return TvAccountInventory(campaigns.values.toList(), awardMap, rejected,
        if (partial) listOf("Twitch inventory is partial; account state for rejected records is unknown.") else emptyList())
}

internal fun mergeTvSources(account: TvAccountInventory, catalog: PublicCatalogResult): CampaignInventory {
    val accountById = account.campaigns.associateBy { it.id }
    val campaigns = catalog.campaigns.filterNot { it.id in account.rejectedIds }.map { metadata ->
        val owned = accountById[metadata.id]
        if (owned != null) {
            // Twitch owns overlapping state. Catalog-only rewards carry no account assumptions.
            val drops = owned.drops + metadata.drops.filterNot { candidate -> owned.drops.any { it.id == candidate.id } }
            owned.copy(drops = drops, totalDrops = drops.size)
        } else metadata.copy(drops = metadata.drops.map { drop ->
            val claimed = drop.rewards.isNotEmpty() && drop.rewards.all { reward ->
                val time = account.awards[reward.id]
                time != null && drop.startsAt != null && drop.endsAt != null && time >= drop.startsAt && time < drop.endsAt
            }
            drop.copy(isClaimed = claimed, currentMinutes = if (claimed) drop.requiredMinutes else 0,
                progressKnown = claimed, claimEvidenceKnown = claimed)
        }).let { it.copy(claimedDrops = it.drops.count { drop -> drop.isClaimed }) }
    }
    return CampaignInventory((campaigns + account.campaigns).distinctBy { it.id },
        diagnostics = account.diagnostics + listOfNotNull(catalog.problem), publicCatalog = true,
        catalogUpdatedAt = catalog.updatedAt, rejectedAccountIds = account.rejectedIds)
}

/** Preserve bounded metadata and confirmed history without reusing stale claim eligibility. */
internal fun retainTvInventory(previous: List<Campaign>, loaded: CampaignInventory, now: Instant): List<Campaign> {
    val old = previous.associateBy { it.id }
    val merged = loaded.campaigns.map { campaign ->
        val known = old[campaign.id]
        if (known == null) campaign else campaign.copy(drops = campaign.drops.map { drop ->
            val prior = known.drops.find { it.id == drop.id }
            if (drop.progressKnown || prior == null) drop else drop.copy(
                currentMinutes = prior.currentMinutes, isClaimed = prior.isClaimed,
                progressKnown = prior.isClaimed, claimEvidenceKnown = prior.isClaimed,
            )
        }).let { it.copy(claimedDrops = it.drops.count { drop -> drop.isClaimed }) }
    }
    val ids = merged.mapTo(mutableSetOf()) { it.id }
    val retained = if (!loaded.isPartial) emptyList() else previous.filter {
        it.id !in ids && it.endsAt?.isAfter(now) == true
    }.map { campaign -> campaign.copy(
        linked = false, linkStatusKnown = false,
        accountStateUsable = campaign.id !in loaded.rejectedAccountIds,
        drops = campaign.drops.map { it.copy(canClaim = false, claimId = null,
            progressKnown = it.isClaimed, claimEvidenceKnown = it.isClaimed,
            eligibleByFilter = it.eligibleByFilter && campaign.id !in loaded.rejectedAccountIds) },
    ) }
    return (merged + retained).take(4000)
}
