package com.jamal_aliev.paginator

object SampleRepository {

    const val PAGE_SIZE = 5
    private const val TOTAL_ITEMS = 100

    /** Simulated final page from backend */
    const val FINAL_PAGE = 20

    /** How many items an [LoadChoice.INCOMPLETE] page returns. */
    const val INCOMPLETE_SIZE = 3

    private val data = List(TOTAL_ITEMS) { "Element ${it + 1}" }

    /**
     * Returns the contents of [page] according to the user-picked [choice].
     *
     * The old version rolled a dice (errors / partial pages at random). Now the outcome is
     * fully controlled from the UI dialog so each pagination scenario can be reproduced on
     * demand. [LoadChoice.ERROR] throws to simulate a network failure.
     */
    fun pageData(page: Int, choice: LoadChoice): List<String> {
        val start = PAGE_SIZE * (page - 1)
        val end = (PAGE_SIZE * page).coerceAtMost(TOTAL_ITEMS)
        val fullPage = if (start in 0 until TOTAL_ITEMS) data.subList(start, end) else emptyList()
        return when (choice) {
            LoadChoice.FULL -> fullPage
            LoadChoice.INCOMPLETE -> fullPage.take(INCOMPLETE_SIZE)
            LoadChoice.EMPTY -> emptyList()
            LoadChoice.ERROR -> throw RuntimeException("Network error on page $page")
        }
    }
}
