package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.core.extension.isErrorState
import com.jamal_aliev.paginator.core.extension.isProgressState
import com.jamal_aliev.paginator.core.page.PageState
import com.jamal_aliev.paginator.core.page.PaginatorUiState
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.dsl.cursorPaginator
import com.jamal_aliev.paginator.cursor.exception.CursorBootstrapFailedException
import com.jamal_aliev.paginator.cursor.exception.CursorLoadGuardedException
import com.jamal_aliev.paginator.cursor.extension.refreshAll
import com.jamal_aliev.paginator.cursor.extension.uiState
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.cursor.page.CursorBootstrapPageState
import com.jamal_aliev.paginator.cursor.page.CursorPageState
import com.jamal_aliev.paginator.cursor.serialization.saveStateToJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The anchorless first load (`restart()` without `initialCursor`, or the implicit bootstrap of
 * `goNextPage()`) has no cursor key until the source answers. It must never put a foreign value
 * where a `K` is expected: its loading / error state lives in `core.bootstrapState` and the
 * key-addressed cache stays empty until the first real page arrives.
 */
class CursorBootstrapTest {

    /** Records every hint passed to `load`; fails while [failing] is set, hangs on [gate]. */
    private class RecordingSource {
        val backend = FakeCursorBackend()
        val hints = mutableListOf<String?>()
        var failing: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        val load: suspend CursorPaginator<String, String>.(CursorBookmark<String>?) -> CursorLoadResult<String, String> =
            { cursor ->
                hints += cursor?.self
                gate?.await()
                failing?.let { throw it }
                backend.loadResult(cursor)
            }
    }

    private fun paginatorOf(source: RecordingSource): MutableCursorPaginator<String, String> =
        MutableCursorPaginator(core = CursorPagingCore(initialCapacity = 3), load = source.load)

    @Test
    fun uiState_is_loading_during_unanchored_restart_then_content() = runTest {
        val source = RecordingSource().apply { gate = CompletableDeferred() }
        val paginator = paginatorOf(source)
        val states = mutableListOf<PaginatorUiState<String>>()
        backgroundScope.launch { paginator.uiState.collect { states += it } }
        runCurrent()

        val restart = launch { paginator.restart() }
        runCurrent()
        assertIs<PaginatorUiState.Loading<String>>(states.last())
        assertTrue(paginator.core.isBootstrapping)
        assertTrue(paginator.core.cursors.isEmpty())

        source.gate!!.complete(Unit)
        restart.join()
        runCurrent()
        assertIs<PaginatorUiState.Content<String>>(states.last())
        assertNull(paginator.core.bootstrapState.value)
        assertEquals(listOf("p0"), paginator.core.cursors.map { it.self })
    }

    @Test
    fun retry_after_failed_bootstrap_calls_load_with_null() = runTest {
        val source = RecordingSource().apply { failing = IllegalStateException("offline") }
        val paginator = paginatorOf(source)

        paginator.restart()
        assertFailsWith<CursorBootstrapFailedException> { paginator.goNextPage() }
        source.failing = null
        paginator.restart()

        assertEquals(listOf<String?>(null, null, null), source.hints)
        assertEquals(listOf("p0"), paginator.core.cursors.map { it.self })
        assertNull(paginator.core.bootstrapState.value)
    }

    @Test
    fun refreshAll_after_failed_bootstrap_does_not_call_load() = runTest {
        val source = RecordingSource().apply { failing = IllegalStateException("offline") }
        val paginator = paginatorOf(source)
        paginator.restart()
        source.hints.clear()

        paginator.refreshAll()

        assertEquals(emptyList<String?>(), source.hints)
    }

    @Test
    fun goNextPage_implicit_bootstrap_failure_throws_and_uiState_is_error() = runTest {
        val source = RecordingSource().apply { failing = IllegalStateException("offline") }
        val paginator = paginatorOf(source)
        val states = mutableListOf<PaginatorUiState<String>>()
        backgroundScope.launch { paginator.uiState.collect { states += it } }

        val failure = assertFailsWith<CursorBootstrapFailedException> { paginator.goNextPage() }
        runCurrent()

        assertEquals("offline", failure.cause?.message)
        assertIs<PaginatorUiState.Error<String>>(states.last())
        assertFalse(paginator.core.isStarted)
    }

    @Test
    fun goNextPage_during_bootstrap_does_not_issue_a_second_first_page_load() = runTest {
        val source = RecordingSource().apply { gate = CompletableDeferred() }
        val paginator = paginatorOf(source)

        val restart = launch { paginator.restart() }
        runCurrent()
        val next = launch { paginator.goNextPage() }
        runCurrent()
        source.gate!!.complete(Unit)
        restart.join()
        next.join()

        assertEquals(listOf<String?>(null, "p1"), source.hints)
        assertEquals(listOf("p0", "p1"), paginator.core.cursors.map { it.self })
    }

