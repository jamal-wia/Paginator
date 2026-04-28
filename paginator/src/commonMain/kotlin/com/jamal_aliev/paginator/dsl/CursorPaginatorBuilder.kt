package com.jamal_aliev.paginator.dsl

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.CursorPagingCore
import com.jamal_aliev.paginator.CursorPagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.MutableCursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.cache.CursorPagingCache
import com.jamal_aliev.paginator.cache.CursorPersistentPagingCache
import com.jamal_aliev.paginator.cache.DefaultCursorPagingCache
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.load.CursorLoadResult
import com.jamal_aliev.paginator.logger.PaginatorLogger

// ──────────────────────────────────────────────────────────────────────────────
//  Entry points
// ──────────────────────────────────────────────────────────────────────────────

/**
 * DSL entry point for a **read-only** [CursorPaginator].
 *
 * ```kotlin
 * val messages: CursorPaginator<Message> = cursorPaginator(capacity = 20) {
 *     load { cursor ->
 *         val page = api.loadMessages(cursor?.self as? String)
 *         CursorLoadResult(
 *             data = page.items,
 *             bookmark = CursorBookmark(page.prevCursor, page.selfCursor, page.nextCursor),
 *         )
 *     }
 * }
 * ```
 */
inline fun <T> cursorPaginator(
    capacity: Int = DEFAULT_CAPACITY,
    block: CursorPaginatorBuilder<T>.() -> Unit,
): CursorPaginator<T> {
    return CursorPaginatorBuilder<T>(capacity).apply(block).build()
}

/**
 * DSL entry point for a [MutableCursorPaginator].
 */
inline fun <T> mutableCursorPaginator(
    capacity: Int = DEFAULT_CAPACITY,
    block: MutableCursorPaginatorBuilder<T>.() -> Unit,
): MutableCursorPaginator<T> {
    return MutableCursorPaginatorBuilder<T>(capacity).apply(block).build()
}

// ──────────────────────────────────────────────────────────────────────────────
//  Base builder
// ──────────────────────────────────────────────────────────────────────────────

@PaginatorDsl
sealed class BaseCursorPaginatorBuilder<T> protected constructor(
    @PublishedApi internal val capacity: Int,
) {

    var cache: CursorPagingCache<T> = DefaultCursorPagingCache()
    var persistentCache: CursorPersistentPagingCache<T>? = null
    var logger: PaginatorLogger? = null
    var recyclingBookmark: Boolean = false
    var initialCursor: CursorBookmark? = null

    @PublishedApi
    internal var loadFn: (suspend CursorPaginator<T>.(cursor: CursorBookmark?) -> CursorLoadResult<T>)? =
        null

    @PublishedApi
    internal var bookmarks: List<CursorBookmark>? = null

    @PublishedApi
    internal var initializersBuilder: CursorInitializersBuilder<T>? = null

    /**
     * Sets the suspending [load] function used to fetch a page given an optional
     * cursor hint. **Required.**
     */
    fun load(fn: suspend CursorPaginator<T>.(cursor: CursorBookmark?) -> CursorLoadResult<T>) {
        loadFn = fn
    }

    /** Replaces the bookmark list with the given [list]. Pass nothing to clear. */
    fun bookmarks(list: List<CursorBookmark>) {
        bookmarks = list.toList()
    }

    fun bookmarks(vararg cursors: CursorBookmark) {
        bookmarks = cursors.toList()
    }

    fun initializers(block: CursorInitializersBuilder<T>.() -> Unit) {
        val builder =
            initializersBuilder ?: CursorInitializersBuilder<T>().also { initializersBuilder = it }
        builder.block()
    }

    @PublishedApi
    internal fun buildCore(): CursorPagingCore<T> {
        return CursorPagingCore(
            cache = cache,
            persistentCache = persistentCache,
            initialCapacity = capacity,
        )
    }

    @PublishedApi
    internal fun resolveLoad(): suspend CursorPaginator<T>.(CursorBookmark?) -> CursorLoadResult<T> {
        return checkNotNull(loadFn) {
            "load { ... } block is required when building a cursor paginator"
        }
    }

    @PublishedApi
    internal fun applyCommon(paginator: CursorPaginator<T>, core: CursorPagingCore<T>) {
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

class CursorPaginatorBuilder<T> @PublishedApi internal constructor(
    capacity: Int,
) : BaseCursorPaginatorBuilder<T>(capacity) {

    @PublishedApi
    internal fun build(): CursorPaginator<T> {
        val load = resolveLoad()
        val core = buildCore()
        return CursorPaginator(core = core, load = load).also { applyCommon(it, core) }
    }
}

class MutableCursorPaginatorBuilder<T> @PublishedApi internal constructor(
    capacity: Int,
) : BaseCursorPaginatorBuilder<T>(capacity) {

    @PublishedApi
    internal fun build(): MutableCursorPaginator<T> {
        val load = resolveLoad()
        val core = buildCore()
        return MutableCursorPaginator(core = core, load = load).also { applyCommon(it, core) }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Initializers sub-builder
// ──────────────────────────────────────────────────────────────────────────────

@PaginatorDsl
class CursorInitializersBuilder<T> @PublishedApi internal constructor() {

    private var progress: InitializerProgressPage<T>? = null
    private var success: InitializerSuccessPage<T>? = null
    private var error: InitializerErrorPage<T>? = null

    fun progress(factory: InitializerProgressPage<T>) {
        progress = factory
    }

    fun success(factory: InitializerSuccessPage<T>) {
        success = factory
    }

    fun error(factory: InitializerErrorPage<T>) {
        error = factory
    }

    internal fun applyTo(core: CursorPagingCore<T>) {
        progress?.let { core.initializerProgressPage = it }
        success?.let { core.initializerSuccessPage = it }
        error?.let { core.initializerErrorPage = it }
    }
}
