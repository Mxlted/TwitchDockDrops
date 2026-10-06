package com.nathan.twitchdropsminer.android.data.model

import java.time.Instant
import java.util.Locale

/** Distribution values are supplied by Twitch, not inferred from reward names. */
fun DropReward.filterType(): String = type.trim().uppercase(Locale.ROOT).ifBlank { "UNKNOWN" }

/** Recompute from raw metadata on every selection/settings update. Never mark skipped drops claimed. */
fun Campaign.withRewardEligibility(settings: AppSettings, now: Instant = Instant.now()): Campaign {
    val byId = drops.associateBy { it.id }
    val duplicateIds = drops.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    val reasons = mutableMapOf<String, String?>()
    // Optimistic lower bound: shared prerequisites count once along each dependency path.
    // This cannot promise enough time for competing branches, but rejects provably impossible chains.
    val earliestFinish = mutableMapOf<String, Instant>()
    val paths = mutableSetOf<String>()
    fun excluded(drop: CampaignDrop) = settings.excludedRewardNames.any { pattern ->
        (listOf(drop.name) + drop.rewards.map { it.name }).any { it.contains(pattern, ignoreCase = true) }
    }
    fun reason(id: String, depth: Int = 0): String? {
        val drop = byId[id] ?: return "Missing prerequisite: $id"
        if (drop.isClaimed) { earliestFinish[id] = now; return null }
        if (reasons.containsKey(id)) return reasons[id]
        if (depth >= 256 || !paths.add(id)) return "Cyclic or excessively deep prerequisite chain"
        val start = listOfNotNull(startsAt, drop.startsAt).maxOrNull()
        val end = listOfNotNull(endsAt, drop.endsAt).minOrNull()
        var result = when {
            id in duplicateIds -> "Duplicate reward identifier"
            excluded(drop) -> "Excluded by reward name"
            start != null && end != null && !start.isBefore(end) -> "Reward has no earning window"
            !drop.hasCompletedProgress && !drop.canClaim && (expired || end?.let { !now.isBefore(it) } == true) -> "Reward window ended"
            drop.requiredMinutes <= 0 && !drop.canClaim -> "Cannot be earned by watching"
            else -> drop.preconditionDropIds.firstNotNullOfOrNull { prerequisite ->
                val dependency = byId[prerequisite]
                val blocked = reason(prerequisite, depth + 1)
                when {
                    blocked != null -> "Prerequisite $prerequisite: $blocked"
                    dependency != null && !dependency.isClaimed && end != null &&
                        listOfNotNull(startsAt, dependency.startsAt).maxOrNull()?.let { !it.isBefore(end) } == true ->
                        "Prerequisite $prerequisite starts after this reward ends"
                    else -> null
                }
            }
        }
        if (result == null) {
            val ready = (listOfNotNull(now, start) + drop.preconditionDropIds.mapNotNull { earliestFinish[it] }).maxOrNull()!!
            val remainingSeconds = if (drop.canClaim || drop.hasCompletedProgress) 0L else
                (drop.requiredMinutes.toLong() - drop.currentMinutes.toLong()).coerceAtLeast(0) * 60
            val finish = ready.plusSeconds(remainingSeconds)
            earliestFinish[id] = finish
            if (remainingSeconds > 0 && end != null && finish.isAfter(end)) result = "Insufficient time for reward and prerequisite watch time"
        }
        paths.remove(id)
        reasons[id] = result?.take(400)
        return reasons[id]
    }
    drops.forEach { reason(it.id) }
    val included = mutableSetOf<String>()
    fun include(id: String) {
        if (!included.add(id)) return
        byId[id]?.preconditionDropIds?.forEach(::include)
    }
    drops.filter { drop ->
        !drop.isClaimed && reasons[drop.id] == null && (settings.allowedRewardTypes.isEmpty() ||
            drop.rewards.ifEmpty { listOf(DropReward("", "UNKNOWN")) }.any { it.filterType() in settings.allowedRewardTypes })
    }.forEach { include(it.id) }
    return copy(drops = drops.map { drop ->
        val blocked = if (drop.isClaimed) null else reasons[drop.id] ?: when {
            drop.id !in included -> "Filtered by reward type"
            drop.preconditionDropIds.any { byId[it]?.isClaimed != true } -> "Waiting for prerequisite claims"
            else -> null
        }
        drop.copy(blockedReason = blocked,
            eligibleByFilter = drop.isClaimed || (drop.id in included && reasons[drop.id] == null))
    })
}
