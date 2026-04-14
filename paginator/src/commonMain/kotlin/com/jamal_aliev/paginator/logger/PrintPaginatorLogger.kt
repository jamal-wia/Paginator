package com.jamal_aliev.paginator.logger

/**
 * A simple [PaginatorLogger] that prints to standard output via [println].
 *
 * Works on all Kotlin Multiplatform targets. Useful for quick debugging
 * and as a reference implementation.
 *
 * **Example:**
 * ```kotlin
 * // Log everything
 * PaginatorLogger.global = PrintPaginatorLogger()
 *
 * // Only warnings and errors from cache operations
 * paginator.logger = PrintPaginatorLogger(
 *     minLevel = LogLevel.WARN,
 *     enabledComponents = setOf(LogComponent.CACHE)
 * )
 * ```
 *
 * @param minLevel Minimum severity to log. Defaults to [LogLevel.DEBUG].
 * @param enabledComponents Components to log. Defaults to all.
 */
class PrintPaginatorLogger(
    override val minLevel: LogLevel = LogLevel.DEBUG,
    private val enabledComponents: Set<LogComponent> = LogComponent.entries.toSet()
) : PaginatorLogger {

    override fun isEnabled(level: LogLevel, component: LogComponent): Boolean =
        level >= minLevel && component in enabledComponents

    override fun log(level: LogLevel, component: LogComponent, message: String) {
        println("[${level.name}] [${component.name}] $message")
    }
}
