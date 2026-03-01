package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isEmptyState
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class TestItem(val name: String, val value: Int)

class PagingCoreSerializationTest {

    private fun createTestPaginator(
        pageCount: Int = 5,
        capacity: Int = 3,
    ): MutablePaginator<TestItem> {
        return MutablePaginator<TestItem> { page: Int ->
            if (page in 1..pageCount) {
                MutableList(capacity) { TestItem("p${page}_item$it", page * 100 + it) }
            } else {
                emptyList()
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

        assertEquals(paginator.core.pages, restored.pages)
        assertEquals(paginator.core.capacity, restored.capacity)
        assertEquals(paginator.core.startContextPage, restored.startContextPage)
        assertEquals(paginator.core.endContextPage, restored.endContextPage)

        for (page in 1..5) {
            val original = paginator.core.getStateOf(page)!!
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
        val cachedData = paginator.core.getStateOf(2)!!.data
        paginator.core.setState(
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

        val cachedData = paginator.core.getStateOf(2)!!.data
        paginator.core.setState(
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

        paginator.core.setState(
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

        assertEquals(paginator.core.startContextPage, restored.startContextPage)
        assertEquals(paginator.core.endContextPage, restored.endContextPage)
    }

    @Test
    fun `IDs regenerated after restore`() = runTest {
        val paginator = createTestPaginator()
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val originalId = paginator.core.getStateOf(1)!!.id
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

        assertEquals(paginator.core.pages, restored.pages)
        assertEquals(paginator.core.capacity, restored.capacity)
        assertEquals(paginator.core.startContextPage, restored.startContextPage)
        assertEquals(paginator.core.endContextPage, restored.endContextPage)
        assertTrue(restored.isDirty(2))

        for (page in 1..3) {
            val original = paginator.core.getStateOf(page)!!
            val restoredState = restored.getStateOf(page)!!
            assertEquals(original.data, restoredState.data)
        }
    }
}
