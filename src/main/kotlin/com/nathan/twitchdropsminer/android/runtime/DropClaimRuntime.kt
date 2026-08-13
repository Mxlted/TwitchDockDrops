package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimOutcome
import com.nathan.twitchdropsminer.android.data.twitch.DropClaimResult
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiErrorType
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal val DefaultClaimFailureCooldown: Duration = Duration.ofMinutes(5)

internal data class ResolvedDropClaim(
    val campaignId: String,
    val dropId: String,
    val claimId: String,
    val generatedClaimId: Boolean,
) {
    val attemptKey: String
        get() = claimId
}

internal sealed class DropClaimPreparation {
    data class Ready(val resolved: ResolvedDropClaim) : DropClaimPreparation()
    data class NotClaimable(val reason: String) : DropClaimPreparation()
    data class MissingIdentifier(val reason: String) : DropClaimPreparation()
}

internal object DropClaimResolver {
    fun prepare(
        session: StoredTwitchSession,
        campaign: Campaign,
        drop: CampaignDrop,
    ): DropClaimPreparation {
        if (drop.isClaimed) {
            return DropClaimPreparation.NotClaimable("Drop is already claimed.")
        }
        if (!drop.hasCompletedProgress && !drop.canClaim) {
            return DropClaimPreparation.NotClaimable("Drop progress is not complete.")
        }
        val existingClaimId = drop.claimId?.trim().orEmpty()
        if (existingClaimId.isNotBlank()) {
            return DropClaimPreparation.Ready(
                ResolvedDropClaim(
                    campaignId = campaign.id,
                    dropId = drop.id,
                    claimId = existingClaimId,
                    generatedClaimId = false,
                ),
            )
        }
        val userId = session.userId.trim()
        val campaignId = campaign.id.trim()
        val dropId = drop.id.trim()
        val missing = listOf(
            "user ID".takeIf { userId.isBlank() },
            "campaign ID".takeIf { campaignId.isBlank() },
            "drop ID".takeIf { dropId.isBlank() },
        ).filterNotNull()
        if (missing.isNotEmpty()) {
            return DropClaimPreparation.MissingIdentifier(
                "Cannot generate drop instance ID; missing ${missing.joinToString()}.",
            )
        }
        return DropClaimPreparation.Ready(
            ResolvedDropClaim(
                campaignId = campaignId,
                dropId = dropId,
                claimId = "$userId#$campaignId#$dropId",
                generatedClaimId = true,
            ),
        )
    }
}

internal data class ClaimAttemptSuppression(
    val message: String,
    val retryAt: Instant?,
)

internal class ClaimAttemptTracker(
    private val failureCooldown: Duration = DefaultClaimFailureCooldown,
    private val now: () -> Instant = Instant::now,
) {
    private val attempts = mutableMapOf<String, ClaimAttemptRecord>()

    fun suppressionFor(attemptKey: String): ClaimAttemptSuppression? {
        val record = attempts[attemptKey] ?: return null
        if (record.terminal) {
            return ClaimAttemptSuppression(
                message = record.message ?: "Drop claim already reached a terminal result this run.",
                retryAt = null,
            )
        }
        val retryAt = record.retryAt ?: return null
        return if (now().isBefore(retryAt)) {
            ClaimAttemptSuppression(
                message = "Drop claim recently failed; retry after $retryAt.",
                retryAt = retryAt,
            )
        } else {
            null
        }
    }

    fun recordTerminal(attemptKey: String, message: String? = null) {
        attempts[attemptKey] = ClaimAttemptRecord(
            terminal = true,
            retryAt = null,
            message = message,
        )
    }

    fun recordFailure(attemptKey: String): Instant {
        val retryAt = now().plus(failureCooldown)
        attempts[attemptKey] = ClaimAttemptRecord(terminal = false, retryAt = retryAt)
        return retryAt
    }

    fun nextRetryAt(): Instant? {
        val current = now()
        return attempts.values
            .asSequence()
            .filterNot(ClaimAttemptRecord::terminal)
            .mapNotNull(ClaimAttemptRecord::retryAt)
            .filter { retryAt -> current.isBefore(retryAt) }
            .minOrNull()
    }

    fun clear() {
        attempts.clear()
    }

    private data class ClaimAttemptRecord(
        val terminal: Boolean,
        val retryAt: Instant?,
        val message: String? = null,
    )
}

internal enum class RuntimeClaimOutcome {
    Claimed,
    AlreadyClaimed,
    Failed,
    Suppressed,
    MissingIdentifier,
    NotClaimable,
    InvalidToken,
    NetworkFailure,
    UnexpectedResponse,
    TerminalRejection,
}

internal data class RuntimeClaimResult(
    val outcome: RuntimeClaimOutcome,
    val campaign: Campaign,
    val drop: CampaignDrop,
    val resolved: ResolvedDropClaim? = null,
    val twitchStatus: String? = null,
    val message: String? = null,
    val retryAt: Instant? = null,
) {
    val shouldCountAsNewClaim: Boolean
        get() = outcome == RuntimeClaimOutcome.Claimed

    val isTerminalSuccess: Boolean
        get() = outcome == RuntimeClaimOutcome.Claimed ||
            outcome == RuntimeClaimOutcome.AlreadyClaimed
}

