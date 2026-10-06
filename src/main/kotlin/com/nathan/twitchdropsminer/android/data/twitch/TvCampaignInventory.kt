package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import java.time.Instant
import kotlinx.serialization.json.*

internal data class TvAccountInventory(
    val campaigns: List<Campaign>,
    val awards: Map<String, Instant>,
    val rejectedIds: Set<String>,
    val diagnostics: List<String>,
    val usableForLogin: Boolean,
)

/** Inventory is a different projection from the public catalog, not a catalog record with self added. */
private fun normalizeTvCampaignMetadata(record: JsonObject): JsonObject {
    val game = record.getValue("game").jsonObject
    val allow = record.getValue("allow").jsonObject
    val channels = allow["channels"]
    val flag = allow["isEnabled"]
    val normalizedAllow = if (flag == null || flag == JsonNull) {
        // Inventory can omit isEnabled: the returned channel list itself is the restriction.
        require(channels == JsonNull || channels is JsonArray)
        JsonObject(allow + ("isEnabled" to JsonPrimitive(channels is JsonArray && channels.isNotEmpty())))
    } else allow
    val rawDrops = record.getValue("timeBasedDrops").jsonArray.also { require(it.size <= 256) }
    val drops = rawDrops.map { element ->
        val drop = element.jsonObject
        val benefits = drop.getValue("benefitEdges").jsonArray.also { require(it.size in 1..64) }.map { edge ->
            val obj = edge.jsonObject
            val benefit = obj.getValue("benefit").jsonObject
            val kind = benefit["distributionType"]
            JsonObject(obj + ("benefit" to if (kind == null || kind == JsonNull)
                JsonObject(benefit + ("distributionType" to JsonPrimitive("UNKNOWN"))) else benefit))
        }
        JsonObject(drop + ("benefitEdges" to JsonArray(benefits)))
    }
    val status = record["status"]
    val normalized = JsonObject(record + mapOf(
        "game" to if (game["displayName"] == null || game["displayName"] == JsonNull)
            JsonObject(game + ("displayName" to game.getValue("name"))) else game,
        "allow" to normalizedAllow,
        "timeBasedDrops" to JsonArray(drops),
        // The shared mapper derives active/upcoming/expired from these validated dates.
        "status" to if (status == null || status == JsonNull) JsonPrimitive("ACTIVE") else status,
    ))
    val metadata = validateCampaignMetadata(normalized)
    // Metadata validation deliberately removes self. Restore only Twitch's separately validated state.
    return JsonObject(metadata + mapOf(
        "self" to (record["self"] ?: JsonNull),
        "timeBasedDrops" to JsonArray(metadata.getValue("timeBasedDrops").jsonArray.mapIndexed { index, drop ->
            JsonObject(drop.jsonObject + ("self" to (drops[index]["self"] ?: JsonNull)))
        }),
    ))
}

internal fun parseTvAccountInventory(response: JsonObject): TvAccountInventory {
    val inventory = (response["data"] as? JsonObject)?.get("currentUser") as? JsonObject
    val data = inventory?.get("inventory") as? JsonObject
    val records = data?.get("dropCampaignsInProgress") as? JsonArray
    val awards = data?.get("gameEventDrops") as? JsonArray
    if (response["errors"]?.let { it !is JsonArray || it.isNotEmpty() } == true || records == null || awards == null || records.size > 2000 || awards.size > 10000) {
        throw TwitchApiException(TwitchApiErrorType.UnexpectedResponse, "Twitch inventory is unavailable or malformed; TV credentials preserved.")
    }
    var rejectedAwards = 0
    val failures = linkedMapOf<String, Int>()
    fun reject(reason: String) { failures[reason] = (failures[reason] ?: 0) + 1 }
    val awardMap = linkedMapOf<String, Instant>()
    awards.forEach { element ->
        val parsed = runCatching {
            val award = element.jsonObject
            val id = award.getValue("id").jsonPrimitive.also { require(it.isString && it.content.isNotBlank() && it.content.length <= 2048) }.content
            id to Instant.parse(award.getValue("lastAwardedAt").jsonPrimitive.content)
        }.getOrNull()
        if (parsed == null) rejectedAwards++ else {
            // Multiple awards of one benefit are legal; response order must not change the evidence.
            awardMap[parsed.first] = maxOf(awardMap[parsed.first] ?: Instant.MIN, parsed.second)
        }
    }
    val seen = mutableSetOf<String>()
    val rejected = mutableSetOf<String>()
    val campaigns = linkedMapOf<String, Campaign>()
    records.forEach { element ->
        val record = element as? JsonObject
        val id = (record?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (id != null && !seen.add(id)) { rejected += id; campaigns.remove(id); reject("duplicate campaign IDs"); return@forEach }
        var stage = "campaign metadata"
        val campaign = runCatching {
            requireNotNull(record)
            val metadata = normalizeTvCampaignMetadata(record)
            stage = "campaign account state"
            require(record["self"] == null || record["self"] == JsonNull || record["self"] is JsonObject)
            val self = record["self"] as? JsonObject
            val linked = (self?.get("isAccountConnected") as? JsonPrimitive)?.booleanOrNull
            require(self?.get("isAccountConnected") == null || self["isAccountConnected"] == JsonNull ||
                (linked != null && !(self["isAccountConnected"] as JsonPrimitive).isString))
            stage = "drop account state"
            val dropRecords = metadata.getValue("timeBasedDrops").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
            val validatedDrops = dropRecords.values.map {
                val drop = it.jsonObject
                val state = it.jsonObject["self"]
                if (state != null && state != JsonNull) {
                    val obj = state.jsonObject
                    require(!obj.getValue("isClaimed").jsonPrimitive.isString && obj.getValue("isClaimed").jsonPrimitive.booleanOrNull != null)
                    val minutes = obj["currentMinutesWatched"]
                    val claimedWithoutMinutes = obj.getValue("isClaimed").jsonPrimitive.boolean && (minutes == null || minutes == JsonNull)
                    require(claimedWithoutMinutes || (minutes is JsonPrimitive && !minutes.isString && minutes.intOrNull in 0..100000))
                    require(obj["dropInstanceID"] == null || obj["dropInstanceID"] == JsonNull || obj["dropInstanceID"]!!.jsonPrimitive.isString)
                    require((obj["dropInstanceID"] as? JsonPrimitive)?.contentOrNull.orEmpty().length <= 2048)
                    if (claimedWithoutMinutes) JsonObject(drop + ("self" to JsonObject(obj + ("currentMinutesWatched" to JsonPrimitive(0)))))
                    else drop
                } else drop
            }
            val mapped = TwitchCampaignMapper.mapCampaign(JsonObject(metadata + ("timeBasedDrops" to JsonArray(validatedDrops))), awardMap)
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
        if (campaign == null) { reject(stage); if (id != null) rejected += id }
        else campaigns[campaign.id] = campaign
    }
    val diagnostics = buildList {
        if (failures.isNotEmpty()) add("Twitch inventory is partial: ${campaigns.size} of ${records.size} campaigns usable; " +
            failures.entries.joinToString { "${it.key}: ${it.value}" } + ". Rejected account state is unknown.")
        if (rejectedAwards > 0) add("Twitch award history is partial: $rejectedAwards invalid records ignored; no claims inferred from them.")
    }
    return TvAccountInventory(campaigns.values.toList(), awardMap, rejected, diagnostics,
        usableForLogin = records.isEmpty() || campaigns.isNotEmpty())
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
