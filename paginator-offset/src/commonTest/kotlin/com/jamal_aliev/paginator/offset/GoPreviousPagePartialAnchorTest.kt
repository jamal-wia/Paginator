package com.jamal_aliev.paginator.offset

import com.jamal_aliev.paginator.offset.bookmark.BookmarkInt
import com.jamal_aliev.paginator.offset.load.LoadResult
import com.jamal_aliev.paginator.offset.page.OffsetPageState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the "stuck on a non-filled start anchor" bug.
 *
 * When the paginator is anchored on a page that is **not** a filled-success page
 * (e.g. you `jump` straight to a partial last page — a common case, since last
 * pages usually carry fewer than `capacity` items), `goPreviousPage` must still
 * navigate to the previous page. The previous behavior reloaded the anchor in
 * place, so the user could never reach the earlier pages.
 */
class GoPreviousPagePartialAnchorTest {

    /** Pages 1..5 are full; page 6 is a partial last page (2 of 5); 7+ are empty. */
    private fun partialLastPagePaginator(capacity: Int = 5) =
        MutablePaginator<String> { page ->
            when {
                page <= 5 -> LoadResult(List(capacity) { "p${page}_$it" })
                page == 6 -> LoadResult(List(2) { "p6_$it" }) // partial last page
                else -> LoadResult(emptyList())
            }
        }.apply { core.resize(capacity = capacity, resize = false, silently = true) }

    @Test
    fun `goPreviousPage from partial last-page anchor advances instead of reloading`() = runTest {
        val paginator = partialLastPagePaginator(capacity = 5)
        paginator.jump(BookmarkInt(6), silentlyLoading = true, silentlyResult = true)
        assertEquals(6, paginator.cache.startContextPage)
        assertEquals(6, paginator.cache.endContextPage)

        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)