internal class DropClaimHandler(
    private val twitchApi: TwitchApi,
    private val attemptTracker: ClaimAttemptTracker = ClaimAttemptTracker(),
) {
    fun suppressionFor(
        session: StoredTwitchSession,
        campaign: Campaign,
        drop: CampaignDrop,
    ): ClaimAttemptSuppression? {
        val preparation = DropClaimResolver.prepare(session, campaign, drop)
        return when (preparation) {
            is DropClaimPreparation.Ready ->
                attemptTracker.suppressionFor(preparation.resolved.attemptKey)
            is DropClaimPreparation.MissingIdentifier ->
                attemptTracker.suppressionFor(missingIdentifierKey(campaign, drop))
            is DropClaimPreparation.NotClaimable -> null
        }
    }

    suspend fun claim(
        session: StoredTwitchSession,
        campaign: Campaign,
        drop: CampaignDrop,
        ensureCurrent: () -> Unit = {},
    ): RuntimeClaimResult {
        val preparation = DropClaimResolver.prepare(session, campaign, drop)
        if (preparation is DropClaimPreparation.NotClaimable) {
            return RuntimeClaimResult(
                outcome = RuntimeClaimOutcome.NotClaimable,
                campaign = campaign,
                drop = drop,
                message = preparation.reason,
            )
        }
        if (preparation is DropClaimPreparation.MissingIdentifier) {
            ensureCurrent()
            attemptTracker.recordTerminal(
                missingIdentifierKey(campaign, drop),
                preparation.reason,
            )
            return RuntimeClaimResult(
                outcome = RuntimeClaimOutcome.MissingIdentifier,
                campaign = campaign,
                drop = drop,
                message = preparation.reason,
            )
        }

        val resolved = (preparation as DropClaimPreparation.Ready).resolved
        attemptTracker.suppressionFor(resolved.attemptKey)?.let { suppression ->
            return RuntimeClaimResult(
                outcome = RuntimeClaimOutcome.Suppressed,
                campaign = campaign,
                drop = drop,
                resolved = resolved,
                message = suppression.message,
                retryAt = suppression.retryAt,
            )
        }

        return try {
            val twitchResult = twitchApi.claimDrop(session, resolved.claimId)
            ensureCurrent()
            twitchResult.toRuntimeResult(campaign, drop, resolved)
        } catch (error: TwitchApiException) {
            ensureCurrent()
            val outcome = error.type.toRuntimeOutcome()
            val retryAt = if (outcome == RuntimeClaimOutcome.InvalidToken) {
                null
            } else {
                attemptTracker.recordFailure(resolved.attemptKey)
            }
            RuntimeClaimResult(
                outcome = outcome,
                campaign = campaign,
                drop = drop,
                resolved = resolved,
                message = error.message,
                retryAt = retryAt,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ensureCurrent()
            val retryAt = attemptTracker.recordFailure(resolved.attemptKey)
            RuntimeClaimResult(
                outcome = RuntimeClaimOutcome.UnexpectedResponse,
                campaign = campaign,
                drop = drop,
                resolved = resolved,
                message = error.message ?: "Unexpected claim failure.",
                retryAt = retryAt,
            )
        }
    }

    fun clearAttempts() {
        attemptTracker.clear()
    }

    fun nextRetryAt(): Instant? = attemptTracker.nextRetryAt()

    private fun DropClaimResult.toRuntimeResult(
        campaign: Campaign,
        drop: CampaignDrop,
        resolved: ResolvedDropClaim,
    ): RuntimeClaimResult {
        val outcome = when (this.outcome) {
            DropClaimOutcome.Claimed -> RuntimeClaimOutcome.Claimed
            DropClaimOutcome.AlreadyClaimed -> RuntimeClaimOutcome.AlreadyClaimed
            DropClaimOutcome.Ineligible -> RuntimeClaimOutcome.TerminalRejection
            DropClaimOutcome.MissingDropInstanceId -> RuntimeClaimOutcome.MissingIdentifier
            DropClaimOutcome.UnexpectedResponse -> RuntimeClaimOutcome.UnexpectedResponse
            DropClaimOutcome.Failed -> RuntimeClaimOutcome.Failed
        }
        val retryAt = when (outcome) {
            RuntimeClaimOutcome.Claimed,
            RuntimeClaimOutcome.AlreadyClaimed -> {
                attemptTracker.recordTerminal(resolved.attemptKey)
                null
            }

            RuntimeClaimOutcome.MissingIdentifier -> {
                attemptTracker.recordTerminal(resolved.attemptKey, message)
                null
            }

            RuntimeClaimOutcome.TerminalRejection -> {
                attemptTracker.recordTerminal(resolved.attemptKey, message)
                null
            }

            else -> attemptTracker.recordFailure(resolved.attemptKey)
        }
        return RuntimeClaimResult(
            outcome = outcome,
            campaign = campaign,
            drop = drop,
            resolved = resolved,
            twitchStatus = twitchStatus,
            message = message,
            retryAt = retryAt,
        )
    }

    private fun TwitchApiErrorType.toRuntimeOutcome(): RuntimeClaimOutcome =
        when (this) {
            TwitchApiErrorType.InvalidToken -> RuntimeClaimOutcome.InvalidToken
            TwitchApiErrorType.Network -> RuntimeClaimOutcome.NetworkFailure
            TwitchApiErrorType.Http,
            TwitchApiErrorType.GraphQl -> RuntimeClaimOutcome.Failed
            TwitchApiErrorType.UnexpectedResponse -> RuntimeClaimOutcome.UnexpectedResponse
        }
}

private fun missingIdentifierKey(campaign: Campaign, drop: CampaignDrop): String =
    "missing:${campaign.id.trim()}:${drop.id.trim()}"

internal fun Campaign.claimableDropsFor(session: StoredTwitchSession): List<CampaignDrop> =
    if (upcoming) {
        emptyList()
    } else {
        drops.filter { drop ->
            DropClaimResolver.prepare(session, this, drop) is DropClaimPreparation.Ready
        }
    }
