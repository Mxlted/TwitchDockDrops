package com.nathan.twitchdropsminer.android.data.local

enum class PersistenceFileState {
    Absent,
    Loaded,
    Corrupt,
    KeyMismatched,
    Unreadable,
}

data class PersistenceStatus(
    val state: PersistenceFileState,
    val diagnostic: String? = null,
)
