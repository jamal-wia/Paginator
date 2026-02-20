package com.jamal_aliev.paginator.bookmark

interface Bookmark {

    val page: Int

    @JvmInline
    value class BookmarkInt(
        override val page: Int
    ) : Bookmark {
        init {
            require(page >= 1) { "page must be >= 1, but was $page" }
        }
    }

}
