package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.page.PageState.EmptyPage
import com.jamal_aliev.paginator.page.PageState.ErrorPage
import com.jamal_aliev.paginator.page.PageState.ProgressPage
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.page.PlaceholderPageState.PlaceholderProgressPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaceholderPageStateIntegrationTest {

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** A simple skeleton marker type used across tests. */
    data object Skeleton

    private fun skeletons(n: Int) = List(n) { Skeleton }

    private fun createPaginator(
        capacity: Int = 3,
        skeletonCount: Int = capacity,
        load: suspend Paginator<String>.(Int) -> LoadResult<String> = { page ->
            LoadResult(List(core.capacity) { "p${page}_item$it" })
        },
    ): MutablePaginator<String> {
        val paginator = MutablePaginator(load = load)
        paginator.core.resize(capacity = capacity, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data, _ ->
            PlaceholderProgressPage(
                page = page,
                data = data,
                placeholders = skeletons(skeletonCount),
            )
        }
        return paginator
    }

    // ══════════════════════════════════════════════════════════════════════
    //  jump
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `jump - progress state is PlaceholderProgressPage`() = runTest {
        var capturedProgress: ProgressPage<String>? = null
        val paginator =
            MutablePaginator { page -> LoadResult(List(core.capacity) { "p${page}_item$it" }) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data, _ ->
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
                .also { capturedProgress = it }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertIs<PlaceholderProgressPage<String, Skeleton>>(capturedProgress)
    }

    @Test
    fun `jump - PlaceholderProgressPage carries correct placeholders`() = runTest {
        var capturedPlaceholders: List<Skeleton>? = null
        val paginator = createPaginator(capacity = 3, skeletonCount = 5)
        paginator.core.initializerProgressPage = { page, data, _ ->
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(5))
                .also {
                    capturedPlaceholders = it.placeholders
                }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertNotNull(capturedPlaceholders)
        assertEquals(5, capturedPlaceholders.size)
        assertTrue(capturedPlaceholders.all { it === Skeleton })
    }

    @Test
    fun `jump - placeholders are separate from data`() = runTest {
        var capturedState: PlaceholderProgressPage<String, Skeleton>? = null
        val paginator = createPaginator(capacity = 3, skeletonCount = 3)
        paginator.core.initializerProgressPage = { page, data, _ ->
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
                .also {
                    capturedState = it
                }
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertNotNull(capturedState)
        // data contains only real items (empty on first load), placeholders are separate
        assertTrue(capturedState.data.isEmpty())
        assertEquals(3, capturedState.placeholders.size)
    }

    @Test
    fun `jump - result is SuccessPage after loading`() = runTest {
        val paginator = createPaginator(capacity = 3)
        val (_, result) = paginator.jump(
            BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true
        )
        assertIs<SuccessPage<String>>(result)
        assertEquals(3, result.data.size)
    }

    @Test
    fun `jump - page number in progress state matches target`() = runTest {
        var capturedPage = -1
        val paginator = createPaginator(capacity = 3)
        paginator.core.initializerProgressPage = { page, data, _ ->
            capturedPage = page
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.jump(BookmarkInt(7), silentlyLoading = true, silentlyResult = true)
        assertEquals(7, capturedPage)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  goNextPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `goNextPage - creates PlaceholderProgressPage`() = runTest {
        var progressCreated = false
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = { page, data, _ ->
            progressCreated = true
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(progressCreated)
    }

    @Test
    fun `goNextPage - data in progress carries previous cached page data`() = runTest {
        var capturedProgressData: List<String>? = null
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        // page 2 has no cache yet — data should be empty
        paginator.core.initializerProgressPage = { page, data, _ ->
            capturedProgressData = data
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertNotNull(capturedProgressData)
        assertTrue(capturedProgressData.isEmpty())
    }

    @Test
    fun `goNextPage - result is SuccessPage`() = runTest {
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        val result = paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        assertIs<SuccessPage<String>>(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  goPreviousPage
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `goPreviousPage - creates PlaceholderProgressPage`() = runTest {
        var progressCreated = false
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = { page, data, _ ->
            progressCreated = true
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)
        assertTrue(progressCreated)
    }

    @Test
    fun `goPreviousPage - passes cached data to PlaceholderProgressPage`() = runTest {
        var capturedProgressData: List<String>? = null

        // Source returns partial results (< capacity) for page 2 so it needs a reload on next nav
        val paginator = MutablePaginator<String> { page ->
            LoadResult(
                if (page == 2) listOf("p2_item0") // incomplete — triggers reload
                else List(core.capacity) { "p${page}_item$it" }
            )
        }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.jump(BookmarkInt(2), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(
            silentlyLoading = true,
            silentlyResult = true
        ) // reloads page 2 (incomplete)

        // After reload context is at page 2-3. goPreviousPage goes to page 1 (no cache → empty data)
        paginator.core.initializerProgressPage = { page, data, _ ->
            capturedProgressData = data
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.jump(BookmarkInt(4), silentlyLoading = true, silentlyResult = true)
        paginator.goPreviousPage(silentlyLoading = true, silentlyResult = true)

        // Page 3 has no prior cache, data passed to progress is empty
        assertNotNull(capturedProgressData)
        assertTrue(capturedProgressData.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  restart
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `restart - creates PlaceholderProgressPage`() = runTest {
        var progressCreated = false
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = { page, data, _ ->
            progressCreated = true
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.restart(silentlyLoading = true, silentlyResult = true)
        assertTrue(progressCreated)
    }

    @Test
    fun `restart - page 1 is SuccessPage after restart`() = runTest {
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.restart(silentlyLoading = true, silentlyResult = true)

        assertIs<SuccessPage<String>>(paginator.core.getStateOf(1))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  refresh
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `refresh - creates PlaceholderProgressPage for each refreshed page`() = runTest {
        var progressCount = 0
        val paginator = createPaginator(capacity = 3)
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        paginator.core.initializerProgressPage = { page, data, _ ->
            progressCount++
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.refresh(pages = listOf(1, 2), loadingSilently = true, finalSilently = true)

        assertTrue(progressCount >= 2, "Expected at least 2 progress states, got $progressCount")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  per-call override vs core default
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `per-call initProgressState overrides core default`() = runTest {
        var defaultUsed = false
        var overrideUsed = false

        val paginator =
            MutablePaginator { page -> LoadResult(List(core.capacity) { "p${page}_item$it" }) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data, _ ->
            defaultUsed = true
            ProgressPage(page = page, data = data)
        }

        paginator.jump(
            bookmark = BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true,
            initProgressState = { page, data, _ ->
                overrideUsed = true
                PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
            },
        )

        assertTrue(overrideUsed)
        assertTrue(!defaultUsed)
    }

    @Test
    fun `core default initializer is used when not overridden per-call`() = runTest {
        var defaultUsed = false

        val paginator =
            MutablePaginator { page -> LoadResult(List(core.capacity) { "p${page}_item$it" }) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data, _ ->
            defaultUsed = true
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        assertTrue(defaultUsed)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  coerceToCapacity interaction
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `coerceToCapacity trims PlaceholderProgressPage data when it exceeds capacity`() = runTest {
        val paginator =
            MutablePaginator { page -> LoadResult(List(core.capacity) { "p${page}_item$it" }) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)

        val bigData = List(10) { "item_$it" }
        val state = PlaceholderProgressPage(page = 1, data = bigData, placeholders = skeletons(3))
        val coerced = paginator.core.coerceToCapacity(state)

        assertEquals(3, coerced.data.size)
    }

    @Test
    fun `coerceToCapacity does not trim PlaceholderProgressPage when within capacity`() = runTest {
        val paginator = createPaginator(capacity = 5)

        val state = PlaceholderProgressPage(
            page = 1,
            data = List(3) { "item_$it" },
            placeholders = skeletons(3)
        )
        val coerced = paginator.core.coerceToCapacity(state)

        assertSame(state, coerced)
    }

    @Test
    fun `coerceToCapacity never trims with UNLIMITED_CAPACITY`() = runTest {
        val paginator = MutablePaginator<String> { LoadResult(emptyList()) }
        paginator.core.resize(
            capacity = PagingCore.UNLIMITED_CAPACITY,
            resize = false,
            silently = true
        )

        val state = PlaceholderProgressPage(
            page = 1,
            data = List(100) { "item_$it" },
            placeholders = skeletons(50)
        )
        val coerced = paginator.core.coerceToCapacity(state)

        assertSame(state, coerced)
        assertEquals(100, coerced.data.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Error and empty source scenarios
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `source error after PlaceholderProgressPage results in ErrorPage`() = runTest {
        var progressCreated = false
        val paginator = MutablePaginator<String> { _ -> throw RuntimeException("network error") }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data, _ ->
            progressCreated = true
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        val (_, result) = paginator.jump(
            BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true
        )
        assertTrue(progressCreated)
        assertIs<ErrorPage<String>>(result)
    }

    @Test
    fun `empty source after PlaceholderProgressPage results in EmptyPage`() = runTest {
        var progressCreated = false
        val paginator = MutablePaginator<String> { _ -> LoadResult(emptyList()) }
        paginator.core.resize(capacity = 3, resize = false, silently = true)
        paginator.core.initializerProgressPage = { page, data, _ ->
            progressCreated = true
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        val (_, result) = paginator.jump(
            BookmarkInt(1),
            silentlyLoading = true,
            silentlyResult = true
        )
        assertTrue(progressCreated)
        assertIs<EmptyPage<String>>(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Multiple sequential navigations
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `PlaceholderProgressPage created for each navigation step in sequence`() = runTest {
        val progressPages = mutableListOf<Int>()
        val paginator = createPaginator(capacity = 3)
        paginator.core.initializerProgressPage = { page, data, _ ->
            progressPages.add(page)
            PlaceholderProgressPage(page = page, data = data, placeholders = skeletons(3))
        }

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertEquals(listOf(1, 2, 3), progressPages)
    }
}
