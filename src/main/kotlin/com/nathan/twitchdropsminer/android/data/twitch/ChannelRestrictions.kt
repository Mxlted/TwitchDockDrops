package com.nathan.twitchdropsminer.android.data.twitch

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal sealed interface ChannelRestrictionResult {
    data object Unknown : ChannelRestrictionResult
    data object Unbanned : ChannelRestrictionResult
    data class Banned(val permanent: Boolean, val expiresAt: Instant?) : ChannelRestrictionResult {
        fun active(at: Instant): Boolean = permanent || expiresAt == null || at.isBefore(expiresAt)
    }
}

/** Only an error-free response for the requested channel supplies account restriction evidence. */
internal fun parseChannelRestriction(response: JsonObject, channelId: Long): ChannelRestrictionResult {
    if (response.containsKey("errors") && (response["errors"] as? JsonArray)?.isEmpty() != true) {
        return ChannelRestrictionResult.Unknown
    }
    val user = (response["data"] as? JsonObject)?.get("user") as? JsonObject
        ?: return ChannelRestrictionResult.Unknown
    val id = user["id"] as? JsonPrimitive
    if (id?.isString != true || id.content != channelId.toString()) return ChannelRestrictionResult.Unknown
    val self = user["self"] as? JsonObject ?: return ChannelRestrictionResult.Unknown
    val status: JsonElement = self["banStatus"] ?: return ChannelRestrictionResult.Unknown
    if (status == JsonNull) return ChannelRestrictionResult.Unbanned
    val fields = status as? JsonObject ?: return ChannelRestrictionResult.Unknown
    val flag = fields["isPermanent"] as? JsonPrimitive ?: return ChannelRestrictionResult.Unknown
    if (flag.isString) return ChannelRestrictionResult.Unknown
    val permanent = flag.booleanOrNull ?: return ChannelRestrictionResult.Unknown
    val expiry = (fields["expiresAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.length <= 64 }
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
    return ChannelRestrictionResult.Banned(permanent, if (permanent) null else expiry)
}

/** Account-owned memory; saturation refuses new admission rather than dropping confirmed restrictions. */
internal class ChannelRestrictions(
    private val capacity: Int = 2_048,
    private val checkInterval: Duration = Duration.ofMinutes(3),
) {
    internal data class Ticket(val account: String, val generation: Long, val channelId: Long)
    private data class Entry(
        var restriction: ChannelRestrictionResult.Banned? = null,
        var checkedAt: Instant? = null,
        var checking: Boolean = false,
    )
    private var account: String? = null
    private var generation = 0L
    private val entries = linkedMapOf<Long, Entry>()

    @Synchronized fun bind(accountId: String?) {
        generation++
        if (account != accountId) entries.clear()
        account = accountId
        entries.values.forEach { it.checking = false }
    }

    @Synchronized fun begin(accountId: String, channelId: Long, at: Instant): Ticket? {
        // Standalone clients can establish their first account; runtime replacement always binds explicitly.
        if (account == null && generation == 0L) bind(accountId)
        if (account != accountId || channelId <= 0) return null
        var entry = entries[channelId]
        if (entry == null) {
            if (entries.size >= capacity) {
                val removable = entries.entries.firstOrNull { !it.value.checking && it.value.restriction?.active(at) != true }
                    ?: return null
                entries.remove(removable.key)
            }
            entry = Entry()
            entries[channelId] = entry
        }
        if (entry.checking || entry.checkedAt?.plus(checkInterval)?.isAfter(at) == true) return null
        entry.checking = true
        // Failed attempts share the same bounded cadence as successful checks.
        entry.checkedAt = at
        return Ticket(accountId, generation, channelId)
    }

    @Synchronized fun complete(ticket: Ticket, result: ChannelRestrictionResult) {
        if (ticket.account != account || ticket.generation != generation) return
        val entry = entries[ticket.channelId] ?: return
        entry.checking = false
        when (result) {
            is ChannelRestrictionResult.Banned -> entry.restriction = result
            ChannelRestrictionResult.Unbanned -> entry.restriction = null
            ChannelRestrictionResult.Unknown -> Unit
        }
    }

    @Synchronized fun allowed(accountId: String, channelId: Long, at: Instant): Boolean {
        if (channelId <= 0) return false
        if (account == null && generation == 0L) return true
        if (account != accountId) return false
        val entry = entries[channelId]
        if (entry != null) return entry.restriction?.active(at) != true
        return entries.size < capacity || entries.values.any { !it.checking && it.restriction?.active(at) != true }
    }

    @Synchronized fun nextExpiry(accountId: String, at: Instant): Instant? = if (account != accountId) null else
        entries.values.mapNotNull { it.restriction?.takeIf { ban -> !ban.permanent }?.expiresAt }
            .filter { it.isAfter(at) }.minOrNull()
}
