package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagingCoreSerializationTest {

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
    fun `round-trip with all SuccessPages`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..5) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }

        val snapshot = paginator.core.saveState()

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        assertEquals(paginator.cache.pages, restored.pages)
        assertEquals(paginator.core.capacity, restored.capacity)
        assertEquals(paginator.cache.startContextPage, restored.startContextPage)
        assertEquals(paginator.cache.endContextPage, restored.endContextPage)

        for (page in 1..5) {
            val original = paginator.cache.getStateOf(page)!!
            val restoredState = restored.getStateOf(page)!!
            assertTrue(restoredState.isSuccessState())
            assertEquals(original.data, restoredState.data)
            assertEquals(original.page, restoredState.page)
        }
    }

    @Test
    fun `ErrorPage converted to SuccessPage and marked dirty`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Set page 2 to ErrorPage with cached data
        val cachedData = paginator.cache.getStateOf(2)!!.data
        paginator.cache.setState(
            PageState.ErrorPage(
                exception = RuntimeException("test error"),
                page = 2,
                data = cachedData,
            ),
            silently = true,
        )

        val snapshot = paginator.core.saveState()

        // Verify snapshot entry
        val entry = snapshot.entries.first { it.page == 2 }
        assertTrue(entry.wasDirty)
        assertEquals(PageEntryType.SUCCESS, entry.type)
        assertEquals(cachedData, entry.data)

        // Restore
        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        val restoredState = restored.getStateOf(2)!!
        assertTrue(restoredState.isSuccessState())
        assertEquals(cachedData, restoredState.data)
        assertTrue(restored.isDirty(2))
    }

    @Test
    fun `ProgressPage converted to SuccessPage and marked dirty`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val cachedData = paginator.cache.getStateOf(2)!!.data
        paginator.cache.setState(
            PageState.ProgressPage(page = 2, data = cachedData),
            silently = true,
        )

        val snapshot = paginator.core.saveState()

        val entry = snapshot.entries.first { it.page == 2 }
        assertTrue(entry.wasDirty)

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        val restoredState = restored.getStateOf(2)!!
        assertTrue(restoredState.isSuccessState())
        assertTrue(restored.isDirty(2))
    }

    @Test
    fun `EmptyPage preserved`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.cache.setState(
            PageState.EmptyPage<TestItem>(page = 2, data = emptyList()),
            silently = true,
        )

        val snapshot = paginator.core.saveState()

        val entry = snapshot.entries.first { it.page == 2 }
        assertEquals(PageEntryType.EMPTY, entry.type)
        assertTrue(entry.data.isEmpty())

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        val restoredState = restored.getStateOf(2)!!
        assertTrue(restoredState.isEmptyState())
    }

    @Test
    fun `dirty pages preserved through round-trip`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..3) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }

        paginator.core.markDirty(1)
        paginator.core.markDirty(3)

        val snapshot = paginator.core.saveState()

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        assertTrue(restored.isDirty(1))
        assertFalse(restored.isDirty(2))
        assertTrue(restored.isDirty(3))
    }

    @Test
    fun `context window restored`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val snapshot = paginator.core.saveState()

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        assertEquals(paginator.cache.startContextPage, restored.startContextPage)
        assertEquals(paginator.cache.endContextPage, restored.endContextPage)
    }

    @Test
    fun `IDs regenerated after restore`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val originalId = paginator.cache.getStateOf(1)!!.id
        val snapshot = paginator.core.saveState()

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        val restoredId = restored.getStateOf(1)!!.id
        assertNotEquals(originalId, restoredId)
    }

    @Test
    fun `empty cache round-trip`() {
        val core = PagingCore<TestItem>(initialCapacity = 10)
        val snapshot = core.saveState()

        assertEquals(0, snapshot.entries.size)
        assertEquals(0, snapshot.startContextPage)
        assertEquals(0, snapshot.endContextPage)
        assertEquals(10, snapshot.capacity)

        val restored = PagingCore<TestItem>()
        restored.restoreState(snapshot, silently = true)

        assertEquals(0, restored.size)
        assertEquals(0, restored.startContextPage)
        assertEquals(0, restored.endContextPage)
        assertEquals(10, restored.capacity)
    }

    @Test
    fun `JSON string round-trip`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..3) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        paginator.core.markDirty(2)

        val json = paginator.core.saveStateToJson(TestItem.serializer())

        assertTrue(json.isNotBlank())

        val restored = PagingCore<TestItem>()
        restored.restoreStateFromJson(json, TestItem.serializer(), silently = true)

        assertEquals(paginator.cache.pages, restored.pages)
        assertEquals(paginator.core.capacity, restored.capacity)
        assertEquals(paginator.cache.startContextPage, restored.startContextPage)
        assertEquals(paginator.cache.endContextPage, restored.endContextPage)
        assertTrue(restored.isDirty(2))

        for (page in 1..3) {
            val original = paginator.cache.getStateOf(page)!!
            val restoredState = restored.getStateOf(page)!!
            assertEquals(original.data, restoredState.data)
        }
    }

    // ── errorMessage tests ──────────────────────────────────────────────────

    @Test
    fun `errorMessage preserved for ErrorPage`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val cachedData = paginator.cache.getStateOf(2)!!.data
        paginator.cache.setState(
            PageState.ErrorPage(
                exception = RuntimeException("network timeout"),
                page = 2,
                data = cachedData,
            ),
            silently = true,
        )

        val snapshot = paginator.core.saveState()
        val entry = snapshot.entries.first { it.page == 2 }
        assertEquals("network timeout", entry.errorMessage)

        val successEntry = snapshot.entries.first { it.page == 1 }
        assertNull(successEntry.errorMessage)
    }

    @Test
    fun `errorMessage null for non-error pages`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val snapshot = paginator.core.saveState()
        for (entry in snapshot.entries) {
            assertNull(entry.errorMessage)
        }
    }

    @Test
    fun `errorMessage survives JSON round-trip`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        val cachedData = paginator.cache.getStateOf(2)!!.data
        paginator.cache.setState(
            PageState.ErrorPage(
                exception = IllegalStateException("bad state"),
                page = 2,
                data = cachedData,
            ),
            silently = true,
        )

        val json = paginator.core.saveStateToJson(TestItem.serializer())
        assertTrue(json.contains("bad state"))

        val restored = PagingCore<TestItem>()
        restored.restoreStateFromJson(json, TestItem.serializer(), silently = true)
        assertTrue(restored.isDirty(2))
    }

    // ── contextOnly tests ───────────────────────────────────────────────────

    @Test
    fun `contextOnly saves only pages in context window`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..5) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }
        // Context window is 1..5, but let's set it manually to 2..4
        // by jumping to page 2 and loading through page 4
        val paginator2 = createTestPaginator()
        paginator2.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator2.goNextPage(silentlyLoading = true, silentlyResult = true) // page 3
        paginator2.goNextPage(silentlyLoading = true, silentlyResult = true) // page 4

        val fullSnapshot = paginator2.core.saveState(contextOnly = false)
        val contextSnapshot = paginator2.core.saveState(contextOnly = true)

        // Context window is 2..4
        assertEquals(paginator2.cache.startContextPage, contextSnapshot.startContextPage)
        assertEquals(paginator2.cache.endContextPage, contextSnapshot.endContextPage)

        // contextOnly should have only pages within context window
        val contextPages = contextSnapshot.entries.map { it.page }.toSet()
        for (page in contextPages) {
            assertTrue(page in paginator2.cache.startContextPage..paginator2.cache.endContextPage)
        }

        // Full snapshot may have more pages
        assertTrue(fullSnapshot.entries.size >= contextSnapshot.entries.size)
    }

    @Test
    fun `contextOnly false saves all cached pages`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        for (i in 2..5) {
            paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        }

        val snapshot = paginator.core.saveState(contextOnly = false)
        assertEquals(5, snapshot.entries.size)
    }

    // ── validation tests ────────────────────────────────────────────────────

    @Test
    fun `restoreState rejects negative startContextPage`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = emptyList(),
            startContextPage = -1,
            endContextPage = 0,
            capacity = 10,
        )
        assertFailsWith<IllegalArgumentException> {
            core.restoreState(snapshot)
        }
    }

    @Test
    fun `restoreState rejects negative endContextPage`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = emptyList(),
            startContextPage = 0,
            endContextPage = -1,
            capacity = 10,
        )
        assertFailsWith<IllegalArgumentException> {
            core.restoreState(snapshot)
        }
    }

    @Test
    fun `restoreState rejects startContextPage greater than endContextPage`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = emptyList(),
            startContextPage = 5,
            endContextPage = 3,
            capacity = 10,
        )
        assertFailsWith<IllegalArgumentException> {
            core.restoreState(snapshot)
        }
    }

    @Test
    fun `restoreState rejects zero capacity`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = emptyList(),
            startContextPage = 0,
            endContextPage = 0,
            capacity = 0,
        )
        assertFailsWith<IllegalArgumentException> {
            core.restoreState(snapshot)
        }
    }

    @Test
    fun `restoreState rejects duplicate page numbers`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = listOf(
                PageEntry(page = 1, type = PageEntryType.SUCCESS, data = listOf(TestItem("a", 1)), wasDirty = false),
                PageEntry(page = 1, type = PageEntryType.SUCCESS, data = listOf(TestItem("b", 2)), wasDirty = false),
            ),
            startContextPage = 1,
            endContextPage = 1,
            capacity = 10,
        )
        assertFailsWith<IllegalArgumentException> {
            core.restoreState(snapshot)
        }
    }

    @Test
    fun `restoreState rejects page number less than 1`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = listOf(
                PageEntry(page = 0, type = PageEntryType.SUCCESS, data = listOf(TestItem("a", 1)), wasDirty = false),
            ),
            startContextPage = 0,
            endContextPage = 0,
            capacity = 10,
        )
        assertFailsWith<IllegalArgumentException> {
            core.restoreState(snapshot)
        }
    }

    @Test
    fun `restoreState accepts both context pages as zero`() {
        val core = PagingCore<TestItem>()
        val snapshot = PagingCoreSnapshot<TestItem>(
            entries = emptyList(),
            startContextPage = 0,
            endContextPage = 0,
            capacity = 10,
        )
        core.restoreState(snapshot, silently = true)
        assertEquals(0, core.startContextPage)
        assertEquals(0, core.endContextPage)
    }
}
