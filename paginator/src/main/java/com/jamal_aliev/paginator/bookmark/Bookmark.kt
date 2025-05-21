package com.jamal_aliev.paginator.bookmark

interface Bookmark {

    val page: UInt

    @JvmInline
    value class BookmarkUInt(
        override val page: UInt
    ) : Bookmark

}
