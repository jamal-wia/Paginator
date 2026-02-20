package com.jamal_aliev.paginator.page

import java.util.concurrent.atomic.AtomicLong

sealed class PageState<E>(
    open val page: Int,
    open val data: List<E>,
    open val id: Long = ids.incrementAndGet(),
) : Comparable<PageState<*>> {

    abstract fun copy(
        page: Int = this.page,
        data: List<E> = this.data,
        id: Long = this.id
    ): PageState<E>

    override fun toString() = "${this::class.simpleName}(page=$page id=$id data=$data)"

    override fun hashCode(): Int = this.page.hashCode()

    override fun equals(other: Any?): Boolean = (other as? PageState<*>)?.id == id

    override operator fun compareTo(other: PageState<*>): Int = page.compareTo(other.page)

    open class ErrorPage<T>(
        val exception: Exception,
        override val page: Int,
        override val data: List<T>,
        override val id: Long = ids.incrementAndGet(),
    ) : PageState<T>(page, data, id) {

        open fun copy(
            exception: Exception,
            page: Int,
            data: List<T>,
            id: Long = this.id
        ): ErrorPage<T> {
            return ErrorPage(exception, page, data, id)
        }

        override fun copy(page: Int, data: List<T>, id: Long): ErrorPage<T> {
            return copy(this.exception, page, data, id)
        }

        override fun toString(): String {
            return "${this::class.simpleName}(exception=${exception}, data=${this.data})"
        }
    }

    open class ProgressPage<T>(
        override val page: Int,
        override val data: List<T>,
        override val id: Long = ids.incrementAndGet()
    ) : PageState<T>(page, data, id) {

        override fun copy(page: Int, data: List<T>, id: Long) = ProgressPage(page, data, id)
    }

    open class SuccessPage<T>(
        override val page: Int,
        override val data: List<T>,
        override val id: Long = ids.incrementAndGet()
    ) : PageState<T>(page, data, id) {

        init {
            checkData()
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun checkData() {
            if (this !is EmptyPage) {
                require(data.isNotEmpty()) { "data must not be empty" }
            }
        }

        /**
         * If you want to override this function, you should check the data because it can't be empty
         * */
        override fun copy(page: Int, data: List<T>, id: Long): SuccessPage<T> {
            return if (data.isEmpty()) EmptyPage(page, data, id)
            else SuccessPage(page, data, id)
        }
    }

    open class EmptyPage<T>(
        override val page: Int,
        override val data: List<T>,
        override val id: Long = ids.incrementAndGet()
    ) : SuccessPage<T>(page, data, id) {

        override fun copy(page: Int, data: List<T>, id: Long) = EmptyPage(page, data, id)
    }

    private companion object {
        private var ids = AtomicLong(0L)
    }
}
