package com.jamal_aliev.paginator.logger

import com.jamal_aliev.paginator.logger.PaginatorLogger.Companion.global


/**
 * Logging interface for [com.jamal_aliev.paginator.Paginator] and
 * [com.jamal_aliev.paginator.MutablePaginator] operations.
 *
 * Supports two levels of configuration:
 *
 * 1. **Global logger** — set once (e.g. in `Application.onCreate()`) to apply
 *    to every paginator in the app:
 *    ```kotlin
 *    PaginatorLogger.global = object : PaginatorLogger {
 *        override fun log(level: LogLevel, component: LogComponent, message: String) {
 *            Log.println(level.toAndroidPriority(), component.name, message)
 *        }
 *    }
 *    ```
 *
 * 2. **Per-instance logger** — set on a specific paginator to override the
 *    global logger for that instance only:
 *    ```kotlin
 *    paginator.logger = PrintPaginatorLogger(minLevel = LogLevel.WARN)
 *    ```
 *
 * Priority: instance logger > [global] logger > no logging.
 *
 * @see LogLevel
 * @see LogComponent
 * @see PrintPaginatorLogger
 * @see CompositePaginatorLogger
 */
interface PaginatorLogger {

    companion object {
        /**
         * Global logger applied to all paginator instances that don't have
         * their own [com.jamal_aliev.paginator.Paginator.logger] set.
         *
         * Typically configured once during application initialization.
         */
        var global: PaginatorLogger? = null
    }

    /**
     * Minimum log level this logger will accept.
     * Messages below this level are discarded by the default [isEnabled] check.
     */
    val minLevel: LogLevel get() = LogLevel.DEBUG

    /**
     * Called by the paginator to log an event.
     *
     * @param level    Severity of the event.
     * @param component Which paginator subsystem produced the event.
     * @param message  A human-readable description of the event.
     */
    fun log(level: LogLevel, component: LogComponent, message: String)

    /**
     * Returns `true` if this logger accepts events at the given [level]
     * and [component]. Override to add component-based filtering.
     *
     * The default implementation checks `level >= minLevel`.
     */
    fun isEnabled(level: LogLevel, component: LogComponent): Boolean =
        level >= minLevel
}
