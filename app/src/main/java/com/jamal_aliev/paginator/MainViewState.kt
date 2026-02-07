package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.page.PageState

data class MainViewState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val data: List<PageState<String>> = emptyList(),
    val currentPage: UInt = 0u,
    val startContextPage: UInt = 0u,
    val endContextPage: UInt = 0u,
    val finalPage: UInt = UInt.MAX_VALUE,
    val cachedPages: List<CachedPageInfo> = emptyList(),
    val bookmarks: List<UInt> = emptyList(),
    val totalCachedItems: Int = 0,
    val errorMessage: String? = null,
)

data class CachedPageInfo(
    val page: UInt,
    val type: String,
    val itemCount: Int,
)
