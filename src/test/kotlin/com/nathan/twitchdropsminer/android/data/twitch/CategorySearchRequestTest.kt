package com.nathan.twitchdropsminer.android.data.twitch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategorySearchRequestTest {
    @Test
    fun `normalization determines paging and preserves opaque cursors`() {
        val short = CategorySearchRequest("  sta  ")
        assertEquals("sta", short.query)
        assertEquals(12, short.limit)
        assertFalse(short.supportsPagination)
        val long = CategorySearchRequest("  star  ", "Ab+/=_-")
        assertEquals("star", long.query)
        assertEquals(50, long.limit)
        assertTrue(long.supportsPagination)
        assertEquals("Ab+/=_-", long.after)
        assertEquals(50, CategorySearchRequest("x".repeat(100)).limit)
    }

    @Test
    fun `invalid queries and continuations cannot reach a search provider`() {
        for (query in listOf("", " a ", "x".repeat(101), "ab\ncd", "ab\u0000cd")) {
            assertFailsWith<IllegalArgumentException> { CategorySearchRequest(query) }
        }
        assertFailsWith<IllegalArgumentException> { CategorySearchRequest(" sta ", "cursor") }
        for (cursor in listOf("", "bad cursor", "cursor\n", "x".repeat(513))) {
            assertFailsWith<IllegalArgumentException> { CategorySearchRequest("star", cursor) }
        }
        assertEquals("x".repeat(512), CategorySearchRequest("star", "x".repeat(512)).after)
    }
}
