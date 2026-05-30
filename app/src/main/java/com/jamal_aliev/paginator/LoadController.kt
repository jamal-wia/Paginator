package com.jamal_aliev.paginator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The four outcomes the user can pick for each page load in the demo. Replaces the old
 * random behavior of [SampleRepository] so every load is deterministic and user-driven.
 */
enum class LoadChoice { FULL, INCOMPLETE, EMPTY, ERROR }

/** A single in-flight load currently waiting for the user's decision. */
data class PendingLoad(
    val page: Int,
    /** Total requests in the queue, including this one. */
    val waitingCount: Int,
)

/**
 * Bridges the paginator's `suspend` load lambda to the UI.
 *
 * When the paginator asks to load a page, [awaitChoice] suspends the load coroutine until
 * the user resolves the central dialog. Because the paginator can issue several loads at
 * once (e.g. forward + backward prefetch, or a multi-page jump), requests are queued FIFO
 * and presented one at a time via [pending].
 */
class LoadController {

    private data class Request(val page: Int, val deferred: CompletableDeferred<LoadChoice>)

    private val mutex = Mutex()
    private val queue = ArrayDeque<Request>()

    private val _pending = MutableStateFlow<PendingLoad?>(null)
    val pending: StateFlow<PendingLoad?> = _pending.asStateFlow()

    /** Suspends until the user picks an outcome for [page]. */
    suspend fun awaitChoice(page: Int): LoadChoice {
        val request = Request(page, CompletableDeferred())
        mutex.withLock {
            queue.addLast(request)
            publishHead()
        }
        try {
            return request.deferred.await()
        } finally {
            // On normal completion resolve() already removed the head; on cancellation
            // (e.g. restart() cancels in-flight loads) we drop our own entry wherever it sits.
            // NonCancellable is required: this runs in an already-cancelled coroutine, where a
            // bare suspending mutex.withLock would re-throw before the cleanup could happen.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (queue.remove(request)) publishHead()
                }
            }
        }
    }

    /** Resolves the request currently shown in the dialog. No-op if nothing is pending. */
    suspend fun resolve(choice: LoadChoice) {
        mutex.withLock {
            val head = queue.removeFirstOrNull() ?: return
            head.deferred.complete(choice)
            publishHead()
        }
    }

    private fun publishHead() {
        _pending.value = queue.firstOrNull()
            ?.let { PendingLoad(page = it.page, waitingCount = queue.size) }
    }
}
