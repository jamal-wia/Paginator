package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.logger.CompositePaginatorLogger
import com.jamal_aliev.paginator.logger.LogComponent
import com.jamal_aliev.paginator.logger.LogLevel
import com.jamal_aliev.paginator.logger.PaginatorLogger
import com.jamal_aliev.paginator.logger.PrintPaginatorLogger
import com.jamal_aliev.paginator.logger.plus
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaginatorLoggerTest {

    @AfterTest
    fun cleanup() {
        PaginatorLogger.global = null
    }

    // ── LogLevel ordering ────────────────────────────────────────────────

    @Test
    fun `LogLevel values are ordered by severity`() {
        assertTrue(LogLevel.DEBUG < LogLevel.INFO)
        assertTrue(LogLevel.INFO < LogLevel.WARN)
        assertTrue(LogLevel.WARN < LogLevel.ERROR)
    }

    // ── isEnabled / minLevel ─────────────────────────────────────────────

    @Test
    fun `default isEnabled accepts all levels`() {
        val logger = RecordingLogger()
        for (level in LogLevel.entries) {
            for (component in LogComponent.entries) {
                assertTrue(logger.isEnabled(level, component))
            }
        }
    }

    @Test
    fun `minLevel filters lower levels`() {
        val logger = RecordingLogger(minLevel = LogLevel.WARN)
        assertFalse(logger.isEnabled(LogLevel.DEBUG, LogComponent.NAVIGATION))
        assertFalse(logger.isEnabled(LogLevel.INFO, LogComponent.NAVIGATION))
        assertTrue(logger.isEnabled(LogLevel.WARN, LogComponent.NAVIGATION))
        assertTrue(logger.isEnabled(LogLevel.ERROR, LogComponent.NAVIGATION))
    }

    // ── PrintPaginatorLogger component filter ────────────────────────────

    @Test
    fun `PrintPaginatorLogger filters by component`() {
        val logger = PrintPaginatorLogger(
            enabledComponents = setOf(LogComponent.CACHE)
        )
        assertTrue(logger.isEnabled(LogLevel.DEBUG, LogComponent.CACHE))
        assertFalse(logger.isEnabled(LogLevel.DEBUG, LogComponent.NAVIGATION))
        assertFalse(logger.isEnabled(LogLevel.DEBUG, LogComponent.MUTATION))
        assertFalse(logger.isEnabled(LogLevel.DEBUG, LogComponent.LIFECYCLE))
    }

    // ── CompositePaginatorLogger ─────────────────────────────────────────

    @Test
    fun `composite fans out to all delegates`() {
        val a = RecordingLogger()
        val b = RecordingLogger()
        val composite = a + b

        composite.log(LogLevel.INFO, LogComponent.CACHE, "test")

        assertEquals(1, a.entries.size)
        assertEquals(1, b.entries.size)
        assertEquals("test", a.entries[0].message)
    }

    @Test
    fun `composite skips disabled delegate`() {
        val debug = RecordingLogger(minLevel = LogLevel.DEBUG)
        val warn = RecordingLogger(minLevel = LogLevel.WARN)
        val composite = debug + warn

        composite.log(LogLevel.DEBUG, LogComponent.NAVIGATION, "low")

        assertEquals(1, debug.entries.size)
        assertEquals(0, warn.entries.size)
    }

    @Test
    fun `composite isEnabled returns true if any delegate is enabled`() {
        val strict = RecordingLogger(minLevel = LogLevel.ERROR)
        val permissive = RecordingLogger(minLevel = LogLevel.DEBUG)
        val composite = strict + permissive

        assertTrue(composite.isEnabled(LogLevel.DEBUG, LogComponent.NAVIGATION))
    }

    @Test
    fun `plus flattens nested composites`() {
        val a = RecordingLogger()
        val b = RecordingLogger()
        val c = RecordingLogger()
        val composite = (a + b) + c

        assertIs<CompositePaginatorLogger>(composite)
        assertEquals(3, composite.loggers.size)
    }

    // ── Global logger ────────────────────────────────────────────────────

    @Test
    fun `global logger receives events when instance logger is null`() = runTest {
        val global = RecordingLogger()
        PaginatorLogger.global = global

        val paginator = createDeterministicPaginator()
        // logger is null by default — global should be used
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertTrue(global.entries.isNotEmpty())
        assertTrue(global.entries.any { it.component == LogComponent.NAVIGATION })
    }

    @Test
    fun `instance logger takes priority over global`() = runTest {
        val global = RecordingLogger()
        val instance = RecordingLogger()
        PaginatorLogger.global = global

        val paginator = createDeterministicPaginator()
        paginator.logger = instance

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)

        assertTrue(instance.entries.isNotEmpty())
        assertEquals(0, global.entries.size)
    }

    @Test
    fun `no crash when both global and instance logger are null`() = runTest {
        PaginatorLogger.global = null
        val paginator = createDeterministicPaginator()
        paginator.logger = null
        // Should complete without errors
        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.release()
    }

    // ── Integration: correct levels and components ───────────────────────

    @Test
    fun `navigation logs use NAVIGATION component`() = runTest {
        val logger = RecordingLogger()
        val paginator = createDeterministicPaginator()
        paginator.logger = logger

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)

        assertTrue(logger.entries.all { it.component == LogComponent.NAVIGATION })
    }

    @Test
    fun `release logs use LIFECYCLE component with INFO level`() = runTest {
        val logger = RecordingLogger()
        val paginator = createDeterministicPaginator()
        paginator.logger = logger

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        logger.entries.clear()
        paginator.release()

        val releaseEntry = logger.entries.first { it.message == "release" }
        assertEquals(LogLevel.INFO, releaseEntry.level)
        assertEquals(LogComponent.LIFECYCLE, releaseEntry.component)
    }

    @Test
    fun `mutation logs use MUTATION component`() = runTest {
        val logger = RecordingLogger()
        val paginator = createDeterministicPaginator()
        paginator.logger = logger

        paginator.jump(BookmarkInt(1), silentlyLoading = true, silentlyResult = true)
        logger.entries.clear()
        paginator.setElement("new", page = 1, index = 0)

        assertTrue(logger.entries.any { it.component == LogComponent.MUTATION })
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private class RecordingLogger(
        override val minLevel: LogLevel = LogLevel.DEBUG
    ) : PaginatorLogger {

        data class Entry(
            val level: LogLevel,
            val component: LogComponent,
            val message: String
        )

        val entries = mutableListOf<Entry>()

        override fun log(level: LogLevel, component: LogComponent, message: String) {
            entries.add(Entry(level, component, message))
        }
    }
}
