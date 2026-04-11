package com.jamal_aliev.paginator

/**
 * Creates a [MutablePaginator] with a deterministic source (no random failures).
 */
fun createDeterministicPaginator(
    capacity: Int = 5,
    totalItems: Int = 100,
): MutablePaginator<String> {
    return MutablePaginator<String> { page: Int ->
        val startIndex = (page - 1) * this.core.capacity
        val endIndex = minOf(startIndex + this.core.capacity, totalItems)
        if (startIndex >= totalItems) emptyList()
        else List(endIndex - startIndex) { "item_${startIndex + it}" }
    }.apply {
        core.resize(capacity = capacity, resize = false, silently = true)
    }
}

/**
 * Creates a [MutablePaginator] pre-populated with filled success pages,
 * with context set via jump to page 1.
 * Must be called from a coroutine.
 */
suspend fun createPopulatedPaginator(
    pageCount: Int = 5,
    capacity: Int = 3,
): MutablePaginator<String> {
    val paginator = MutablePaginator<String> { page: Int ->
        if (page in 1..pageCount) {
            MutableList(capacity) { "p${page}_item$it" }
        } else {
            emptyList()
        }
    }
    paginator.core.resize(capacity = capacity, resize = false, silently = true)
    // Load all pages via navigation
    paginator.jump(
        bookmark = com.jamal_aliev.paginator.bookmark.Bookmark.BookmarkInt(1),
        silentlyLoading = true,
        silentlyResult = true,
    )
    for (i in 2..pageCount) {
        paginator.goNextPage(silentlyLoading = true, silentlyResult = true)
    }
    return paginator
}
