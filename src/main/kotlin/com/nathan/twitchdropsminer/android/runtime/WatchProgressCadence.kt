package com.nathan.twitchdropsminer.android.runtime

import java.time.Instant

/** Retained across inventory/channel refreshes in one run; playlist polls never count as minutes. */
internal class WatchProgressCadence {
    private var lastCheck: Instant? = null

    fun takeIfDue(now: Instant, intervalSeconds: Int): Boolean {
        if (lastCheck?.let { now.isBefore(it.plusSeconds(intervalSeconds.toLong())) } == true) return false
        lastCheck = now
        return true
    }
}
