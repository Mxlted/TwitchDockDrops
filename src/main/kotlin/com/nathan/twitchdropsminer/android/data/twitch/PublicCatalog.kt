package com.nathan.twitchdropsminer.android.data.twitch

import com.nathan.twitchdropsminer.android.data.model.Campaign
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

data class PublicCatalogResult(
    val campaigns: List<Campaign> = emptyList(),
    val updatedAt: Instant? = null,
    val problem: String? = null,
)

/** No session argument and no shared Twitch client, interceptors, cookies, or authenticators. */
class PublicCatalogClient(
    endpoint: String = "https://twitch-drops-api.sunkwi.com/v2/drops",
    private val now: () -> Instant = Instant::now,
) {
    private val url = endpoint.toHttpUrl().also {
        require(it.toString() == "https://twitch-drops-api.sunkwi.com/v2/drops" ||
            (it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1", "::1")))
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var cached = PublicCatalogResult(problem = "Public catalog has not been checked.")
    private var nextAttempt = Instant.MIN

    suspend fun fetch(): PublicCatalogResult = mutex.withLock {
        val time = now()
        if (time < nextAttempt && cached.updatedAt?.let { fresh(it, time) } != false) return@withLock cached
        // One attempt per minute, also on failure. No transport or application retry loop.
        if (time < nextAttempt) return@withLock cached.copy(problem = "Public catalog is stale; retained metadata.")
        nextAttempt = time.plusSeconds(60)
        val received = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use PublicCatalogResult(problem = "Public catalog unavailable (HTTP ${response.code}); retained metadata.")
                    val body = response.body ?: error("missing body")
                    require(body.contentLength() <= MaxCatalogBytes)
                    val source = body.source()
                    require(!source.request(MaxCatalogBytes + 1))
                    parsePublicCatalog(source.readUtf8(), now())
                }
            } catch (_: Exception) {
                PublicCatalogResult(problem = "Public catalog unavailable or malformed; retained metadata.")
            }
        }
        val prior = cached.campaigns.filter { it.endsAt?.isAfter(time) == true }
        cached = received.copy(
            campaigns = if (received.problem == null) received.campaigns else
                (received.campaigns + prior).distinctBy { it.id }.take(2000),
            updatedAt = received.updatedAt ?: cached.updatedAt,
        )
        cached
    }
}

private const val MaxCatalogBytes = 8L * 1024 * 1024
private fun fresh(updated: Instant, now: Instant) = updated >= now.minusSeconds(1800) && updated <= now.plusSeconds(300)

/** Independently validates the public wire schema; account fields are deliberately discarded. */
internal fun parsePublicCatalog(body: String, now: Instant): PublicCatalogResult {
    try {
        val root = Json.parseToJsonElement(body).jsonObject
        val updated = Instant.parse(root.getValue("lastUpdatedAt").jsonPrimitive.content)
        if (!fresh(updated, now)) return PublicCatalogResult(problem = "Public catalog timestamp is stale or in the future; retained metadata.")
        val groups = root.getValue("data").jsonArray
        require(groups.size <= 2000)
        var partial = false
        var count = 0
        val records = linkedMapOf<String, Campaign>()
        val seen = mutableSetOf<String>()
        for (element in groups) {
            val group = element as? JsonObject
            val rewards = group?.get("rewards") as? JsonArray
            if (rewards == null) { partial = true; continue }
            count += rewards.size
            require(count <= 2000)
            for (record in rewards) {
                val id = ((record as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                if (id != null && !seen.add(id)) { records.remove(id); partial = true; continue }
                val campaign = runCatching {
                    val normalized = validateCampaignMetadata(record.jsonObject)
                    val mapped = TwitchCampaignMapper.mapCampaign(normalized, emptyMap())
                    require(mapped.diagnostics.isEmpty())
                    requireNotNull(mapped.campaign).copy(
                        publicCatalog = true, linked = false, linkStatusKnown = false,
                        gameBoxArtUrl = mapped.campaign.gameBoxArtUrl ?: group["gameBoxArtURL"]?.jsonPrimitive?.contentOrNull,
                        drops = mapped.campaign.drops.map { it.copy(progressKnown = false, claimEvidenceKnown = false) },
                    )
                }.getOrNull()
                if (campaign == null) { partial = true; continue }
                if (campaign.endsAt!! > now) records[campaign.id] = campaign
            }
        }
        return PublicCatalogResult(records.values.toList(), updated,
            if (partial) "Public catalog is partial; retained known metadata for missing records." else null)
    } catch (_: Exception) {
        return PublicCatalogResult(problem = "Public catalog schema is malformed; retained metadata.")
    }
}

internal fun validateCampaignMetadata(record: JsonObject): JsonObject {
    fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString && it.content.isNotBlank() && it.content.length <= 2048); it.content
    }
    fun window(obj: JsonObject) { require(Instant.parse(obj.text("startAt")) < Instant.parse(obj.text("endAt"))) }
    record.text("id"); record.text("name"); window(record)
    require(record.text("status") in setOf("ACTIVE", "UPCOMING", "EXPIRED"))
    val game = record.getValue("game").jsonObject
    require(game.text("id").toLong() > 0); game.text("displayName")
    val allow = record.getValue("allow").jsonObject
    val restriction = allow.getValue("isEnabled").jsonPrimitive
    require(!restriction.isString)
    val restricted = requireNotNull(restriction.booleanOrNull)
    require(allow["channels"] == null || allow["channels"] == JsonNull || allow["channels"] is JsonArray)
    val channels = if (restricted) allow.getValue("channels").jsonArray else JsonArray(emptyList())
    require(channels.size <= 100 && (!restricted || channels.isNotEmpty()))
    val cleanChannels = channels.map {
        val channel = it.jsonObject
        require(channel.text("id").toLong() > 0)
        val login = channel.text(if (channel["login"] == null || channel["login"] == JsonNull) "name" else "login")
        require(login.matches(Regex("[A-Za-z0-9_]{1,100}")))
        JsonObject(channel + ("login" to JsonPrimitive(login)))
    }
    val drops = record.getValue("timeBasedDrops").jsonArray
    require(drops.size <= 256)
    val ids = mutableSetOf<String>()
    val cleanDrops = drops.map { element ->
        val drop = element.jsonObject
        require(ids.add(drop.text("id"))); drop.text("name"); window(drop)
        val required = drop.getValue("requiredMinutesWatched").jsonPrimitive
        require(!required.isString && required.intOrNull in 0..100000)
        val subscription = drop["requiredSubs"]
        require(subscription == null || (subscription is JsonPrimitive && !subscription.isString && subscription.intOrNull != null))
        val subs = subscription?.jsonPrimitive?.intOrNull ?: 0
        require(subs >= 0)
        val prerequisites = drop.getValue("preconditionDrops")
        require(prerequisites == JsonNull || prerequisites is JsonArray)
        if (prerequisites is JsonArray) {
            require(prerequisites.size <= 256)
            prerequisites.forEach { it.jsonObject.text("id") }
        }
        val benefits = drop.getValue("benefitEdges").jsonArray
        require(benefits.size in 1..64)
        benefits.forEach { edge ->
            val benefit = edge.jsonObject.getValue("benefit").jsonObject
            benefit.text("id"); benefit.text("name"); benefit.text("distributionType")
        }
        JsonObject(drop - "self" + if (subs > 0) mapOf("requiredMinutesWatched" to JsonPrimitive(0)) else emptyMap())
    }
    return JsonObject(record - "self" + mapOf("timeBasedDrops" to JsonArray(cleanDrops),
        "allow" to JsonObject(allow + ("channels" to JsonArray(cleanChannels)))))
}
