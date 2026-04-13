package com.jamal_aliev.paginator.page

class PlaceholderProgressPage<T>(
    override val page: Int,
    override val data: List<T>,
    placeholderCapacity: Int,
) : PageState.ProgressPage<T>(page, data) {

    init {
        @Suppress("UncheckedCast")
        val mutableData: MutableList<Any> = requireNotNull(data as? MutableList<Any>) {
            "PlaceholderProgressPage requires mutable data."
        }

        repeat(placeholderCapacity) {
            mutableData.add(Placeholder)
        }
    }

    data object Placeholder
}
