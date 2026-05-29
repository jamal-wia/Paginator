package com.jamal_aliev.paginator.compose.core

/**
 * Marker for APIs that are public for cross-module reuse inside the Paginator suite (currently:
 * `paginator-compose-offset` and `paginator-compose-cursor` calling into
 * `paginator-compose-core`'s `Bind*ScrollPreservation` composables) but are not part of the
 * stable user-facing surface.
 *
 * Symbols annotated with this MAY change shape or disappear between minor versions. End users
 * should not need to opt in — `BindToLazyList(preserveScroll = true)` and friends do the
 * opt-in transparently. If you find yourself reaching for this annotation in app code, you are
 * almost certainly using the wrong entry point.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is an internal cross-module API of the Paginator suite. It is not stable; " +
            "use BindToLazyList(preserveScroll = true) / BindToLazyGrid(preserveScroll = true) / " +
            "BindToLazyStaggeredGrid(preserveScroll = true) from paginator-compose-offset or " +
            "paginator-compose-cursor instead."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class PaginatorInternalApi
