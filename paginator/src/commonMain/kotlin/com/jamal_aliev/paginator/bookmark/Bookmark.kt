package com.jamal_aliev.paginator.bookmark

interface Bookmark {

    open class BookmarkInt(val page: Int) : Bookmark {
        init {
            require(page >= 1) { "page must be >= 1, but was $page" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BookmarkInt) return false
            return page == other.page
        }

        override fun hashCode(): Int = page

        override fun toString(): String = "BookmarkInt(page=$page)"
    }

}
