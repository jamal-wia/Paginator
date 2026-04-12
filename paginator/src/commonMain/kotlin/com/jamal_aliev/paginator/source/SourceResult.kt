package com.jamal_aliev.paginator.source

/**
 * Wraps the result of a [source][com.jamal_aliev.paginator.Paginator.source] call,
 * carrying both the page [data] and any additional metadata from the API response.
 *
 * For simple sources that return only a list, use [SourceResult] directly:
 * ```kotlin
 * source = { page -> SourceResult(api.getItems(page)) }
 * ```
 *
 * To carry metadata, subclass [SourceResult]:
 * ```kotlin
 * class MySourceResult<T>(
 *     override val data: List<T>,
 *     val totalCount: Int,
 *     val nextCursor: String?,
 * ) : SourceResult<T>(data)
 * ```
 *
 * Metadata can then be accessed in custom [PageState][com.jamal_aliev.paginator.page.PageState]
 * initializers to embed it into your page state objects.
 *
 * @param T The type of elements in [data].
 * @param data The page items returned by the source.
 */
open class SourceResult<T>(
    open val data: List<T>
)

/**
 * Wraps this [List] in a [SourceResult] with no additional metadata.
 *
 * Convenience extension for sources that do not carry metadata:
 * ```kotlin
 * source = { page -> api.getItems(page).toSourceResult() }
 * ```
 */
fun <T> List<T>.toSourceResult(): SourceResult<T> = SourceResult(this)
