package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.offset.page.OffsetPageState

data class MainViewState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val data: List<OffsetPageState<String>> = emptyList(),
    val currentPage: Int = 0,
    val startContextPage: Int = 0,
    val endContextPage: Int = 0,
    val finalPage: Int = Int.MAX_VALUE,
    val cachedPages: List<CachedPageInfo> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
    val totalCachedItems: Int = 0,
    val errorMessage: String? = null,
)

data class CachedPageInfo(
    val page: Int,
    val type: String,
    val itemCount: Int,
)
