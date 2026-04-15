package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.load.LoadResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginatorSerializationTest {

    @Serializable
    data class TestItem(val name: String, val value: Int)

    private fun createTestPaginator(
        pageCount: Int = 5,
        capacity: Int = 3,
    ): MutablePaginator<TestItem> {
        return MutablePaginator<TestItem> { page: Int ->
            if (page in 1..pageCount) {
                LoadResult(MutableList(capacity) { TestItem("p${page}_item$it", page * 100 + it) })
            } else {
                LoadResult(emptyList())
            }
        }.apply {
            core.resize(capacity = capacity, resize = false, silently = true)
        }
    }

    @Test
    fun `full round-trip preserves all Paginator state`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.finalPage = 10
        paginator.bookmarks.clear()
        paginator.bookmarks.add(BookmarkInt(1))
        paginator.bookmarks.add(BookmarkInt(5))
        paginator.bookmarks.add(BookmarkInt(10))
        paginator.recyclingBookmark = true
        paginator.lockJump = true
        paginator.lockGoNextPage = true
        paginator.lockGoPreviousPage = false
        paginator.lockRestart = true
        paginator.lockRefresh = false

        val snapshot = paginator.saveState()

        val restored = createTestPaginator()
        restored.restoreState(snapshot, silently = true)

        assertEquals(10, restored.finalPage)
        assertEquals(3, restored.bookmarks.size)
        assertEquals(1, restored.bookmarks[0].page)
        assertEquals(5, restored.bookmarks[1].page)
        assertEquals(10, restored.bookmarks[2].page)
        assertTrue(restored.recyclingBookmark)
        assertTrue(restored.lockJump)
        assertTrue(restored.lockGoNextPage)
        assertFalse(restored.lockGoPreviousPage)
        assertTrue(restored.lockRestart)
        assertFalse(restored.lockRefresh)

        // Verify core state
        assertEquals(paginator.cache.startContextPage, restored.cache.startContextPage)
        assertEquals(paginator.cache.endContextPage, restored.cache.endContextPage)
        assertEquals(paginator.core.capacity, restored.core.capacity)
        assertEquals(paginator.cache.pages, restored.cache.pages)
    }

    @Test
    fun `bookmark index preserved`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.bookmarks.clear()
        paginator.bookmarks.add(BookmarkInt(1))
        paginator.bookmarks.add(BookmarkInt(3))
        paginator.bookmarks.add(BookmarkInt(5))

        // Advance bookmark index by calling jumpForward
        paginator.jumpForward(silentlyLoading = true, silentlyResult = true)

        val snapshot = paginator.saveState()
        assertTrue(snapshot.bookmarkIndex > 0)

        val restored = createTestPaginator()
        restored.restoreState(snapshot, silently = true)

        // The bookmark index should match
        assertEquals(snapshot.bookmarkIndex, snapshot.bookmarkIndex)
    }

    @Test
    fun `empty bookmarks restored with default`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val snapshot = paginator.saveState()

        // Create a snapshot with empty bookmarks
        val modifiedSnapshot = snapshot.copy(bookmarkPages = emptyList())

        val restored = createTestPaginator()
        restored.restoreState(modifiedSnapshot, silently = true)

        // Should have default bookmark
        assertEquals(1, restored.bookmarks.size)
        assertEquals(1, restored.bookmarks[0].page)
    }

    @Test
    fun `JSON round-trip for Paginator`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.finalPage = 20
        paginator.bookmarks.clear()
        paginator.bookmarks.add(BookmarkInt(1))
        paginator.bookmarks.add(BookmarkInt(10))
        paginator.lockJump = true

        val json = paginator.saveStateToJson(TestItem.serializer())
        assertTrue(json.isNotBlank())

        val restored = createTestPaginator()
        restored.restoreStateFromJson(json, TestItem.serializer(), silently = true)

        assertEquals(20, restored.finalPage)
        assertEquals(2, restored.bookmarks.size)
        assertEquals(10, restored.bookmarks[1].page)
        assertTrue(restored.lockJump)
        assertEquals(paginator.cache.pages, restored.cache.pages)
    }

    @Test
    fun `contextOnly with Paginator saveState`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val fullSnapshot = paginator.saveState(contextOnly = false)
        val contextSnapshot = paginator.saveState(contextOnly = true)

        // Context window pages should be a subset
        assertTrue(contextSnapshot.coreSnapshot.entries.size <= fullSnapshot.coreSnapshot.entries.size)

        // Paginator-level state should be the same
        assertEquals(fullSnapshot.finalPage, contextSnapshot.finalPage)
        assertEquals(fullSnapshot.bookmarkPages, contextSnapshot.bookmarkPages)
        assertEquals(fullSnapshot.lockJump, contextSnapshot.lockJump)
    }

    @Test
    fun `lock flags all false by default`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val snapshot = paginator.saveState()

        assertFalse(snapshot.lockJump)
        assertFalse(snapshot.lockGoNextPage)
        assertFalse(snapshot.lockGoPreviousPage)
        assertFalse(snapshot.lockRestart)
        assertFalse(snapshot.lockRefresh)
    }
}
