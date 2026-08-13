package com.nathan.twitchdropsminer.android.data.model

enum class AutoModePriority(
    val storageKey: String,
    val title: String,
    val description: String,
) {
    LinkedClaimedProgress(
        storageKey = "linked_claimed_progress",
        title = "Linked · claimed-drop progress",
        description = "Linked campaigns where at least one drop is already claimed.",
    ),
    UnlinkedClaimedProgress(
        storageKey = "unlinked_claimed_progress",
        title = "Unlinked · claimed-drop progress",
        description = "Unlinked campaigns where at least one drop is already claimed.",
    ),
    LinkedViewingProgress(
        storageKey = "linked_viewing_progress",
        title = "Linked · viewing progress",
        description = "Linked campaigns with Twitch-reported watch progress.",
    ),
    UnlinkedViewingProgress(
        storageKey = "unlinked_viewing_progress",
        title = "Unlinked · viewing progress",
        description = "Unlinked campaigns with Twitch-reported watch progress.",
    ),
    LinkedFresh(
        storageKey = "linked_fresh",
        title = "Linked · no progress",
        description = "Linked campaigns without claimed drops or viewing progress.",
    ),
    UnlinkedFresh(
        storageKey = "unlinked_fresh",
        title = "Unlinked · no progress",
        description = "Unlinked campaigns without claimed drops or viewing progress.",
    ),
    ;

    companion object {
        val DefaultOrder: List<AutoModePriority> = listOf(
            LinkedClaimedProgress,
            LinkedViewingProgress,
            LinkedFresh,
            UnlinkedClaimedProgress,
            UnlinkedViewingProgress,
            UnlinkedFresh,
        )

        fun fromStorageKey(value: String): AutoModePriority? =
            entries.firstOrNull { option -> option.storageKey == value.trim() }

        fun normalize(order: List<AutoModePriority>): List<AutoModePriority> {
            val distinctOrder = order.distinct()
            return distinctOrder + DefaultOrder.filterNot(distinctOrder::contains)
        }
    }
}
