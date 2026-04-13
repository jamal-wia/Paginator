package com.jamal_aliev.paginator.logger

/**
 * A [PaginatorLogger] that delegates to multiple loggers simultaneously.
 *
 * Use the [plus] operator for ergonomic composition:
 * ```kotlin
 * val consoleLogger = PrintPaginatorLogger(minLevel = LogLevel.DEBUG)
 * val analyticsLogger = MyAnalyticsLogger(minLevel = LogLevel.WARN)
 *
 * PaginatorLogger.global = consoleLogger + analyticsLogger
 * ```
 *
 * @param loggers The list of loggers to delegate to.
 */
class CompositePaginatorLogger(
    internal val loggers: List<PaginatorLogger>
) : PaginatorLogger {

    override val minLevel: LogLevel
        get() = loggers.minOf { it.minLevel }

    override fun isEnabled(level: LogLevel, component: LogComponent): Boolean =
        loggers.any { it.isEnabled(level, component) }

    override fun log(level: LogLevel, component: LogComponent, message: String) {
        for (logger in loggers) {
            if (logger.isEnabled(level, component)) {
                logger.log(level, component, message)
            }
        }
    }
}

/**
 * Combines two loggers into a [CompositePaginatorLogger].
 *
 * Nested composites are flattened automatically.
 */
operator fun PaginatorLogger.plus(other: PaginatorLogger): CompositePaginatorLogger =
    CompositePaginatorLogger(
        buildList {
            if (this@plus is CompositePaginatorLogger) addAll(loggers) else add(this@plus)
            if (other is CompositePaginatorLogger) addAll(other.loggers) else add(other)
        }
    )
