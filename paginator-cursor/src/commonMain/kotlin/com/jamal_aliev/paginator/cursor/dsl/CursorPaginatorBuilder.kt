// The deprecated L2 persistent cache is still wired through here until it is removed.
@file:Suppress("DEPRECATION")

package com.jamal_aliev.paginator.cursor.dsl

import com.jamal_aliev.paginator.core.dsl.PaginatorDsl
import com.jamal_aliev.paginator.cursor.CursorPaginator
import com.jamal_aliev.paginator.cursor.CursorPagingCore
import com.jamal_aliev.paginator.cursor.CursorPagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.cursor.MutableCursorPaginator
import com.jamal_aliev.paginator.cursor.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cursor.cache.CursorInMemoryPagingCache
import com.jamal_aliev.paginator.cursor.cache.CursorPagingCache
import com.jamal_aliev.paginator.cursor.cache.persistent.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorBootstrapErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorBootstrapProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorErrorPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorProgressPage
import com.jamal_aliev.paginator.cursor.initializer.InitializerCursorSuccessPage
import com.jamal_aliev.paginator.cursor.load.CursorLoadResult
import com.jamal_aliev.paginator.core.logger.PaginatorLogger

// ──────────────────────────────────────────────────────────────────────────────
//  Entry points
// ──────────────────────────────────────────────────────────────────────────────

/**
 * DSL entry point for a **read-only** [CursorPaginator].
 *
 * ```kotlin
 * val messages: CursorPaginator<String, Message> = cursorPaginator(capacity = 20) {
 *     load { cursor ->
 *         val page = api.loadMessages(cursor?.self)
 *         CursorLoadResult(
 *             data = page.items,
 *             bookmark = CursorBookmark(page.prevCursor, page.selfCursor, page.nextCursor),
 *         )
 *     }
 * }
 * ```
 */
inline fun <K : Any, T> cursorPaginator(
    capacity: Int = DEFAULT_CAPACITY,
    block: CursorPaginatorBuilder<K, T>.() -> Unit,
): CursorPaginator<K, T> {
    return CursorPaginatorBuilder<K, T>(capacity).apply(block).build()
}

/**
 * DSL entry point for a [MutableCursorPaginator].
 */
inline fun <K : Any, T> mutableCursorPaginator(
    capacity: Int = DEFAULT_CAPACITY,
    block: MutableCursorPaginatorBuilder<K, T>.() -> Unit,
): MutableCursorPaginator<K, T> {
    return MutableCursorPaginatorBuilder<K, T>(capacity).apply(block).build()
}

// ──────────────────────────────────────────────────────────────────────────────
//  Base builder
// ──────────────────────────────────────────────────────────────────────────────

