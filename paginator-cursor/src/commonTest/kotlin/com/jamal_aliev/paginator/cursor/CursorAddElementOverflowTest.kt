package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.cursor.MutableCursorPaginator.CursorBookmarkFactory
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.extension.addElement
import com.jamal_aliev.paginator.cursor.extension.insertAfter
import com.jamal_aliev.paginator.cursor.extension.insertBefore
import com.jamal_aliev.paginator.cursor.extension.loadedPagesCount
import com.jamal_aliev.paginator.cursor.extension.moveElement
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cursor counterpart of the offset `AddElementOverflowTest`.
 *
 * Cursor pages are keyed by a server-issued `self`, which the client **cannot** synthesise — so
 * tail-page creation is opt-in via a [CursorBookmarkFactory] (the documented cursor asymmetry; see
 * `docs/15. reactive-sources.md` → "Tail-insert overflow caveat"). When a factory *is* supplied, the
 * new tail page's **state** is produced by the same default same-class factory the whole insert family
 * shares ([defaultCursorOverflowPageFactory]) — fresh id, correct bookmark, source class preserved.
 */
class CursorAddElementOverflowTest {

    private class MySuccess(bookmark: CursorBookmark<String>, data: List<String>) :
        CursorPageState.Success<String, String>(bookmark, data)

    private val mintTail = CursorBookmarkFactory<String> { idx, previous ->
        CursorBookmark(prev = previous.self, self = "ovf$idx", next = null)
    }

    private suspend fun populated(pageCount: Int, capacity: Int): MutableCursorPaginator<String, String> {
        val backend = FakeCursorBackend(FakeCursorBackend.defaultPages(pageCount, capacity))
        val paginator = mutableCursorPaginatorOf(backend, capacity = capacity)
        paginator.jump(
            bookmark = CursorBookmark(prev = null, self = backend.head.self, next = null),
            silentlyLoading = true,
            silentlyResult = true,
        )
        repeat(pageCount - 1) { paginator.goNextPage(silentlyLoading = true, silentlyResult = true) }
        return paginator
    }

    @Test
    fun `addElement with a bookmarkFactory creates a same-class tail page (default initPageState)`() = runTest {
        val paginator = populated(pageCount = 1, capacity = 3) // p0 = [p0_item0..2], full, genuine tail
        val sourceId = paginator.cache.getStateOf("p0")!!.id

        paginator.addElement("new", silently = true, bookmarkFactory = mintTail)

        val tail = paginator.cache.getStateOf("ovf0")
        assertNotNull(tail)
        assertTrue(tail is CursorPageState.Success)
        assertEquals(listOf("new"), tail.data)
        // The default factory embeds the CORRECT new bookmark (the old copy() fallback embedded the
        // source page's bookmark, i.e. self="p0") and a fresh id.
        assertEquals("ovf0", tail.bookmark.self)
        assertEquals("p0", tail.bookmark.prev)
        assertTrue(tail.id != sourceId, "overflow page must have a fresh id")
        assertEquals("ovf0", paginator.cache.getCursorOf("p0")?.next)
    }

    @Test
    fun `addElement without a bookmarkFactory drops the tail overflow (cursor cannot mint self)`() = runTest {
        val paginator = populated(pageCount = 1, capacity = 3)

        // No bookmarkFactory → the documented cursor exception: overflow is dropped, no page minted.
        val appended = paginator.addElement("new", silently = true)

        assertTrue(appended)
        assertEquals(listOf("p0_item0", "p0_item1", "p0_item2"), paginator.cache.getStateOf("p0")!!.data)
        assertEquals(1, paginator.loadedPagesCount)
    }

    @Test
    fun `insertAfter threads the bookmarkFactory so overflow creates a tail page`() = runTest {
        val paginator = populated(pageCount = 1, capacity = 3)

        // Before this change insertAfter had no factory param and always dropped tail overflow.
        val inserted = paginator.insertAfter("X", silently = true, bookmarkFactory = mintTail) {
            it == "p0_item2"
        }

        assertTrue(inserted)
        // X is inserted at index 3 (after p0_item2, the last item); the cascade trims from the end,
        // so X itself overflows into the new tail page. Visible order stays p0_item0..2, X.
        assertEquals(listOf("p0_item0", "p0_item1", "p0_item2"), paginator.cache.getStateOf("p0")!!.data)
        val tail = paginator.cache.getStateOf("ovf0")
        assertNotNull(tail)
        assertEquals(listOf("X"), tail.data)
        assertEquals("ovf0", tail.bookmark.self)
    }

    @Test
    fun `insertBefore drops overflow without a bookmarkFactory`() = runTest {
        val paginator = populated(pageCount = 1, capacity = 3)

        val inserted = paginator.insertBefore("X", silently = true) { it == "p0_item2" }

        assertTrue(inserted)
        assertEquals(listOf("p0_item0", "p0_item1", "X"), paginator.cache.getStateOf("p0")!!.data)
        assertEquals(1, paginator.loadedPagesCount) // p0_item2 dropped, no tail minted
    }

    @Test
    fun `moveElement threads initPageState so the created tail page embeds the correct bookmark`() = runTest {
        val paginator = populated(pageCount = 2, capacity = 3) // p0, p1 both full; p1 is the tail

        // Move p0_item0 to the head of the full tail page p1 → its tail (p1_item2) overflows.
        paginator.moveElement(
            fromSelf = "p0", fromIndex = 0,
            toSelf = "p1", toIndex = 0,
            silently = true,
            bookmarkFactory = mintTail,
        )

        val tail = paginator.cache.getStateOf("ovf0")
        assertNotNull(tail)
        assertEquals(listOf("p1_item2"), tail.data)
        // The fix: the default initPageState embeds self="ovf0"; the old copy() fallback would have
        // embedded p1's bookmark (self="p1").
        assertEquals("ovf0", tail.bookmark.self)
    }

    @Test
    fun `created tail page uses the configured success initializer (custom subclass preserved)`() = runTest {
        val paginator = populated(pageCount = 1, capacity = 3)
        paginator.core.initializerSuccessPage = { bookmark, data, _ -> MySuccess(bookmark, data) }

        paginator.addElement("new", silently = true, bookmarkFactory = mintTail)

        val tail = paginator.cache.getStateOf("ovf0")
        assertTrue(
            tail is MySuccess,
            "tail page should be the custom subclass, was ${tail?.let { it::class.simpleName }}",
        )
    }
}
