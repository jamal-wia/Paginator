package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.page.PlaceholderProgressPage
import com.jamal_aliev.paginator.page.PlaceholderProgressPage.Placeholder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaceholderProgressPageIntegrationTest {

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun createPaginator(
        capacity: Int = 5,
        placeholderCapacity: Int = capacity,
        source: suspend Paginator<Any>.(Int) -> List<Any> = { page ->
            List(core.capacity) { "p${page}_item$it" }
        },
    ): MutablePaginator<Any> {
        val paginator = MutablePaginator(source = source)
        paginator.core.resize(capacity = capacity, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data ->
            PlaceholderProgressPage(
                page = page,
                data = data.toMutableList(),
                placeholderCapacity = placeholderCapacity,
            )
        }
        return paginator
    }

    private fun placeholderInitializer(
        placeholderCapacity: Int,
        onCreated: ((PlaceholderProgressPage<Any>) -> Unit)? = null,
    ): (Int, List<Any>) -> ProgressPage<Any> = { page, data ->
        PlaceholderProgressPage(
            page = page,
            data = data.toMutableList(),
            placeholderCapacity = placeholderCapacity,
        ).also { onCreated?.invoke(it) }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  jump
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `jump - progress state is PlaceholderProgressPage`() = runTest {
        var capturedProgress: ProgressPage<Any>? = null

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            capturedProgress = it
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertIs<PlaceholderProgressPage<Any>>(capturedProgress)
    }

    @Test
    fun `jump - result is SuccessPage after loading`() = runTest {
        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        val (_, result) = paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertIs<SuccessPage<Any>>(result)
        assertEquals(3, result.data.size)
    }

    @Test
    fun `jump - PlaceholderProgressPage contains correct page number`() = runTest {
        var capturedPage = -1

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            capturedPage = it.page
        }

        paginator.jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
        assertEquals(5, capturedPage)
    }

    @Test
    fun `jump - PlaceholderProgressPage data contains placeholders`() = runTest {
        var capturedData: List<Any>? = null

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 4)
        paginator.core.initializerProgressPage = placeholderInitializer(4) {
            capturedData = it.data.toList()
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        // No previous data for first jump, so data = 4 placeholders
        assertEquals(4, capturedData!!.size)
        assertTrue(capturedData.all { it is Placeholder })
    }

    // ══════════════════════════════════════════════════════════════════════
    //  goNextPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `goNextPage - uses PlaceholderProgressPage`() = runTest {
        var progressCreated = false

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            progressCreated = true
        }

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(progressCreated)
    }

    @Test
    fun `goNextPage - PlaceholderProgressPage has no prior data for new page`() = runTest {
        var capturedData: List<Any>? = null

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 2)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = placeholderInitializer(2) {
            capturedData = it.data.toList()
        }

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        // Page 2 has no cached data, so only placeholders
        assertEquals(2, capturedData!!.count { it is Placeholder })
    }

    @Test
    fun `goNextPage - result is SuccessPage`() = runTest {
        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val result = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertIs<SuccessPage<Any>>(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  goPreviousPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `goPreviousPage - uses PlaceholderProgressPage`() = runTest {
        var progressForPreviousPage = false

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            progressForPreviousPage = true
        }

        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(progressForPreviousPage)
    }

    @Test
    fun `goPreviousPage - PlaceholderProgressPage carries cached data from previous page`() = runTest {
        var capturedData: List<Any>? = null

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 2)
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = placeholderInitializer(2) {
            capturedData = it.data.toList()
        }

        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)

        // The progress state should contain placeholders
        val placeholders = capturedData!!.count { it is Placeholder }
        assertTrue(placeholders > 0, "Expected placeholders in progress data")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  restart
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `restart - uses PlaceholderProgressPage`() = runTest {
        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        var restartProgressCreated = false
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            restartProgressCreated = true
        }

        paginator.restart(silentlyLoading = true, silentlyResult = true)
        assertTrue(restartProgressCreated)
    }

    @Test
    fun `restart - page 1 is SuccessPage after restart`() = runTest {
        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.restart(silentlyLoading = true, silentlyResult = true)

        val state = paginator.core.getStateOf(1)
        assertIs<SuccessPage<Any>>(state)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  refresh
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `refresh - uses PlaceholderProgressPage for all reloaded pages`() = runTest {
        var progressCount = 0

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        progressCount = 0
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            progressCount++
        }

        paginator.refresh(
            pages = listOf(1, 2),
            loadingSilently = true,
            finalSilently = true,
        )

        assertTrue(progressCount >= 2, "Expected at least 2 progress pages, got $progressCount")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  per-call vs core default initProgressState
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `per-call initProgressState overrides core default`() = runTest {
        var defaultUsed = false
        var overrideUsed = false

        val paginator = MutablePaginator<Any> { page ->
            List(core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data ->
            defaultUsed = true
            ProgressPage(page = page, data = data)
        }

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
            initProgressState = { page, data ->
                overrideUsed = true
                PlaceholderProgressPage(
                    page = page,
                    data = data.toMutableList(),
                    placeholderCapacity = 3,
                )
            },
        )

        assertTrue(overrideUsed)
        assertTrue(!defaultUsed)
    }

    @Test
    fun `core default initializerProgressPage is used when not overridden`() = runTest {
        var defaultUsed = false

        val paginator = MutablePaginator<Any> { page ->
            List(core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data ->
            defaultUsed = true
            PlaceholderProgressPage(
                page = page,
                data = data.toMutableList(),
                placeholderCapacity = 3,
            )
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertTrue(defaultUsed)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  coerceToCapacity interaction
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `coerceToCapacity trims PlaceholderProgressPage when placeholders exceed capacity`() = runTest {
        val paginator = MutablePaginator<Any> { page ->
            List(core.capacity) { "p${page}_item$it" }
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        // After initProgressState creates a PlaceholderProgressPage with 10 placeholders,
        // coerceToCapacity trims the state's data to capacity (3).
        // We verify by checking what the cache receives.
        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
            initProgressState = { page, data ->
                PlaceholderProgressPage(
                    page = page,
                    data = data.toMutableList(),
                    placeholderCapacity = 10,
                )
            },
        )

        // After jump completes, the cached state for page 1 is SuccessPage.
        // But we can verify coerceToCapacity works by checking a captured intermediate.
        // Let's directly test via the paginator's coerceToCapacity method:
        val bigPlaceholder = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 10,
        )
        val coerced = paginator.coerceToCapacity(bigPlaceholder)
        assertEquals(3, coerced.data.size)
    }

    @Test
    fun `coerceToCapacity preserves PlaceholderProgressPage when within capacity`() = runTest {
        val paginator = createPaginator(capacity = 5, placeholderCapacity = 3)

        val placeholder = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 3,
        )
        val coerced = paginator.coerceToCapacity(placeholder)
        assertEquals(3, coerced.data.size)
        // Same instance — not trimmed
        assertSame(coerced, placeholder)
    }

    @Test
    fun `unlimited capacity does not trim PlaceholderProgressPage`() = runTest {
        val paginator = MutablePaginator<Any> { emptyList() }
        paginator.core.resize(
            capacity = PagingCore.UNLIMITED_CAPACITY,
            resize = false,
            silently = true,
        )

        val placeholder = PlaceholderProgressPage<Any>(
            page = 1,
            data = mutableListOf(),
            placeholderCapacity = 100,
        )
        val coerced = paginator.coerceToCapacity(placeholder)
        assertEquals(100, coerced.data.size)
        assertSame(coerced, placeholder)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Error scenario
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `source error after PlaceholderProgressPage results in ErrorPage`() = runTest {
        var progressCreated = false

        val paginator = MutablePaginator<Any> { _ ->
            throw RuntimeException("network error")
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            progressCreated = true
        }

        val (_, result) = paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertTrue(progressCreated)
        assertIs<ErrorPage<Any>>(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Empty source
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `empty source after PlaceholderProgressPage results in EmptyPage`() = runTest {
        var progressCreated = false

        val paginator = MutablePaginator<Any> { _ -> emptyList() }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            progressCreated = true
        }

        val (_, result) = paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
        )
        assertTrue(progressCreated)
        assertIs<EmptyPage<Any>>(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Multiple sequential navigations
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `PlaceholderProgressPage created for each navigation step`() = runTest {
        val progressPages = mutableListOf<Int>()

        val paginator = createPaginator(capacity = 3, placeholderCapacity = 3)
        paginator.core.initializerProgressPage = placeholderInitializer(3) {
            progressPages.add(it.page)
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertEquals(listOf(1, 2, 3), progressPages)
    }
}