@PaginatorDsl
sealed class BaseCursorPaginatorBuilder<K : Any, T> protected constructor(
    @PublishedApi internal val capacity: Int,
) {

    var cache: CursorPagingCache<K, T> = CursorInMemoryPagingCache<K, T>()
    @Deprecated(
        message = "The L2 persistent cache is deprecated since 10.3.0 and will be removed in a future major release. " +
            "It is no longer supported together with the rest of the library (refresh, eviction, transactions, " +
            "reactive sources) and may cause non-obvious bugs. Keep your database as the single source of truth " +
            "and bridge it with observe() instead.",
    )
    var persistentCache: CursorPersistentPagingCache<K, T>? = null
    var logger: PaginatorLogger? = null
    var recyclingBookmark: Boolean = false
    var initialCursor: CursorBookmark<K>? = null

    @PublishedApi
    internal var loadFn: (suspend CursorPaginator<K, T>.(cursor: CursorBookmark<K>?) -> CursorLoadResult<K, T>)? =
        null

    @PublishedApi
    internal var bookmarks: List<CursorBookmark<K>>? = null

    @PublishedApi
    internal var initializersBuilder: CursorInitializersBuilder<K, T>? = null

    /**
     * Sets the suspending [load] function used to fetch a page given an optional
     * cursor hint. **Required.**
     */
    fun load(fn: suspend CursorPaginator<K, T>.(cursor: CursorBookmark<K>?) -> CursorLoadResult<K, T>) {
        loadFn = fn
    }

    /** Replaces the bookmark list with the given [list]. Pass nothing to clear. */
    fun bookmarks(list: List<CursorBookmark<K>>) {
        bookmarks = list.toList()
    }

    fun bookmarks(vararg cursors: CursorBookmark<K>) {
        bookmarks = cursors.toList()
    }

    fun initializers(block: CursorInitializersBuilder<K, T>.() -> Unit) {
        val builder =
            initializersBuilder ?: CursorInitializersBuilder<K, T>().also {
                initializersBuilder = it
            }
        builder.block()
    }

    @PublishedApi
    internal fun buildCore(): CursorPagingCore<K, T> {
        return CursorPagingCore(
            cache = cache,
            persistentCache = persistentCache,
            initialCapacity = capacity,
        )
    }

    @PublishedApi
    internal fun resolveLoad(): suspend CursorPaginator<K, T>.(CursorBookmark<K>?) -> CursorLoadResult<K, T> {
        return checkNotNull(loadFn) {
            "load { ... } block is required when building a cursor paginator"
        }
    }

    @PublishedApi
    internal fun applyCommon(paginator: CursorPaginator<K, T>, core: CursorPagingCore<K, T>) {
        paginator.recyclingBookmark = recyclingBookmark
        paginator.initialCursor = initialCursor
        logger?.let { paginator.logger = it }
        bookmarks?.let { configured ->
            paginator.bookmarks.clear()
            paginator.bookmarks.addAll(configured)
        }
        initializersBuilder?.applyTo(core)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Concrete builders
// ──────────────────────────────────────────────────────────────────────────────

class CursorPaginatorBuilder<K : Any, T> @PublishedApi internal constructor(
    capacity: Int,
) : BaseCursorPaginatorBuilder<K, T>(capacity) {

    @PublishedApi
    internal fun build(): CursorPaginator<K, T> {
        val load = resolveLoad()
        val core = buildCore()
        return CursorPaginator(core = core, load = load).also { applyCommon(it, core) }
    }
}

class MutableCursorPaginatorBuilder<K : Any, T> @PublishedApi internal constructor(
    capacity: Int,
) : BaseCursorPaginatorBuilder<K, T>(capacity) {

    @PublishedApi
    internal fun build(): MutableCursorPaginator<K, T> {
        val load = resolveLoad()
        val core = buildCore()
        return MutableCursorPaginator(core = core, load = load).also { applyCommon(it, core) }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Initializers sub-builder
// ──────────────────────────────────────────────────────────────────────────────

@PaginatorDsl
class CursorInitializersBuilder<K : Any, T> @PublishedApi internal constructor() {

    private var progress: InitializerCursorProgressPage<K, T>? = null
    private var success: InitializerCursorSuccessPage<K, T>? = null
    private var error: InitializerCursorErrorPage<K, T>? = null
    private var bootstrapProgress: InitializerCursorBootstrapProgressPage<T>? = null
    private var bootstrapError: InitializerCursorBootstrapErrorPage<T>? = null

    fun progress(factory: InitializerCursorProgressPage<K, T>) {
        progress = factory
    }

    fun success(factory: InitializerCursorSuccessPage<K, T>) {
        success = factory
    }

    fun error(factory: InitializerCursorErrorPage<K, T>) {
        error = factory
    }

    /**
     * Progress state of the anchorless first load (`restart()` without `initialCursor`), which has
     * no bookmark yet — e.g. a skeleton. See
     * [CursorPagingCore.bootstrapState][com.jamal_aliev.paginator.cursor.CursorPagingCore.bootstrapState].
     */
    fun bootstrapProgress(factory: InitializerCursorBootstrapProgressPage<T>) {
        bootstrapProgress = factory
    }

    /** Error state of a failed anchorless first load. */
    fun bootstrapError(factory: InitializerCursorBootstrapErrorPage<T>) {
        bootstrapError = factory
    }

    internal fun applyTo(core: CursorPagingCore<K, T>) {
        progress?.let { core.initializerProgressPage = it }
        success?.let { core.initializerSuccessPage = it }
        error?.let { core.initializerErrorPage = it }
        bootstrapProgress?.let { core.initializerBootstrapProgressPage = it }
        bootstrapError?.let { core.initializerBootstrapErrorPage = it }
    }
}
