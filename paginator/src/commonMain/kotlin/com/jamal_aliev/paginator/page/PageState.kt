package com.jamal_aliev.paginator.page

import com.jamal_aliev.paginator.load.Metadata
import kotlinx.atomicfu.atomic

sealed class PageState<E>(
    open val page: Int,
    open val data: List<E>,
    open val metadata: Metadata? = null,
    open val id: Long = ids.incrementAndGet(),
) : Comparable<PageState<*>> {

    abstract fun copy(
        page: Int = this.page,
        data: List<E> = this.data,
        result: Metadata? = this.metadata,
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
        override val metadata: Metadata? = null,
        override val id: Long = ids.incrementAndGet(),
    ) : PageState<T>(page, data, metadata, id) {

        open fun copy(
            exception: Exception = this.exception,
            page: Int = this.page,
            data: List<T> = this.data,
            result: Metadata? = this.metadata,
            id: Long = this.id
        ): ErrorPage<T> = ErrorPage(exception, page, data, result, id)

        override fun copy(page: Int, data: List<T>, result: Metadata?, id: Long): ErrorPage<T> =
            copy(this.exception, page, data, result, id)

        override fun toString(): String =
            "${this::class.simpleName}(exception=${exception}, data=${this.data})"
    }

    open class ProgressPage<T>(
        override val page: Int,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = ids.incrementAndGet()
    ) : PageState<T>(page, data, metadata, id) {
        override fun copy(page: Int, data: List<T>, result: Metadata?, id: Long) =
            ProgressPage(page, data, result, id)
    }

    open class SuccessPage<T>(
        override val page: Int,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = ids.incrementAndGet()
    ) : PageState<T>(page, data, metadata, id) {

        init {
            checkData()
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun checkData() {
            if (this !is EmptyPage) require(data.isNotEmpty()) { "data must not be empty" }
        }

        override fun copy(page: Int, data: List<T>, result: Metadata?, id: Long): SuccessPage<T> =
            if (data.isEmpty()) EmptyPage(page, data, result, id)
            else SuccessPage(page, data, result, id)
    }

    open class EmptyPage<T>(
        override val page: Int,
        override val data: List<T>,
        override val metadata: Metadata? = null,
        override val id: Long = ids.incrementAndGet()
    ) : SuccessPage<T>(page, data, metadata, id) {
        override fun copy(page: Int, data: List<T>, result: Metadata?, id: Long) =
            EmptyPage(page, data, result, id)
    }

    companion object {
        private val ids = atomic(0L)
        fun nextId(): Long = ids.incrementAndGet()
    }
}
