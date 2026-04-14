package com.jamal_aliev.paginator.logger

/**
 * Logs a [LogLevel.DEBUG] message if logging is enabled.
 *
 * Resolves the effective logger as: `this` (instance) → [PaginatorLogger.global] → skip.
 * The [message] lambda is only invoked when the log will actually be emitted,
 * avoiding unnecessary string concatenation.
 */
inline fun PaginatorLogger?.debug(component: LogComponent, message: () -> String) {
    val effective = this ?: PaginatorLogger.global ?: return
    if (effective.isEnabled(LogLevel.DEBUG, component)) {
        effective.log(LogLevel.DEBUG, component, message())
    }
}

/**
 * Logs a [LogLevel.INFO] message if logging is enabled.
 *
 * @see debug
 */
inline fun PaginatorLogger?.info(component: LogComponent, message: () -> String) {
    val effective = this ?: PaginatorLogger.global ?: return
    if (effective.isEnabled(LogLevel.INFO, component)) {
        effective.log(LogLevel.INFO, component, message())
    }
}

/**
 * Logs a [LogLevel.WARN] message if logging is enabled.
 *
 * @see debug
 */
inline fun PaginatorLogger?.warn(component: LogComponent, message: () -> String) {
    val effective = this ?: PaginatorLogger.global ?: return
    if (effective.isEnabled(LogLevel.WARN, component)) {
        effective.log(LogLevel.WARN, component, message())
    }
}

/**
 * Logs a [LogLevel.ERROR] message if logging is enabled.
 *
 * @see debug
 */
inline fun PaginatorLogger?.error(component: LogComponent, message: () -> String) {
    val effective = this ?: PaginatorLogger.global ?: return
    if (effective.isEnabled(LogLevel.ERROR, component)) {
        effective.log(LogLevel.ERROR, component, message())
    }
}
