package com.jamal_aliev.paginator.load

/**
 * Base class for metadata returned alongside page data by the
 * [load][com.jamal_aliev.paginator.Paginator.load] lambda.
 *
 * Subclass to propagate API-level details (total count, cursor, ETag, etc.) into
 * [PageState.result][com.jamal_aliev.paginator.page.PageState.metadata]
 * without coupling [LoadResult] to a concrete response type.
 *
 * ```kotlin
 * class MyMetadata(val totalCount: Int, val nextCursor: String?) : Metadata()
 * ```
 *
 * @see LoadResult
 * @see com.jamal_aliev.paginator.page.PageState.metadata
 */
open class Metadata
