package com.nathan.twitchdropsminer.android.data.model

import java.time.Instant

/** Display-only Twitch rewards. Never passed to the watch selector or claim runtime. */
data class RewardCampaign(
    val id: String,
    val name: String,
    val brand: String?,
    val gameName: String?,
    val summary: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val rewardNames: List<String>,
)
