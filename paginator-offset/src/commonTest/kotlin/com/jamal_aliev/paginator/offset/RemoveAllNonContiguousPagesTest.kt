package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.extension.removeAll
import com.jamal_aliev.paginator.offset.load.LoadResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for page re-labelling when the cache holds non-contiguous islands of pages
 * (e.g. `1,2` and `5,6` after a jump).
 *
 * Emptying a page removes one page worth of elements from the feed, so every cached page above
 * it must shift down by one — across gaps too. Previously the head of a far island was dropped
 * instead of being re-labelled, silently destroying cached data the caller never asked to
 * remove.
 */
class RemoveAllNonContiguousPagesTest {

    private val capacity = 3

    /** Cache layout: pages `1,2` plus an island of [islandLen] pages starting at [islandStart]. */
    private suspend fun createTwoIslandPaginator(
        islandStart: Int = 5,
        islandLen: Int = 2,
    ): MutablePaginator<String> {
        val paginator = MutablePaginator<String> { page: Int ->
            LoadResult(MutableList(capacity) { "p${page}_item$it" })
        }
        paginator.core.resize(capacity = capacity, resize = false, silently = true)

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.jump(BookmarkInt(islandStart), silentlyLoading = true, silentlyResult = true)
        repeat(islandLen - 1) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        return paginator
    }

    private fun MutablePaginator<String>.pageContents(): Map<Int, List<String>> =
        cache.pages.associateWith { page -> cache.getStateOf(page)!!.data }

    @Test
    fun `two island layout is what these tests assume`() = runTest {
        val paginator = createTwoIslandPaginator()
        assertEquals(listOf(1, 2, 5, 6), paginator.cache.pages)
        assertEquals(5, paginator.cache.startContextPage)
        assertEquals(6, paginator.cache.endContextPage)
    }

    @Test
    fun `emptying a page keeps every element that did not match the predicate`() = runTest {
        val paginator = createTwoIslandPaginator()

        val removed = paginator.removeAll(silently = true) { it.startsWith("p2_") }

        assertEquals(3, removed)
        assertEquals(
            listOf(
                "p1_item0", "p1_item1", "p1_item2",
                "p5_item0", "p5_item1", "p5_item2",
                "p6_item0", "p6_item1", "p6_item2",
            ),
            paginator.cache.pages.flatMap { paginator.cache.getStateOf(it)!!.data },
        )
    }

    @Test
    fun `far island shifts down as a whole`() = runTest {
        val paginator = createTwoIslandPaginator()

        paginator.removeAll(silently = true) { it.startsWith("p2_") }

        assertEquals(
            mapOf(
                1 to listOf("p1_item0", "p1_item1", "p1_item2"),
                4 to listOf("p5_item0", "p5_item1", "p5_item2"),
                5 to listOf("p6_item0", "p6_item1", "p6_item2"),
            ),
            paginator.pageContents(),
        )
    }

    @Test
    fun `context window follows the pages it was pointing at`() = runTest {
        val paginator = createTwoIslandPaginator()
        assertEquals(5, paginator.cache.startContextPage)
        assertEquals(6, paginator.cache.endContextPage)

        paginator.removeAll(silently = true) { it.startsWith("p2_") }

        // The window was showing old pages 5 and 6; they are now 4 and 5.
        assertEquals(4, paginator.cache.startContextPage)
        assertEquals(5, paginator.cache.endContextPage)
    }

    @Test
    fun `single page island is re-labelled, not deleted`() = runTest {
        val paginator = createTwoIslandPaginator(islandStart = 5, islandLen = 1)

        paginator.removeAll(silently = true) { it.startsWith("p2_") }

        assertEquals(
            mapOf(
                1 to listOf("p1_item0", "p1_item1", "p1_item2"),
                4 to listOf("p5_item0", "p5_item1", "p5_item2"),
            ),
            paginator.pageContents(),
        )
        assertEquals(4, paginator.cache.startContextPage)
        assertEquals(4, paginator.cache.endContextPage)
    }

    @Test
    fun `longer island shifts every one of its pages`() = runTest {
        val paginator = createTwoIslandPaginator(islandStart = 5, islandLen = 3)

        paginator.removeAll(silently = true) { it.startsWith("p2_") }

        assertEquals(
            mapOf(
                1 to listOf("p1_item0", "p1_item1", "p1_item2"),
                4 to listOf("p5_item0", "p5_item1", "p5_item2"),
                5 to listOf("p6_item0", "p6_item1", "p6_item2"),
                6 to listOf("p7_item0", "p7_item1", "p7_item2"),
            ),
            paginator.pageContents(),
        )
    }

    @Test
    fun `gap width does not change the shift amount`() = runTest {
        val paginator = createTwoIslandPaginator(islandStart = 10, islandLen = 2)

        paginator.removeAll(silently = true) { it.startsWith("p2_") }

        // One page worth of elements was removed, so the island moves by exactly one page,
        // regardless of how wide the gap is.
        assertEquals(
            mapOf(
                1 to listOf("p1_item0", "p1_item1", "p1_item2"),
                9 to listOf("p10_item0", "p10_item1", "p10_item2"),
                10 to listOf("p11_item0", "p11_item1", "p11_item2"),
            ),
            paginator.pageContents(),
        )
    }

    @Test
    fun `contiguous pages keep their existing behaviour`() = runTest {
        val paginator = MutablePaginator<String> { page: Int ->
            LoadResult(MutableList(capacity) { "p${page}_item$it" })
        }
        paginator.core.resize(capacity = capacity, resize = false, silently = true)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        repeat(3) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        assertEquals(listOf(1, 2, 3, 4), paginator.cache.pages)

        paginator.removeAll(silently = true) { it.startsWith("p2_") }

        assertEquals(
            mapOf(
                1 to listOf("p1_item0", "p1_item1", "p1_item2"),
                2 to listOf("p3_item0", "p3_item1", "p3_item2"),
                3 to listOf("p4_item0", "p4_item1", "p4_item2"),
            ),
            paginator.pageContents(),
        )
        assertEquals(1, paginator.cache.startContextPage)
        assertEquals(3, paginator.cache.endContextPage)
    }
}
