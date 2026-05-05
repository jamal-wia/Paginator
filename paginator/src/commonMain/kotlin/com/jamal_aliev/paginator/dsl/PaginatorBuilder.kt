package com.jamal_aliev.paginator.dsl

import com.jamal_aliev.paginator.MutablePaginator
import com.jamal_aliev.paginator.Paginator
import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.PagingCore.Companion.DEFAULT_CAPACITY
import com.jamal_aliev.paginator.bookmark.BookmarkInt
import com.jamal_aliev.paginator.cache.InMemoryPagingCache
import com.jamal_aliev.paginator.cache.PagingCache
import com.jamal_aliev.paginator.cache.persistent.PersistentPagingCache
import com.jamal_aliev.paginator.initializer.InitializerErrorPage
import com.jamal_aliev.paginator.initializer.InitializerProgressPage
import com.jamal_aliev.paginator.initializer.InitializerSuccessPage
import com.jamal_aliev.paginator.load.LoadResult
import com.jamal_aliev.paginator.logger.PaginatorLogger

/**
 * Marker that scopes lambdas inside the paginator-builder DSL so that nested
 * receivers cannot accidentally call methods on outer builders.
 */
@DslMarker
annotation class PaginatorDsl

// ──────────────────────────────────────────────────────────────────────────────
//  Entry points
// ──────────────────────────────────────────────────────────────────────────────

/**
 * DSL entry point for a **read-only** [Paginator].
 *
 * Use this when the call site only needs navigation, refresh, and snapshot flow
 * access. For element-level CRUD (`addElement`, `removeElement`, `setElement`, …),
 * use [mutablePaginator] instead.
 *
 * ```kotlin
 * val users: Paginator<User> = paginator(capacity = 20) {
 *     load { page -> api.getUsers(page) }
 *     finalPage = 100
 * }
 * ```
 *
 * @param capacity Items expected per page; default [DEFAULT_CAPACITY].
 *   Pass [PagingCore.UNLIMITED_CAPACITY] (0) to disable capacity checks.
 * @param block DSL configuration block. The [PaginatorBuilder.load] call is required.
 * @return A fully wired [Paginator].
 * @throws IllegalStateException If [PaginatorBuilder.load] was not called inside [block].
 *
 * @see mutablePaginator
 */
inline fun <T> paginator(
    capacity: Int = DEFAULT_CAPACITY,
    block: PaginatorBuilder<T>.() -> Unit,
): Paginator<T> {
    return PaginatorBuilder<T>(capacity).apply(block).build()
}

/**
 * DSL entry point for a **mutable** [MutablePaginator].
 *
 * Use this when the call site needs element-level CRUD (`addElement`,
 * `removeElement`, `setElement`, `replaceAllElements`, transactional flushes,
 * …) on top of navigation. Otherwise prefer [paginator].
 *
 * ```kotlin
 * val users: MutablePaginator<User> = mutablePaginator(capacity = 20) {
 *     load { page -> api.getUsers(page) }
 *
 *     cache = MostRecentPagingCache(maxSize = 50) + TimeLimitedPagingCache(ttl = 5.minutes)
 *     persistentCache = roomPagingCache
 *
 *     finalPage = 100
 *     bookmarks(1, 10, 50, 100)
 *     recyclingBookmark = true
 *     logger = PrintPaginatorLogger()
 *
 *     initializers {
 *         success { page, data, _ -> MySuccessPage(page, data) }
 *         error { e, page, data, _ -> MyErrorPage(e, page, data) }
 *     }
 * }
 * ```
 *
 * @param capacity Items expected per page; default [DEFAULT_CAPACITY].
 *   Pass [PagingCore.UNLIMITED_CAPACITY] (0) to disable capacity checks.
 * @param block DSL configuration block. The [MutablePaginatorBuilder.load] call is required.
 * @return A fully wired [MutablePaginator].
 * @throws IllegalStateException If [MutablePaginatorBuilder.load] was not called inside [block].
 *
 * @see paginator
 */
inline fun <T> mutablePaginator(
    capacity: Int = DEFAULT_CAPACITY,
    block: MutablePaginatorBuilder<T>.() -> Unit,
): MutablePaginator<T> {
    return MutablePaginatorBuilder<T>(capacity).apply(block).build()
}

// ──────────────────────────────────────────────────────────────────────────────
//  Base builder
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Common configuration shared by [PaginatorBuilder] and [MutablePaginatorBuilder].
 *
 * Holds every knob that can be set on a [Paginator] (and therefore also on a
 * [MutablePaginator], which inherits from it). Concrete subclasses only differ
 * in the type returned by their `build()` function.
 *
 * Sealed: only the two built-in builders may extend this class.
 */
