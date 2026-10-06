package com.nathan.twitchdropsminer.android.runtime

import java.time.Duration
import java.time.Instant

/** Absence is successful observation, but never a minute sample for the confirmed-stall probes. */
internal class UnconfirmedSessionProbe {
    private var since: Instant? = null
    private var observations = 0
    fun observe(observation: ProgressObservation, at: Instant): Boolean {
        if (observation == ProgressObservation.Confirmed) { reset(); return false }
        if (observation != ProgressObservation.NoActiveDrop && observation != ProgressObservation.OtherChannel &&
            observation != ProgressObservation.UnexpectedDrop) return false
        if (since == null) since = at
        observations++
        return observations >= 3 && !at.isBefore(since!!.plus(Duration.ofMinutes(5)))
    }
    fun reset() { since = null; observations = 0 }
}

internal val ProgressObservation.status: String get() = when (this) {
    ProgressObservation.Confirmed -> "confirmed"
    ProgressObservation.NoActiveDrop -> "no_active_drop"
    ProgressObservation.OtherChannel -> "other_channel"
    ProgressObservation.UnexpectedDrop -> "reconciling"
    is ProgressObservation.Unavailable -> if (malformed) "malformed" else "unavailable"
}

internal val ProgressObservation.detail: String? get() = when (this) {
    ProgressObservation.Confirmed -> null
    ProgressObservation.NoActiveDrop -> "Waiting for Twitch to report a drop"
    ProgressObservation.OtherChannel -> "Twitch reported another channel; earning here is not confirmed"
    ProgressObservation.UnexpectedDrop -> "Reconciling a Twitch-reported drop with account inventory"
    is ProgressObservation.Unavailable -> if (malformed) "Twitch progress response is malformed; retrying" else "Progress check temporarily unavailable; retrying"
}
