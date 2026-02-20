package com.jamal_aliev.paginator.logger

/**
 * Logging interface for [com.jamal_aliev.paginator.Paginator] and
 * [com.jamal_aliev.paginator.MutablePaginator] operations.
 *
 * Implement this interface to receive detailed logs about navigation,
 * state changes, and element-level CRUD operations performed by the paginator.
 *
 * Set your implementation via [com.jamal_aliev.paginator.Paginator.logger].
 *
 * **Example:**
 * ```kotlin
 * val paginator = MutablePaginator<String>(source = { page ->
 *     api.fetchItems(page.toInt())
 * }).apply {
 *     logger = object : Logger {
 *         override fun log(tag: String, message: String) {
 *             Log.d(tag, message)
 *         }
 *     }
 * }
 * ```
 */
interface PaginatorLogger {

    /**
     * Called by the paginator to log an event.
     *
     * @param tag A short identifier for the operation category
     *   (e.g. `"Paginator"`, `"MutablePaginator"`).
     * @param message A human-readable description of the event.
     */
    fun log(tag: String, message: String)
}
