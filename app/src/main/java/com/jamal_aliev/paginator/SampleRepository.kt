package com.jamal_aliev.paginator

import kotlinx.coroutines.delay

object SampleRepository {

    private const val PAGE_SIZE = 20

    private val data = List(10_000) { "$it" }

    suspend fun loadPage(page: Int): List<String> {
        delay(timeMillis = (1000L..3000L).random())
        return data.subList(PAGE_SIZE * (page - 1), PAGE_SIZE * page)
    }


}