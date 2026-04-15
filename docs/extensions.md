# Extension Functions & Full Example

[← Back to README](../README.md)

## Table of Contents

- [Extension Functions](#extension-functions)
    - [PageState Extensions](#pagestate-extensions)
    - [Paginator Extensions](#paginator-extensions)
- [Full Example](#full-example)

---

## Extension Functions

### PageState Extensions

Type-checking with Kotlin contracts for smart-casting:

```kotlin
pageState.isProgressState()  // true if ProgressPage
pageState.isSuccessState()   // true if SuccessPage (but NOT EmptyPage)
pageState.isEmptyState()     // true if EmptyPage
pageState.isErrorState()     // true if ErrorPage

// Check for specific custom subclasses
pageState.isRealProgressState(MyCustomProgress::class)
```

Distance calculations:

```kotlin
pageA near pageB  // true if pages are 0 or 1 apart
pageA far pageB   // true if pages are more than 1 apart
pageA gap pageB   // Int distance between page numbers
```

### Paginator Extensions

```kotlin
// Search for elements
paginator.indexOfFirst { it.id == targetId }    // Returns Pair<Int, Int>? (page, index)
paginator.indexOfLast { it.name == "test" }     // Search in reverse
paginator.getElement { it.id == targetId }             // Get first matching element

// Modify elements
paginator.setElement(updatedItem) { it.id == targetId }
paginator.removeElement { it.id == targetId }
paginator.addElement(newItem)  // Append to last page

// Iteration
paginator.forEach { pageState -> /* ... */ }
paginator.smartForEach { states, index, currentState -> /* continue? */ true }

// Traversal
paginator.walkForwardWhile(startState) { it.isSuccessState() }
paginator.walkBackwardWhile(endState) { it.isSuccessState() }

// Refresh all cached pages
paginator.refreshAll()
```

---

## Full Example

A complete ViewModel demonstrating all major features:

```kotlin
class PaginatorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<List<PageState<String>>>(emptyList())
    val uiState = _uiState.asStateFlow()

    private val paginator = MutablePaginator<String>(load = { page ->
        LoadResult(repository.loadPage(page))
    }).apply {
        core.resize(capacity = 5, resize = false, silently = true)
        finalPage = 20
        bookmarks.addAll(listOf(BookmarkInt(5), BookmarkInt(10), BookmarkInt(15)))
        recyclingBookmark = true
        logger = PrintPaginatorLogger(minLevel = LogLevel.DEBUG)
    }

    init {
        paginator.core.snapshot
            .filter { it.isNotEmpty() }
            .onEach { _uiState.value = it }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            paginator.jump(BookmarkInt(1))
        }
    }

    // 1. Forward pagination (loading indicator at bottom)
    fun loadNextPage() = viewModelScope.launch {
        try {
            paginator.goNextPage()
        } catch (e: FinalPageExceededException) {
            showError("No more pages")
        }
    }

    // 2. Backward pagination (loading indicator at top)
    fun loadPreviousPage() = viewModelScope.launch {
        paginator.goPreviousPage()
    }

    // 3. Jump to a user-specified page
    fun jumpToPage(page: Int) = viewModelScope.launch {
        try {
            paginator.jump(BookmarkInt(page))
        } catch (e: FinalPageExceededException) {
            showError("Page exceeds limit")
        }
    }

    // 4. Navigate bookmarks
    fun nextBookmark() = viewModelScope.launch { paginator.jumpForward() }
    fun prevBookmark() = viewModelScope.launch { paginator.jumpBack() }

    // 5. Swipe-to-refresh
    fun restart() = viewModelScope.launch { paginator.restart() }

    // 6. Retry on error
    fun retryPage(page: Int) = viewModelScope.launch {
        paginator.refresh(pages = listOf(page))
    }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }
}
```

Rendering in Compose:

```kotlin
@Composable
fun PaginatedList(pages: List<PageState<String>>) {
    LazyColumn {
        pages.forEach { pageState ->
            when {
                pageState.isSuccessState() -> {
                    items(pageState.data.size) { index ->
                        Text(pageState.data[index])
                    }
                }
                pageState.isProgressState() -> {
                    // Show cached data (if any) + loading indicator
                    items(pageState.data.size) { index ->
                        Text(pageState.data[index], color = Color.Gray)
                    }
                    item { CircularProgressIndicator() }
                }
                pageState.isErrorState() -> {
                    item {
                        ErrorCard(
                            message = pageState.exception.message,
                            onRetry = { retryPage(pageState.page) }
                        )
                    }
                }
                pageState.isEmptyState() -> {
                    item { Text("No data on page ${pageState.page}") }
                }
            }
        }
    }
}
```
