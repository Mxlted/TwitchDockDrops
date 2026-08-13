package com.nathan.twitchdropsminer.android.data.model

import java.time.Instant

enum class LoginState {
    Unknown,
    LoggedOut,
    LoginRequired,
    LoggedIn,
    Expired,
}

data class LoginSession(
    val state: LoginState = LoginState.Unknown,
    val statusText: String = "Unknown",
    val userId: String? = null,
    val oauthUrl: String? = null,
    val oauthCode: String? = null,
    val deviceCode: String? = null,
    val expiresAt: Instant? = null,
) {
    val isAuthenticated: Boolean
        get() = state == LoginState.LoggedIn

    val isActionRequired: Boolean
        get() = state == LoginState.LoginRequired || oauthCode != null
}

data class StoredTwitchSession(
    val accessToken: String,
    val userId: String,
    val deviceId: String,
    val savedAt: Instant,
)

data class MinerStatus(
    val statusText: String,
    val login: LoginSession,
    val manualMode: Boolean = false,
    val manualGame: String? = null,
    val manualChannel: String? = null,
    val dropsClaimedThisSession: Int = 0,
)

data class DropReward(
    val name: String,
    val type: String,
    val imageUrl: String? = null,
    val id: String? = null,
)

data class CampaignDrop(
    val id: String,
    val name: String,
    val currentMinutes: Int,
    val requiredMinutes: Int,
    val progress: Float,
    val isClaimed: Boolean,
    val canClaim: Boolean,
    val rewards: List<DropReward>,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val claimId: String? = null,
    val preconditionDropIds: List<String> = emptyList(),
) {
    val watchedMinutes: Int
        get() = if (requiredMinutes > 0) {
            currentMinutes.coerceIn(0, requiredMinutes)
        } else {
            currentMinutes.coerceAtLeast(0)
        }

    val remainingMinutes: Int
        get() = (requiredMinutes - watchedMinutes).coerceAtLeast(0)

    val progressFraction: Float
        get() = when {
            isClaimed -> 1f
            requiredMinutes > 0 -> watchedMinutes.toFloat() / requiredMinutes.toFloat()
            else -> progress.coerceIn(0f, 1f)
        }

    val hasCompletedProgress: Boolean
        get() = requiredMinutes > 0 && currentMinutes >= requiredMinutes
}

data class Campaign(
    val id: String,
    val name: String,
    val gameName: String,
    val gameBoxArtUrl: String? = null,
    val campaignUrl: String? = null,
    val linkUrl: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val linked: Boolean = false,
    val linkStatusKnown: Boolean = true,
    val active: Boolean = false,
    val upcoming: Boolean = false,
    val expired: Boolean = false,
    val claimedDrops: Int = 0,
    val totalDrops: Int = 0,
    val drops: List<CampaignDrop> = emptyList(),
    val allowedChannels: List<Channel> = emptyList(),
    val selected: Boolean = false,
) {
    val progress: Float
        get() = drops.watchProgressFraction()

    val statusLabel: String
        get() = when {
            active -> "Active"
            upcoming -> "Upcoming"
            expired -> "Expired"
            else -> "Unknown"
        }

    val remainingMinutes: Int
        get() = drops.sumOf { it.remainingMinutes }

    val hasEarnableDrops: Boolean
        get() = hasEarnableDropsAt(Instant.now())

    fun hasEarnableDropsAt(now: Instant): Boolean = drops.any {
            !it.isClaimed &&
                (
                    (it.remainingMinutes > 0 && it.isWatchableAt(now)) ||
                        it.canClaim ||
                        it.hasCompletedProgress
                    )
        }

    fun isActiveAt(now: Instant): Boolean {
        if (expired || (endsAt != null && !now.isBefore(endsAt))) {
            return false
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return false
        }
        return active || upcoming || startsAt != null
    }

    fun isWatchableAt(now: Instant): Boolean =
        isActiveAt(now) && drops.any { drop ->
            !drop.isClaimed &&
                drop.requiredMinutes > 0 &&
                !drop.hasCompletedProgress &&
                drop.isWatchableAt(now)
        }

    val isKnownUnlinked: Boolean
        get() = !linked && (linkStatusKnown || linkUrl != null)

    val canEarnLocally: Boolean
        get() = canEarnLocallyAt(Instant.now())

    fun canEarnLocallyAt(now: Instant): Boolean =
        isActiveAt(now) && linked && hasEarnableDropsAt(now)

    val canTryUnlinkedLocally: Boolean
        get() = canTryUnlinkedLocallyAt(Instant.now())

    fun canTryUnlinkedLocallyAt(now: Instant): Boolean =
        isActiveAt(now) && isKnownUnlinked && hasEarnableDropsAt(now)

    fun watchableDrop(
        preferredDropId: String? = null,
        now: Instant = Instant.now(),
    ): CampaignDrop? {
        if (!isActiveAt(now)) return null
        val orderedDrops = drops.inEarningOrder()
        val knownDropIds = drops.mapTo(mutableSetOf()) { drop -> drop.id }
        val claimedDropIds = drops
            .asSequence()
            .filter { drop -> drop.isClaimed }
            .map { drop -> drop.id }
            .toSet()
        val farmableDrops = orderedDrops.filter { drop ->
            !drop.isClaimed &&
                drop.requiredMinutes > 0 &&
                !drop.hasCompletedProgress &&
                drop.isWatchableAt(now) &&
                drop.hasSatisfiedPrerequisites(knownDropIds, claimedDropIds)
        }
        return farmableDrops.firstOrNull { drop -> drop.id == preferredDropId }
            ?: farmableDrops.firstOrNull { drop -> drop.watchedMinutes > 0 }
            ?: farmableDrops.firstOrNull()
    }

    fun activeDrop(
        preferredDropId: String? = null,
        now: Instant = Instant.now(),
    ): CampaignDrop? {
        val orderedDrops = drops.inEarningOrder()
        val knownDropIds = drops.mapTo(mutableSetOf()) { drop -> drop.id }
        val claimedDropIds = drops
            .asSequence()
            .filter { drop -> drop.isClaimed }
            .map { drop -> drop.id }
            .toSet()
        val preferredDrop = preferredDropId?.let { id ->
            drops.firstOrNull { drop ->
                drop.id == id &&
                    !drop.isClaimed &&
                    (
                        (
                            drop.requiredMinutes > 0 &&
                                !drop.hasCompletedProgress &&
                                drop.isWatchableAt(now)
                            ) ||
                            drop.canClaim ||
                            drop.hasCompletedProgress
                        ) &&
                    drop.hasSatisfiedPrerequisites(knownDropIds, claimedDropIds)
            }
        }
        if (preferredDrop != null) {
            return preferredDrop
        }
        return watchableDrop(preferredDropId, now)
            ?: orderedDrops.firstOrNull { drop ->
                !drop.isClaimed &&
                    (drop.canClaim || drop.hasCompletedProgress) &&
                    drop.hasSatisfiedPrerequisites(knownDropIds, claimedDropIds)
            }
    }

    fun isDropUnlocked(drop: CampaignDrop): Boolean {
        val knownDropIds = drops.mapTo(mutableSetOf()) { candidate -> candidate.id }
        val claimedDropIds = drops
            .asSequence()
            .filter { candidate -> candidate.isClaimed }
            .map { candidate -> candidate.id }
            .toSet()
        return drop.hasSatisfiedPrerequisites(knownDropIds, claimedDropIds)
    }

    fun claimableDropsInEarningOrder(): List<CampaignDrop> {
        val knownDropIds = drops.mapTo(mutableSetOf()) { candidate -> candidate.id }
        val claimedDropIds = drops
            .asSequence()
            .filter { candidate -> candidate.isClaimed }
            .map { candidate -> candidate.id }
            .toSet()
        return drops.inEarningOrder().filter { drop ->
            !drop.isClaimed &&
                (drop.canClaim || drop.hasCompletedProgress) &&
                drop.hasSatisfiedPrerequisites(knownDropIds, claimedDropIds)
        }
    }
}

