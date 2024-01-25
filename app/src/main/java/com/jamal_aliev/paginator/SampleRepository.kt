package com.jamal_aliev.paginator

import kotlinx.coroutines.delay

object SampleRepository {

    private const val PAGE_SIZE = 20

    private val data = List(10_000) { "$it" }

    /**
     *The function generates an error with a probability of 50 percent
     * */
    suspend fun loadPage(page: Int): List<String> {
        delay(timeMillis = (1000L..3000L).random())
        return if (Math.random() < 0.5) {
            throw Exception("error")
        } else {
            data.subList(PAGE_SIZE * (page - 1), PAGE_SIZE * page)
        }
    }

}