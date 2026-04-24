package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.extension.isErrorState
import com.jamal_aliev.paginator.page.PageState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransactionTest {

    // =========================================================================
    // Basic success / failure
    // =========================================================================

    @Test
    fun `transaction returns block result on success`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)

        val result = paginator.transaction {
            (this as MutablePaginator).setElement("new_item", page = 1, index = 0, silently = true)
            42
        }

        assertEquals(42, result)
        assertEquals("new_item", paginator.cache.getElement(1, 0))
    }

    @Test
    fun `transaction rolls back on exception`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val originalElement = paginator.cache.getElement(1, 0)

        assertFailsWith<IllegalStateException> {
            paginator.transaction {
                (this as MutablePaginator).setElement("modified", page = 1, index = 0, silently = true)
                error("transaction failed")
            }
        }

        assertEquals(originalElement, paginator.cache.getElement(1, 0))
    }

    @Test
    fun `transaction propagates original exception`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)

        val ex = assertFailsWith<ArithmeticException> {
            paginator.transaction {
                throw ArithmeticException("divide by zero")
            }
        }

        assertEquals("divide by zero", ex.message)
    }

    // =========================================================================
    // CancellationException
    // =========================================================================

    @Test
    fun `transaction rolls back on cancellation`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val originalPages = paginator.core.states.map { it.page to it.data.toList() }

        val job = launch {
            paginator.transaction {
                (this as MutablePaginator).setElement("cancelled_item", page = 1, index = 0, silently = true)
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { /* never resumes */ }
            }
        }

        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        val restoredPages = paginator.core.states.map { it.page to it.data.toList() }
        assertEquals(originalPages, restoredPages)
    }

    // =========================================================================
    // CRUD rollback
    // =========================================================================

    @Test
    fun `setElement is rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val original = paginator.cache.getElement(2, 1)

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                (this as MutablePaginator).setElement("replaced", page = 2, index = 1, silently = true)
                assertEquals("replaced", cache.getElement(2, 1))
                throw RuntimeException("fail")
            }
        }

        assertEquals(original, paginator.cache.getElement(2, 1))
    }

    @Test
    fun `removeElement is rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val originalData = paginator.cache.getStateOf(1)!!.data.toList()
        val originalSize = paginator.cache.size

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                (this as MutablePaginator).removeElement(page = 1, index = 0, silently = true)
                throw RuntimeException("fail")
            }
        }

        assertEquals(originalSize, paginator.cache.size)
        assertEquals(originalData, paginator.cache.getStateOf(1)!!.data)
    }

    @Test
    fun `addAllElements is rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val originalData = paginator.cache.getStateOf(1)!!.data.toList()

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                (this as MutablePaginator).addAllElements(
                    elements = listOf("extra1", "extra2"),
                    targetPage = 1,
                    index = 0,
                    silently = true,
                )
                throw RuntimeException("fail")
            }
        }

        assertEquals(originalData, paginator.cache.getStateOf(1)!!.data)
    }

    // =========================================================================
    // Navigation rollback
    // =========================================================================

    @Test
    fun `jump inside transaction is rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        val startBefore = paginator.cache.startContextPage
        val endBefore = paginator.cache.endContextPage
        val pagesBefore = paginator.cache.pages.toList()

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
                throw RuntimeException("fail")
            }
        }

        assertEquals(startBefore, paginator.cache.startContextPage)
        assertEquals(endBefore, paginator.cache.endContextPage)
        assertEquals(pagesBefore, paginator.cache.pages)
    }

    // =========================================================================
    // Nested transaction
    // =========================================================================

    @Test
    fun `nested transaction - outer fails rolls back to outer savepoint`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val originalElement = paginator.cache.getElement(1, 0)

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                // Inner succeeds
                (this as MutablePaginator).transaction {
                    setElement("inner_change", page = 1, index = 0, silently = true)
                }
                assertEquals("inner_change", cache.getElement(1, 0))

                // Outer fails
                throw RuntimeException("outer fail")
            }
        }

        assertEquals(originalElement, paginator.cache.getElement(1, 0))
    }

    @Test
    fun `nested transaction - inner fails, outer succeeds`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)

        paginator.transaction {
            (this as MutablePaginator).setElement("outer_change", page = 1, index = 0, silently = true)

            // Inner fails and rolls back
            assertFailsWith<RuntimeException> {
                transaction {
                    setElement("inner_change", page = 1, index = 0, silently = true)
                    throw RuntimeException("inner fail")
                }
            }

            // After inner rollback, outer_change should be restored
            assertEquals("outer_change", cache.getElement(1, 0))
        }

        // Outer succeeded, so outer_change persists
        assertEquals("outer_change", paginator.cache.getElement(1, 0))
    }

    // =========================================================================
    // Dirty pages rollback
    // =========================================================================

    @Test
    fun `dirty pages are rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertTrue(paginator.core.isDirtyPagesEmpty())

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                core.markDirty(1)
                core.markDirty(2)
                assertTrue(core.isDirty(1))
                throw RuntimeException("fail")
            }
        }

        assertTrue(paginator.core.isDirtyPagesEmpty())
    }

    @Test
    fun `pre-existing dirty pages are preserved after rollback`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        paginator.core.markDirty(3)

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                core.clearDirty(3)
                core.markDirty(1)
                throw RuntimeException("fail")
            }
        }

        assertTrue(paginator.core.isDirty(3))
        assertFalse(paginator.core.isDirty(1))
    }

    // =========================================================================
    // Bookmark rollback
    // =========================================================================

    @Test
    fun `bookmarks are rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        val bookmarksBefore = paginator.bookmarks.map { it.page }

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                bookmarks.clear()
                bookmarks.add(BookmarkInt(10))
                bookmarks.add(BookmarkInt(20))
                throw RuntimeException("fail")
            }
        }

        assertEquals(bookmarksBefore, paginator.bookmarks.map { it.page })
    }

    // =========================================================================
    // Lock flags rollback
    // =========================================================================

    @Test
    fun `lock flags are rolled back on failure`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)
        assertFalse(paginator.lockJump)
        assertFalse(paginator.lockGoNextPage)
        assertFalse(paginator.lockRefresh)

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                lockJump = true
                lockGoNextPage = true
                lockRefresh = true
                throw RuntimeException("fail")
            }
        }

        assertFalse(paginator.lockJump)
        assertFalse(paginator.lockGoNextPage)
        assertFalse(paginator.lockRefresh)
    }

    // =========================================================================
    // Empty paginator
    // =========================================================================

    @Test
    fun `transaction on empty paginator rolls back to empty`() = runTest {
        val paginator = createDeterministicPaginator(capacity = 3)
        assertEquals(0, paginator.cache.size)

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
                assertTrue(cache.size > 0)
                throw RuntimeException("fail")
            }
        }

        assertEquals(0, paginator.cache.size)
        assertEquals(0, paginator.cache.startContextPage)
        assertEquals(0, paginator.cache.endContextPage)
    }

    // =========================================================================
    // ErrorPage preservation
    // =========================================================================

    @Test
    fun `ErrorPage type is preserved after rollback`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 3, capacity = 3)

        // Manually set an ErrorPage in the cache
        val errorPage = PageState.ErrorPage<String>(
            exception = RuntimeException("load error"),
            page = 2,
            data = mutableListOf("cached_item"),
        )
        paginator.core.setState(state = errorPage, silently = true)
        assertTrue(paginator[2]!!.isErrorState())

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                (this as MutablePaginator).setElement("modified", page = 2, index = 0, silently = true)
                throw RuntimeException("fail")
            }
        }

        val restored = paginator[2]!!
        assertIs<PageState.ErrorPage<String>>(restored)
        assertEquals("cached_item", restored.data[0])
    }

    // =========================================================================
    // Context window rollback
    // =========================================================================

    @Test
    fun `context window is restored after rollback`() = runTest {
        val paginator = createPopulatedPaginator(pageCount = 5, capacity = 3)
        val startBefore = paginator.cache.startContextPage
        val endBefore = paginator.cache.endContextPage

        assertFailsWith<RuntimeException> {
            paginator.transaction {
                jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
                throw RuntimeException("fail")
            }
        }

        assertEquals(startBefore, paginator.cache.startContextPage)
        assertEquals(endBefore, paginator.cache.endContextPage)
    }
}
