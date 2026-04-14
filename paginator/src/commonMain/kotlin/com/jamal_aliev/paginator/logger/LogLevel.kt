package com.jamal_aliev.paginator.logger

/**
 * Severity levels for paginator log events.
 *
 * Values are ordered by severity: [DEBUG] < [INFO] < [WARN] < [ERROR].
 * The natural [ordinal] ordering is used for level comparison in
 * [PaginatorLogger.isEnabled].
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}