        // Must reach page 5 — not be stuck reloading the partial page 6.
        assertEquals(5, paginator.cache.startContextPage)
        // Page 6 (the partial last page) stays as the end boundary.
        assertEquals(6, paginator.cache.endContextPage)
    }

    @Test
    fun `repeated goPreviousPage from partial anchor reaches page 1 without getting stuck`() =
        runTest {
            val paginator = partialLastPagePaginator(capacity = 5)
            paginator.jump(BookmarkInt(6), silentlyLoading = true, silentlyResult = true)

            var steps = 0
            while (paginator.cache.startContextPage > 1 && steps++ < 20) {
                paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
            }

            assertEquals(1, paginator.cache.startContextPage)
            assertTrue(steps <= 6, "should walk 6 -> 1 in 5 steps, but took $steps (stuck?)")
        }

    @Test
    fun `goPreviousPage from errored anchor advances instead of getting stuck`() = runTest {
        var failPage6 = true
        val paginator = MutablePaginator<String> { page ->
            if (page == 6 && failPage6) throw RuntimeException("boom on page 6")
            LoadResult(List(core.capacity) { "p${page}_$it" })
        }.apply { core.resize(capacity = 5, resize = false, silently = true) }

        // Jump lands on page 6, whose load fails -> page 6 is an Error state anchor.
        paginator.jump(BookmarkInt(6), silentlyLoading = true, silentlyResult = true)
        assertEquals(6, paginator.cache.startContextPage)

        // Going back must reach page 5, not endlessly retry the errored page 6.
        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertEquals(5, paginator.cache.startContextPage)
    }

    @Test
    fun `when the previous page itself comes back non-Success the window stays consistent and recovers`() =
        runTest {
            var page5Fails = true
            val paginator = MutablePaginator<String> { page ->
                when {
                    page == 5 && page5Fails -> throw RuntimeException("boom on page 5")
                    page == 6 -> LoadResult(List(2) { "p6_$it" }) // partial last-page anchor
                    else -> LoadResult(List(core.capacity) { "p${page}_$it" })
                }
            }.apply { core.resize(capacity = 5, resize = false, silently = true) }

            paginator.jump(BookmarkInt(6), silentlyLoading = true, silentlyResult = false)

            // pivot-1 (page 5) errors. The library must NOT corrupt the window or create a hole:
            // it does not advance over the non-filled page, surfaces the error, and stays put.
            val errored = paginator.goPreviousPage(silentlyLoading = true, silentlyResult = false)
            assertTrue(errored is OffsetPageState.Error, "expected page-5 Error, was $errored")
            assertEquals(
                6,
                paginator.cache.startContextPage,
                "window must not advance over a non-Success page"
            )
            assertEquals(6, paginator.cache.endContextPage)
            // The visible snapshot is still sensible (the partial anchor page 6 is shown, no crash).
            val visibleAfterError = paginator.core.snapshot.first().map { it.page }
            assertTrue(
                6 in visibleAfterError,
                "anchor page 6 should remain visible, was $visibleAfterError"
            )

            // Once the source recovers, the very same call now makes progress — no permanent stuck.
            page5Fails = false
            val recovered = paginator.goPreviousPage(silentlyLoading = true, silentlyResult = false)
            assertTrue(
                recovered is OffsetPageState.Success,
                "page 5 should load on retry, was $recovered"
            )
            assertEquals(5, paginator.cache.startContextPage)
            assertEquals(6, paginator.cache.endContextPage)
            val visibleAfterRecovery = paginator.core.snapshot.first().map { it.page }
            assertTrue(
                5 in visibleAfterRecovery && 6 in visibleAfterRecovery,
                "pages 5 and 6 should be visible, was $visibleAfterRecovery"
            )
        }

    @Test
    fun `jump onto a partial page absorbs the adjacent cached page, then goPreviousPage loads the next new page`() =
        runTest {
            val paginator = MutablePaginator<String> { page ->
                when {
                    page <= 6 -> LoadResult(List(core.capacity) { "p${page}_$it" })
                    page == 7 -> LoadResult(List(2) { "p7_$it" }) // partial last page
                    else -> LoadResult(emptyList())
                }
            }.apply { core.resize(capacity = 5, resize = false, silently = true) }

            // Cache page 6 (filled), then jump to the partial page 7 sitting right above it.
            paginator.jump(BookmarkInt(6), silentlyLoading = true, silentlyResult = true)
            paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = false)

            // jump absorbs the adjacent cached page 6 into the window — both 6 and 7 are visible.
            // (Don't hide already-loaded neighbors behind a partial landing page.)
            assertEquals(6, paginator.cache.startContextPage)
            assertEquals(7, paginator.cache.endContextPage)
            assertEquals(listOf(6, 7), paginator.core.snapshot.first().map { it.page })

            // With 6 already in the window, going back loads the next genuinely-new page (5),
            // not a re-reveal of the already-visible page 6.
            val state = paginator.goPreviousPage(silentlyLoading = true, silentlyResult = false)
            assertEquals(5, state.page, "should load the next new page 5, was ${state.page}")
            assertEquals(5, paginator.cache.startContextPage)
            assertEquals(listOf(5, 6, 7), paginator.core.snapshot.first().map { it.page })
        }

    @Test
    fun `jump onto a non-filled page keeps it at a boundary, never spanning the window across it`() =
        runTest {
            val paginator = MutablePaginator<String> { page ->
                if (page == 6) throw RuntimeException("boom on page 6")
                LoadResult(List(core.capacity) { "p${page}_$it" })
            }.apply { core.resize(capacity = 5, resize = false, silently = true) }

            // Cache filled pages 5 and 7, leaving page 6 (which errors) between them.
            paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
            paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = true)

            // Jump onto the errored page 6. The window must NOT span 5..7 (which would bury the
            // non-filled page 6 in the interior). It absorbs backward toward the cached page 5,
            // leaving page 6 at the end boundary.
            paginator.jump(BookmarkInt(6), silentlyLoading = true, silentlyResult = true)
            assertEquals(5, paginator.cache.startContextPage)
            assertEquals(
                6,
                paginator.cache.endContextPage,
                "page 6 must stay at the boundary, not be buried inside 5..7"
            )
        }

    @Test
    fun `jump onto a non-filled page with cached pages above absorbs them and forward nav still advances`() =
        runTest {
            var page1Fails = true
            val paginator = MutablePaginator<String> { page ->
                if (page == 1 && page1Fails) throw RuntimeException("boom on page 1")
                LoadResult(List(core.capacity) { "p${page}_$it" })
            }.apply { core.resize(capacity = 5, resize = false, silently = true) }

            // Cache filled pages 2 and 3 (nothing below page 1 exists).
            paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
            paginator.jump(BookmarkInt(3), silentlyLoading = true, silentlyResult = true)

            // Jump onto page 1, which errors, with cached pages 2,3 above it.
            // Forward absorption keeps page 1 at the START boundary (no jump-over) and pulls in 2,3.
            paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
            assertEquals(1, paginator.cache.startContextPage)
            assertEquals(
                3,
                paginator.cache.endContextPage,
                "cached pages 2,3 above the errored landing must be absorbed"
            )

            // Because endContextPage is a filled page (3), forward navigation advances to a NEW page
            // instead of being trapped retrying the errored page 1.
            val next = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
            assertEquals(
                4,
                next.page,
                "goNextPage should advance past cached pages, was ${next.page}"
            )
        }
}