fun CampaignDrop.isWatchableAt(now: Instant): Boolean =
    (startsAt == null || !now.isBefore(startsAt)) &&
        (endsAt == null || now.isBefore(endsAt))

private fun CampaignDrop.hasSatisfiedPrerequisites(
    knownDropIds: Set<String>,
    claimedDropIds: Set<String>,
): Boolean = preconditionDropIds.none { prerequisiteId ->
    prerequisiteId in knownDropIds && prerequisiteId !in claimedDropIds
}

fun List<CampaignDrop>.inEarningOrder(): List<CampaignDrop> {
    if (size < 2) {
        return this
    }

    data class IndexedDrop(val index: Int, val drop: CampaignDrop)

    val remaining = mapIndexed(::IndexedDrop).toMutableList()
    val knownDropIds = map { drop -> drop.id }.toSet()
    val emittedDropIds = mutableSetOf<String>()
    val ordered = ArrayList<CampaignDrop>(size)
    val byRequiredTime = compareBy<IndexedDrop> { indexed ->
        indexed.drop.requiredMinutes.takeIf { minutes -> minutes > 0 } ?: Int.MAX_VALUE
    }.thenBy { indexed -> indexed.index }

    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { indexed ->
            indexed.drop.preconditionDropIds.none { prerequisiteId ->
                prerequisiteId in knownDropIds && prerequisiteId !in emittedDropIds
            }
        }
        val next = (ready.ifEmpty { remaining }).minWithOrNull(byRequiredTime) ?: break
        ordered += next.drop
        emittedDropIds += next.drop.id
        remaining.remove(next)
    }
    return ordered
}

fun List<CampaignDrop>.watchProgressFraction(): Float {
    val requiredMinutes = sumOf { drop -> drop.requiredMinutes.coerceAtLeast(0) }
    if (requiredMinutes <= 0) {
        return 0f
    }
    val watchedMinutes = sumOf { drop ->
        if (drop.requiredMinutes > 0) drop.watchedMinutes else 0
    }
    return watchedMinutes.toFloat() / requiredMinutes.toFloat()
}

data class Channel(
    val id: Long,
    val name: String,
    val game: String? = null,
    val viewers: Int? = null,
    val online: Boolean = false,
    val dropsEnabled: Boolean = false,
    val aclBased: Boolean = false,
    val watching: Boolean = false,
    val broadcastId: String? = null,
    val gameId: String? = null,
    val title: String? = null,
) {
    val statusLabel: String
        get() = when {
            watching -> "Watching"
            online && dropsEnabled -> "Drops enabled"
            online -> "Online"
            else -> "Offline"
        }
}

data class BackendConsole(
    val lines: List<String> = emptyList(),
)
