package com.jamal_aliev.paginator.interweave

/**
 * DSL marker for the [InterweaverBuilder] lambda so that nested receivers cannot
 * accidentally call methods on outer builders.
 */
@DslMarker
annotation class InterweaverDsl

/**
 * Convenience builder for assembling an [Interweaver] from lambdas without
 * writing a full interface implementation.
 *
 * All four functional slots ([itemKey], [decorationKey], [between]) must be
 * set before [build] is called; otherwise [IllegalStateException] is thrown.
 *
 * Typically invoked through the [interweaver] top-level factory:
 *
 * ```kotlin
 * val weaver = interweaver<Message, DateLabel> {
 *     itemKey { it.id }
 *     decorationKey { _, _, next -> "date-${next?.date?.toLocalDate()}" }
 *     emitLeading = true
 *     between { prev, next ->
 *         val p = prev?.date?.toLocalDate()
 *         val n = next?.date?.toLocalDate()
 *         if (p != n && n != null) DateLabel(n) else null
 *     }
 * }
 * ```
 */
@InterweaverDsl
class InterweaverBuilder<T, I> @PublishedApi internal constructor() {

    private var itemKey: ((T) -> Any)? = null
    private var decorationKey: ((I, T?, T?) -> Any)? = null
    private var between: ((T?, T?) -> I?)? = null

    /** Whether a decoration may be inserted before the first data item. */
    var emitLeading: Boolean = false

    /** Whether a decoration may be inserted after the last data item. */
    var emitTrailing: Boolean = false

    /** Sets the per-item stable key. See [Interweaver.itemKey]. */
    fun itemKey(fn: (T) -> Any) {
        itemKey = fn
    }

    /** Sets the per-decoration stable key. See [Interweaver.decorationKey]. */
    fun decorationKey(fn: (I, T?, T?) -> Any) {
        decorationKey = fn
    }

    /** Sets the boundary decision function. See [Interweaver.between]. */
    fun between(fn: (prev: T?, next: T?) -> I?) {
        between = fn
    }

    @PublishedApi
    internal fun build(): Interweaver<T, I> {
        val itemKeyFn = checkNotNull(itemKey) {
            "itemKey { } is required when building an Interweaver"
        }
        val decorationKeyFn = checkNotNull(decorationKey) {
            "decorationKey { } is required when building an Interweaver"
        }
        val betweenFn = checkNotNull(between) {
            "between { } is required when building an Interweaver"
        }
        val leading = emitLeading
        val trailing = emitTrailing
        return object : Interweaver<T, I> {
            override fun itemKey(item: T): Any = itemKeyFn(item)
            override fun decorationKey(decoration: I, prev: T?, next: T?): Any =
                decorationKeyFn(decoration, prev, next)

            override fun between(prev: T?, next: T?): I? = betweenFn(prev, next)
            override val emitLeading: Boolean = leading
            override val emitTrailing: Boolean = trailing
        }
    }
}

/**
 * Builds an [Interweaver] from a DSL lambda.
 *
 * @throws IllegalStateException If any of `itemKey`, `decorationKey`, or `between`
 *   is not set inside [block].
 */
inline fun <T, I> interweaver(block: InterweaverBuilder<T, I>.() -> Unit): Interweaver<T, I> =
    InterweaverBuilder<T, I>().apply(block).build()
