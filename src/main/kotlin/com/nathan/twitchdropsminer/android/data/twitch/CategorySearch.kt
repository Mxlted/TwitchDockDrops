package com.nathan.twitchdropsminer.android.data.twitch

data class TwitchCategory(val id: String, val name: String)
data class TwitchCategoryPage(val categories: List<TwitchCategory>, val nextCursor: String? = null)

/** Validated public lookup parameters shared by the API, transport, and serializer. */
class CategorySearchRequest(query: String, val after: String? = null) {
    val query: String = query.trim()
    val supportsPagination: Boolean get() = this.query.length >= 4
    val limit: Int get() = if (supportsPagination) 50 else 12

    init {
        require(this.query.length in 2..100 && this.query.none(Char::isISOControl)) {
            "Search must contain 2 to 100 characters without control characters."
        }
        require(after == null || (supportsPagination && isValidCursor(after))) {
            "Paging requires at least 4 search characters and a valid cursor."
        }
    }

    companion object {
        private val CursorPattern = Regex("[A-Za-z0-9+/=_-]{1,512}")

        fun isValidCursor(value: String): Boolean = CursorPattern.matches(value)
    }
}

fun interface CategorySearch {
    suspend fun search(request: CategorySearchRequest): TwitchCategoryPage
}

class CategorySearchException(val busy: Boolean = false) : IllegalStateException(
    if (busy) "Category search is busy. Try again shortly."
    else "Twitch category search is unavailable. Try again shortly or use loaded and saved games.",
)