    @Serializable
    private data class Token(val v: String)

    @Test
    fun a_typed_key_never_sees_a_foreign_value() = runTest {
        var failing = true
        val gate = CompletableDeferred<Unit>()
        val paginator = CursorPaginator<Token, String>(core = CursorPagingCore(initialCapacity = 3)) { _ ->
            gate.await()
            if (failing) throw IllegalStateException("offline")
            CursorLoadResult(
                data = listOf("a", "b", "c"),
                bookmark = CursorBookmark(prev = null, self = Token("t0"), next = Token("t1")),
            )
        }
        // The keyed initializers must not be invoked without a key: every bookmark they see is real.
        val seenKeys = mutableListOf<String>()
        paginator.core.initializerProgressPage = { bookmark, data, metadata ->
            seenKeys += bookmark.self.v
            CursorPageState.Progress(bookmark, data, metadata)
        }
        paginator.core.initializerErrorPage = { e, bookmark, data, metadata ->
            seenKeys += bookmark.self.v
            CursorPageState.Error(e, bookmark, data, metadata)
        }
        val keysInSnapshots = mutableListOf<String>()
        backgroundScope.launch {
            paginator.core.snapshot.collect { pages -> pages.forEach { keysInSnapshots += it.bookmark.self.v } }
        }

        val first = launch { paginator.restart() }
        runCurrent()
        assertNull(paginator.core.tailCursor())
        gate.complete(Unit)
        first.join()
        assertTrue(paginator.core.bootstrapState.value.isErrorState())

        failing = false
        paginator.restart()
        runCurrent()

        assertEquals(listOf("t0"), keysInSnapshots.distinct())
        assertEquals(emptyList(), seenKeys)
        assertEquals("t0", paginator.core.tailCursor()!!.self.v)
    }

    @Test
    fun saveStateToJson_during_bootstrap_is_empty_and_does_not_throw() = runTest {
        val source = RecordingSource().apply { failing = IllegalStateException("offline") }
        val paginator = paginatorOf(source)
        paginator.restart()

        val json = paginator.saveStateToJson(
            elementSerializer = String.serializer(),
            keySerializer = String.serializer(),
        )

        assertTrue("\"entries\":[]" in json, json)
    }

    @Test
    fun restart_guard_rejection_without_anchor_reports_isBootstrap() = runTest {
        val paginator = paginatorOf(RecordingSource())

        val rejection = assertFailsWith<CursorLoadGuardedException> {
            paginator.restart(loadGuard = { _, _ -> false })
        }

        assertTrue(rejection.isBootstrap)
    }

    @Test
    fun release_and_jump_clear_the_bootstrap_state() = runTest {
        val source = RecordingSource().apply { failing = IllegalStateException("offline") }
        val paginator = paginatorOf(source)

        paginator.restart()
        assertTrue(paginator.core.bootstrapState.value.isErrorState())
        paginator.release()
        assertNull(paginator.core.bootstrapState.value)

        paginator.restart()
        assertTrue(paginator.core.bootstrapState.value.isErrorState())
        source.failing = null
        paginator.jump(CursorBookmark(prev = null, self = "p2", next = null))
        assertNull(paginator.core.bootstrapState.value)
        assertEquals(listOf("p2"), paginator.core.cursors.map { it.self })
    }

    @Test
    fun cancelling_an_unanchored_restart_leaves_the_paginator_not_started() = runTest {
        val source = RecordingSource().apply { gate = CompletableDeferred() }
        val paginator = paginatorOf(source)

        val restart = launch { paginator.restart() }
        runCurrent()
        assertTrue(paginator.core.isBootstrapping)
        restart.cancel()
        runCurrent()

        assertNull(paginator.core.bootstrapState.value)
        assertFalse(paginator.core.isStarted)
        assertTrue(paginator.core.cursors.isEmpty())
    }

    @Test
    fun custom_bootstrap_initializers_are_used() = runTest {
        class Skeleton : CursorBootstrapPageState.Progress<String>(data = listOf("skeleton"))

        val gate = CompletableDeferred<Unit>()
        val paginator = cursorPaginator<String, String>(capacity = 3) {
            load { _ ->
                gate.await()
                throw IllegalStateException("offline")
            }
            initializers {
                bootstrapProgress { Skeleton() }
                bootstrapError { e -> CursorBootstrapPageState.Error(e, data = listOf("cached")) }
            }
        }

        val restart = launch { paginator.restart() }
        runCurrent()
        assertIs<Skeleton>(paginator.core.bootstrapState.value)
        assertTrue(paginator.core.bootstrapState.value.isProgressState())

        gate.complete(Unit)
        restart.join()
        val error = paginator.core.bootstrapState.value
        assertIs<PageState.ErrorState<String>>(error)
        assertEquals(listOf("cached"), error.data)
    }
}
