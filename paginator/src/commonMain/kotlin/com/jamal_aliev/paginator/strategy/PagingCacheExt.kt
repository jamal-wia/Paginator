package com.jamal_aliev.paginator.strategy

/**
 * Composes two cache strategies so that [this] wraps [inner].
 *
 * The operator is left-associative, so `a + b + c` builds the chain `a → b → c → DefaultPagingCache`.
 * Each strategy's [DefaultPagingCache] leaf is replaced by [inner], recursively.
 *
 * ```kotlin
 * // Equivalent forms:
 * val cache = LruPagingCache(maxSize = 50) + TtlPagingCache(ttl = 5.minutes) + SlidingWindowPagingCache()
 *
 * val cache = LruPagingCache(
 *     delegate = TtlPagingCache(
 *         delegate = SlidingWindowPagingCache(),
 *         ttl = 5.minutes
 *     ),
 *     maxSize = 50
 * )
 * ```
 *
 * When [this] is not a [WrappablePagingCache] (i.e., it is the leaf [DefaultPagingCache]),
 * [inner] is returned directly.
 */
operator fun <T> PagingCache<T>.plus(inner: PagingCache<T>): PagingCache<T> = when (this) {
    is WrappablePagingCache<T> -> this.wrap(this.wrapped + inner)
    else -> inner
}
