package com.jamal_aliev.paginator

import kotlinx.coroutines.delay

object SampleRepository {

    const val PAGE_SIZE = 5
    private const val TOTAL_ITEMS = 100

    /** Simulated final page from backend */
    const val FINAL_PAGE = 20

    private val data = List(TOTAL_ITEMS) { "Element ${it + 1}" }

    /**
     * Simulates network loading.
     * - 20% chance of error
     * - 30% chance of returning 3 items (incomplete)
     * - 20% chance of returning 4 items (incomplete)
     * - 50% chance of returning full 5 items
     */
    suspend fun loadPage(page: Int): List<String> {
        delay(timeMillis = (800L..2000L).random())
        if (Math.random() < 0.2) {
            throw Exception("Network error on page $page")
        }
        val start = PAGE_SIZE * (page - 1)
        val end = (PAGE_SIZE * page).coerceAtMost(TOTAL_ITEMS)
        if (start >= TOTAL_ITEMS) return emptyList()
        val fullPage = data.subList(start, end)
        val roll = (0..9).random()
        return when {
            roll < 3 && fullPage.size == PAGE_SIZE -> fullPage.take(3)
            roll < 5 && fullPage.size == PAGE_SIZE -> fullPage.take(4)
            else -> fullPage
        }
    }
}
