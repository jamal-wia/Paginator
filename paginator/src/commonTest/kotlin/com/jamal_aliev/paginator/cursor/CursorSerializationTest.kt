package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.CursorPagingCore
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.extension.isSuccessState
import com.jamal_aliev.paginator.page.PageState.SuccessPage
import com.jamal_aliev.paginator.serialization.restoreStateFromJson
import com.jamal_aliev.paginator.serialization.saveStateToJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CursorSerializationTest {

    private fun populatedCore(): CursorPagingCore<String> {
        val core = CursorPagingCore<String>(initialCapacity = 3)
        val selves = listOf("p0", "p1", "p2", "p3")
        selves.forEachIndexed { idx, self ->
            val bookmark = CursorBookmark(
                prev = if (idx == 0) null else selves[idx - 1],
                self = self,
                next = if (idx == selves.lastIndex) null else selves[idx + 1],
            )
            val state = SuccessPage(
                page = idx + 1,
                data = MutableList(3) { "${self}_item$it" },
            )
            core.cache.setState(bookmark, state, silently = true)
        }
        core.startContextCursor = core.cache.getCursorOf("p1")
        core.endContextCursor = core.cache.getCursorOf("p2")
        return core
    }

    @Test
    fun core_save_restore_roundtrip() {
        val core = populatedCore()
        val json = core.saveStateToJson(String.serializer(), String.serializer())

        val restored = CursorPagingCore<String>(initialCapacity = 3)
        restored.restoreStateFromJson(json, String.serializer(), String.serializer())

        // Same number of pages.
        assertEquals(core.size, restored.size)
        // Ordering by next-links is preserved.
        assertEquals(
            listOf("p0", "p1", "p2", "p3"),
            restored.cursors.map { it.self },
        )
        // Context cursors round-tripped.
        assertEquals("p1", restored.startContextCursor?.self)
        assertEquals("p2", restored.endContextCursor?.self)
        // Each restored state is a SuccessPage with the original data.
        (0..3).forEach { idx ->
            val self = "p$idx"
            val originalData = core.cache.getStateOf(self)!!.data
            val restoredState = restored.cache.getStateOf(self)
            assertNotNull(restoredState)
            assertTrue(restoredState.isSuccessState())
            assertEquals(originalData, restoredState.data)
        }
    }

    @Test
    fun core_contextOnly_excludes_pages_outside_window() {
        val core = populatedCore()
        val json = core.saveStateToJson(
            elementSerializer = String.serializer(),
            keySerializer = String.serializer(),
            contextOnly = true,
        )

        val restored = CursorPagingCore<String>(initialCapacity = 3)
        restored.restoreStateFromJson(json, String.serializer(), String.serializer())
        // Context window was p1..p2, so only those two should survive.
        assertEquals(listOf("p1", "p2"), restored.cursors.map { it.self })
    }

    @Test
    fun paginator_save_restore_keeps_bookmarks_and_locks() = runTest {
        val backend = FakeCursorBackend()
        val paginator = cursorPaginatorOf(backend)
        paginator.restart(silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
        paginator.bookmarks.add(CursorBookmark(prev = null, self = "p3", next = null))
        paginator.recyclingBookmark = true
        paginator.lockRefresh = true

        val json = paginator.saveStateToJson(String.serializer(), String.serializer())

        val restored = cursorPaginatorOf(FakeCursorBackend())
        restored.restoreStateFromJson(json, String.serializer(), String.serializer())

        assertEquals(paginator.cache.size, restored.cache.size)
        assertEquals("p0", restored.core.startContextCursor?.self)
        assertEquals("p1", restored.core.endContextCursor?.self)
        assertEquals(1, restored.bookmarks.size)
        assertEquals("p3", restored.bookmarks.first().self)
        assertTrue(restored.recyclingBookmark)
        assertTrue(restored.lockRefresh)
    }
}
