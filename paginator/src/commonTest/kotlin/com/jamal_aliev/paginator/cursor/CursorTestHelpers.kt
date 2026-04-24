package com.jamal_aliev.paginator.cursor

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.CursorPagingCore
import com.jamal_aliev.paginator.MutableCursorPaginator
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.load.CursorLoadResult

/**
 * Simulates a cursor-based HTTP backend: a list of pages, each identified by a
 * string `self` key, linked together so that `page[i].next == page[i + 1].self`
 * and `page[0].prev == null` / `page[last].next == null`.
 *
 * The test backend tracks every [callCount] so tests can assert that the
 * paginator hit (or avoided hitting) the source.
 */
class FakeCursorBackend(
    val pages: List<Page> = defaultPages(),
) {
    class Page(
        val self: String,
        val items: List<String>,
        val prev: String? = null,
        val next: String? = null,
    )

    private val bySelf: Map<String, Page> = pages.associateBy { it.self }
    val head: Page get() = pages.first()
    val tail: Page get() = pages.last()

    var callCount: Int = 0
        private set

    /** Maps a hint cursor (from `goNext` / `goPrev` / `jump`) to the target page. */
    fun resolve(cursor: CursorBookmark?): Page {
        callCount++
        if (cursor == null) return pages.first()
        // Callers pass the **target** self in `cursor.self`. Resolve by self first.
        bySelf[cursor.self]?.let { return it }
        // Fall back to prev/next hints for `goNext`/`goPrev` stub cursors.
        cursor.prev?.let { prevSelf ->
            bySelf[prevSelf]?.next?.let { bySelf[it] }?.also { return it }
        }
        cursor.next?.let { nextSelf ->
            bySelf[nextSelf]?.prev?.let { bySelf[it] }?.also { return it }
        }
        error("unknown cursor: $cursor")
    }

    fun loadResult(cursor: CursorBookmark?): CursorLoadResult<String> {
        val page = resolve(cursor)
        return CursorLoadResult(
            data = page.items,
            bookmark = CursorBookmark(
                prev = page.prev,
                self = page.self,
                next = page.next,
            ),
        )
    }

    companion object {
        fun defaultPages(
            pageCount: Int = 5,
            capacity: Int = 3,
        ): List<Page> = List(pageCount) { idx ->
            val self = "p$idx"
            val prev = if (idx == 0) null else "p${idx - 1}"
            val next = if (idx == pageCount - 1) null else "p${idx + 1}"
            Page(
                self = self,
                prev = prev,
                next = next,
                items = List(capacity) { i -> "${self}_item$i" },
            )
        }
    }
}

/**
 * Builds a fully-wired [CursorPaginator] with a [FakeCursorBackend] and the
 * given capacity.
 */
fun cursorPaginatorOf(
    backend: FakeCursorBackend = FakeCursorBackend(),
    capacity: Int = 3,
): CursorPaginator<String> {
    val core = CursorPagingCore<String>(initialCapacity = capacity)
    return CursorPaginator<String>(core = core) { cursor ->
        backend.loadResult(cursor)
    }
}

/** Like [cursorPaginatorOf] but with [MutableCursorPaginator]. */
fun mutableCursorPaginatorOf(
    backend: FakeCursorBackend = FakeCursorBackend(),
    capacity: Int = 3,
): MutableCursorPaginator<String> {
    val core = CursorPagingCore<String>(initialCapacity = capacity)
    return MutableCursorPaginator<String>(core = core) { cursor ->
        backend.loadResult(cursor)
    }
}

/** Builds a paginator whose load lambda always throws [error]. */
fun failingCursorPaginator(
    error: Exception = RuntimeException("boom"),
    capacity: Int = 3,
): CursorPaginator<String> {
    val core = CursorPagingCore<String>(initialCapacity = capacity)
    return CursorPaginator<String>(core = core) { _ -> throw error }
}
