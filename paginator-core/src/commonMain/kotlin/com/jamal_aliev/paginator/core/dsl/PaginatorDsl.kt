package com.jamal_aliev.paginator.core.dsl

/**
 * DSL marker for the paginator builder hierarchies (offset and cursor).
 *
 * Restricts implicit `this` resolution inside builder lambdas so that nested
 * builders cannot accidentally invoke each other's receivers.
 */
@DslMarker
annotation class PaginatorDsl
