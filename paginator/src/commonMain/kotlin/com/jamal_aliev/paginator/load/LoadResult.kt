package com.jamal_aliev.paginator.load

/**
 * Bundles the page [data] and optional [metadata] returned by the
 * [load][com.jamal_aliev.paginator.Paginator.load] lambda.
 *
 * For simple sources with no metadata:
 * ```kotlin
 * load = { page -> LoadResult(api.getItems(page)) }
 * ```
 *
 * To propagate API-level metadata (total count, cursor, etc.), pass a
 * [Metadata] subclass:
 * ```kotlin
 * class MyMetadata(val totalCount: Int, val nextCursor: String?) : Metadata()
 *
 * load = { page ->
 *     val response = api.getItems(page)
 *     LoadResult(response.items, MyMetadata(response.total, response.cursor))
 * }
 * ```
 *
 * The [metadata] is forwarded to [PageState.result][com.jamal_aliev.paginator.page.PageState.metadata]
 * of the resulting page state.
 *
 * @param data Items for the requested page, in order. If
 *   [PagingCore.capacity][com.jamal_aliev.paginator.PagingCore.capacity] is set,
 *   excess items are trimmed automatically.
 * @param metadata Arbitrary metadata attached to this load result, or `null` if none.
 * @see Metadata
 * @see com.jamal_aliev.paginator.page.PageState
 */
open class LoadResult<T>(
    open val data: List<T>,
    open val metadata: Metadata? = null
)