@PaginatorDsl
sealed class BasePaginatorBuilder<T> protected constructor(
    @PublishedApi internal val capacity: Int,
) {

    /**
     * In-memory cache (L1) used by the paginator.
     *
     * Compose eviction strategies with the `+` operator, e.g.
     * `MostRecentPagingCache(maxSize = 50) + TimeLimitedPagingCache(ttl = 5.minutes)`.
     *
     * Defaults to [InMemoryPagingCache] (no eviction).
     */
    var cache: PagingCache<T> = InMemoryPagingCache()

    /**
     * Optional persistent (L2) cache. Defaults to `null` (no persistence).
     */
    var persistentCache: PersistentPagingCache<T>? = null

    /**
     * Optional logger forwarded to both the paginator and its [PagingCore].
     */
    var logger: PaginatorLogger? = null

    /**
     * Maximum page number allowed. Defaults to [Int.MAX_VALUE] (no limit).
     */
    var finalPage: Int = Int.MAX_VALUE

    /**
     * Whether bookmark navigation wraps around at the ends of the bookmark list.
     */
    var recyclingBookmark: Boolean = false

    @PublishedApi
    internal var loadFn: (suspend Paginator<T>.(page: Int) -> LoadResult<T>)? = null

    @PublishedApi
    internal var bookmarks: List<BookmarkInt>? = null

    @PublishedApi
    internal var initializersBuilder: InitializersBuilder<T>? = null

    /**
     * Sets the suspending [load] function used to fetch a page from the source.
     *
     * **Required** — the builder will throw if you forget to call this.
     */
    fun load(fn: suspend Paginator<T>.(page: Int) -> LoadResult<T>) {
        loadFn = fn
    }

    /**
     * Replaces the default bookmark (page 1) with the given [pages], wrapped in
     * [BookmarkInt] instances.
     *
     * Pass nothing to clear bookmarks entirely.
     */
    fun bookmarks(vararg pages: Int) {
        bookmarks = pages.map { BookmarkInt(it) }
    }

    /**
     * Replaces the default bookmark (page 1) with the supplied [BookmarkInt] list.
     */
    fun bookmarks(list: List<BookmarkInt>) {
        bookmarks = list.toList()
    }

    /**
     * Configures the [PageState][com.jamal_aliev.paginator.page.PageState]
     * factories used by the paginator. Any factory left unset retains its default.
     *
     * ```kotlin
     * initializers {
     *     success { page, data, meta -> MySuccessPage(page, data, meta) }
     *     error { e, page, data, meta -> MyErrorPage(e, page, data, meta) }
     * }
     * ```
     *
     * Calling this multiple times merges the factories — later calls overwrite
     * only the slots they set.
     */
    fun initializers(block: InitializersBuilder<T>.() -> Unit) {
        val builder: InitializersBuilder<T> =
            initializersBuilder ?: InitializersBuilder<T>().also { initializersBuilder = it }
        builder.block()
    }

    /** Builds the [PagingCore] from the configured cache fields. */
    @PublishedApi
    internal fun buildCore(): PagingCore<T> {
        return PagingCore(
            cache = cache,
            persistentCache = persistentCache,
            initialCapacity = capacity,
        )
    }

    /** Resolves the load function or fails with a clear message. */
    @PublishedApi
    internal fun resolveLoad(): suspend Paginator<T>.(Int) -> LoadResult<T> {
        return checkNotNull(loadFn) {
            "load { ... } block is required when building a paginator"
        }
    }

    /** Applies all the cross-cutting configuration to a freshly created paginator. */
    @PublishedApi
    internal fun applyCommon(paginator: Paginator<T>, core: PagingCore<T>) {
        paginator.finalPage = finalPage
        paginator.recyclingBookmark = recyclingBookmark
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

/**
 * Builder for a read-only [Paginator]. Collected by the [paginator] DSL.
 */
class PaginatorBuilder<T> @PublishedApi internal constructor(
    capacity: Int,
) : BasePaginatorBuilder<T>(capacity) {

    @PublishedApi
    internal fun build(): Paginator<T> {
        val load = resolveLoad()
        val core = buildCore()
        return Paginator(core = core, load = load).also { applyCommon(it, core) }
    }
}

/**
 * Builder for a [MutablePaginator]. Collected by the [mutablePaginator] DSL.
 */
class MutablePaginatorBuilder<T> @PublishedApi internal constructor(
    capacity: Int,
) : BasePaginatorBuilder<T>(capacity) {

    @PublishedApi
    internal fun build(): MutablePaginator<T> {
        val load = resolveLoad()
        val core = buildCore()
        return MutablePaginator(core = core, load = load).also { applyCommon(it, core) }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Initializers sub-builder
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Sub-builder for overriding the default [PageState][com.jamal_aliev.paginator.page.PageState]
 * factories on the underlying [PagingCore].
 *
 * Each setter is independent — any factory you do not call keeps its default.
 */
@PaginatorDsl
class InitializersBuilder<T> @PublishedApi internal constructor() {

    private var progress: InitializerProgressPage<T>? = null
    private var success: InitializerSuccessPage<T>? = null
    private var error: InitializerErrorPage<T>? = null

    /** Override the [PageState.ProgressPage][com.jamal_aliev.paginator.page.PageState.ProgressPage] factory. */
    fun progress(factory: InitializerProgressPage<T>) {
        progress = factory
    }

    /**
     * Override the [PageState.SuccessPage][com.jamal_aliev.paginator.page.PageState.SuccessPage] factory.
     *
     * The same factory is used regardless of whether the source returned data — an
     * "empty" page is just a [PageState.SuccessPage][com.jamal_aliev.paginator.page.PageState.SuccessPage]
     * with an empty `data` list.
     */
    fun success(factory: InitializerSuccessPage<T>) {
        success = factory
    }

    /** Override the [PageState.ErrorPage][com.jamal_aliev.paginator.page.PageState.ErrorPage] factory. */
    fun error(factory: InitializerErrorPage<T>) {
        error = factory
    }

    internal fun applyTo(core: PagingCore<T>) {
        progress?.let { core.initializerProgressPage = it }
        success?.let { core.initializerSuccessPage = it }
        error?.let { core.initializerErrorPage = it }
    }
}
