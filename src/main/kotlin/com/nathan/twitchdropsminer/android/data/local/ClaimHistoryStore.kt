package com.nathan.twitchdropsminer.android.data.local

import app.twitchdockdrops.storage.AtomicFiles
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

/** Account-scoped write-ahead intent. Unknown outcomes require fresh inventory evidence. */
class ClaimHistoryStore(private val directory: Path, private val cooldown: Duration = Duration.ofMinutes(5), private val now: () -> Instant = Instant::now) {
    private fun file(account: String): Path {
        require(account.matches(Regex("[0-9]{1,30}"))) { "Invalid history account." }
        return directory.resolve("claims-$account.json")
    }
    private fun load(account: String): List<JsonObject> {
        val path = file(account)
        if (!Files.exists(path)) return emptyList()
        try {
            require(Files.size(path) <= 4 * 1024 * 1024)
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            require(root["version"]?.jsonPrimitive?.intOrNull == 1)
            require(root["account"]?.jsonPrimitive?.content == account)
            val records = root.getValue("records").jsonArray
            require(records.size <= 2000)
            return records.map { value -> value.jsonObject.also {
                require(it["state"]?.jsonPrimitive?.content in setOf("pending", "confirmed"))
                for (key in listOf("campaignId", "dropId", "campaign", "reward", "game", "recordedAt")) {
                    require(it[key]?.jsonPrimitive?.isString == true)
                    require(it[key]!!.jsonPrimitive.content.length <= 500)
                }
                Instant.parse(it.getValue("recordedAt").jsonPrimitive.content)
            }.let { record -> JsonObject(record.filterKeys { it in setOf("campaignId", "dropId", "campaign", "reward", "game", "recordedAt", "state") }) } }
        } catch (_: Exception) {
            // Preserve the entire recoverable file. Never replace uncertain pending records with defaults.
            throw IllegalStateException("Claim history is unreadable; its file was preserved. Claims are paused until it is recovered.")
        }
    }
    private fun save(account: String, records: List<JsonObject>) {
        val pending = records.filter { it["state"]!!.jsonPrimitive.content == "pending" }
        require(pending.size <= 2000) { "Pending claim storage is full; reconcile inventory before claiming." }
        val kept = pending + records.filter { it["state"]!!.jsonPrimitive.content == "confirmed" }.takeLast(2000 - pending.size)
        val serialized = buildJsonObject {
            put("version", 1); put("account", account); put("records", JsonArray(kept))
        }.toString()
        require(serialized.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Claim history storage is full; its file was preserved." }
        AtomicFiles.writeString(file(account), serialized, ownerOnly = true)
    }
    private fun record(campaign: Campaign, drop: CampaignDrop, state: String) = buildJsonObject {
        require(campaign.id.length in 1..500 && drop.id.length in 1..500) { "Claim identifiers exceed storage limits." }
        put("campaignId", campaign.id); put("dropId", drop.id)
        put("campaign", campaign.name.take(200)); put("reward", drop.name.take(200)); put("game", campaign.gameName.take(200))
        put("state", state); put("recordedAt", now().toString())
    }
    private fun JsonObject.matches(c: String, d: String) =
        this["campaignId"]?.jsonPrimitive?.content == c && this["dropId"]?.jsonPrimitive?.content == d

    @Synchronized fun view(account: String): String = buildJsonObject {
        put("records", JsonArray(load(account).asReversed()))
    }.toString()

    @Synchronized fun suppressed(account: String, campaign: String, drop: String): Boolean =
        load(account).any { it.matches(campaign, drop) }

    @Synchronized fun begin(account: String, campaign: Campaign, drop: CampaignDrop) {
        val records = load(account)
        check(records.none { it.matches(campaign.id, drop.id) }) { "Claim awaits authoritative reconciliation." }
        save(account, records + record(campaign, drop, "pending"))
    }

    @Synchronized fun rejected(account: String, campaign: String, drop: String) {
        save(account, load(account).filterNot { it.matches(campaign, drop) && it["state"]!!.jsonPrimitive.content == "pending" })
    }

    @Synchronized fun confirm(account: String, campaign: Campaign, drop: CampaignDrop) {
        val records = load(account)
        if (records.any { it.matches(campaign.id, drop.id) && it["state"]!!.jsonPrimitive.content == "confirmed" }) return
        save(account, records.filterNot { it.matches(campaign.id, drop.id) } + record(campaign, drop, "confirmed"))
    }

    @Synchronized fun reconcile(account: String, campaigns: List<Campaign>) {
        var records = load(account)
        val before = records
        for (campaign in campaigns) for (drop in campaign.drops) {
            val previous = records.firstOrNull { it.matches(campaign.id, drop.id) }
            if (previous?.get("state")?.jsonPrimitive?.content == "confirmed") continue
            if (drop.isClaimed) {
                records = records.filterNot { it.matches(campaign.id, drop.id) } + record(campaign, drop, "confirmed")
            } else if (previous != null && drop.claimEvidenceKnown && drop.canClaim && !drop.claimId.isNullOrBlank() &&
                !now().isBefore(Instant.parse(previous.getValue("recordedAt").jsonPrimitive.content).plus(cooldown))) {
                // Explicit unclaimed, still-claimable inventory after cooldown permits a new attempt.
                records = records.filterNot { it.matches(campaign.id, drop.id) }
            }
        }
        if (records != before) save(account, records)
    }
}
