package com.nathan.twitchdropsminer.android.data.model

import com.nathan.twitchdropsminer.android.data.twitch.TwitchClientId
import com.nathan.twitchdropsminer.android.data.twitch.TwitchTvClientId

/** Derived from stored protocol identity, never a display label or token presence. */
enum class SessionCapabilities(
    val method: String,
    val oauthClientId: String,
    val publicCatalog: Boolean,
    val openRewardCampaigns: Boolean,
    val renewal: String,
) {
    Browser("browser", TwitchWebClientId, false, true, "browser"),
    AndroidTv("android_tv", TwitchTvClientId, true, false, "refresh_token"),
    LegacyDevice("device", TwitchClientId, false, true, "reconnect");

    val requiresClaimEvidence: Boolean get() = this == AndroidTv
    companion object {
        fun from(session: StoredTwitchSession): SessionCapabilities = when {
            session.browserContext != null -> Browser.also { require(session.clientId == null) }
            session.clientId == TwitchTvClientId -> AndroidTv
            session.clientId == null || session.clientId == TwitchClientId -> LegacyDevice
            else -> error("Unsupported session client.")
        }
    }
}

val StoredTwitchSession.capabilities: SessionCapabilities get() = SessionCapabilities.from(this)
