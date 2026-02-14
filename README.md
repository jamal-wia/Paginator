# Paginator

[![Release](https://jitpack.io/v/jamal-wia/Paginator.svg)](https://jitpack.io/#jamal-wia/Paginator) [![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## [**📲 Download Demo APK**](https://raw.githubusercontent.com/jamal-wia/Paginator/master/PaginatorDemo.apk)

**Paginator** is a powerful, flexible pagination library for Android (Kotlin) that goes far beyond
simple "load next page" patterns. It provides a full-featured page management system with support
for jumping to arbitrary pages, bidirectional navigation, bookmarks, page caching, element-level
CRUD, incomplete page handling, capacity management, and reactive state via Kotlin Flows.

---
## AI Docs - https://deepwiki.com/jamal-wia/Paginator
---

[Telegram Community](https://t.me/+0eeAM-EJpqgwNGZi) | [YouTube Tutorial (RU)](https://www.youtube.com/watch?v=YsUX7-FgKgA)

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
    - [PageState](#pagestate)
    - [MutablePaginator](#mutablepaginator)
    - [Context Window](#context-window)
    - [Bookmarks](#bookmarks)
    - [Capacity & Incomplete Pages](#capacity--incomplete-pages)
    - [Final Page Limit](#final-page-limit)
- [Navigation](#navigation)
    - [goNextPage](#gonextpage)
    - [goPreviousPage](#gopreviouspage)
    - [jump](#jump)
    - [jumpForward / jumpBack](#jumpforward--jumpback)
    - [restart](#restart)
    - [refresh](#refresh)
- [Reactive State](#reactive-state)
    - [Snapshot Flow](#snapshot-flow)
    - [Cache Flow](#cache-flow)
- [Element-Level Operations](#element-level-operations)
- [Custom PageState Subclasses](#custom-pagestate-subclasses)
- [Lock Flags](#lock-flags)
- [Logger](#logger)
- [Extension Functions](#extension-functions)
    - [PageState Extensions](#pagestate-extensions)
    - [Paginator Extensions](#paginator-extensions)
- [Full Example](#full-example)
- [API Reference](#api-reference)
- [License](#license)

---

## Features

- **Bidirectional pagination** -- navigate forward (`goNextPage`) and backward (`goPreviousPage`)
- **Jump to any page** -- jump to arbitrary pages with `jump(bookmark)`
- **Bookmark system** -- define bookmarks and cycle through them with `jumpForward` / `jumpBack`,
  with optional recycling (wrap-around)
- **Incomplete page handling** -- when the server returns fewer items than expected, the paginator
  detects this and re-requests the page on the next `goNextPage`, showing cached data with a loading
  indicator
- **Final page limit** -- set `finalPage` to enforce a maximum page boundary (typically from backend
  metadata), throwing `FinalPageExceededException` when exceeded
- **Page caching** -- loaded pages are cached in a sorted map for instant access
- **Reactive state** -- observe page changes via `snapshot` Flow (visible pages) or `asFlow()` (
  entire cache)
- **Element-level CRUD** -- get, set, add, remove, and replace individual elements within pages,
  with automatic page rebalancing
- **Capacity management** -- resize pages on the fly with automatic data redistribution
- **Custom PageState subclasses** -- extend `SuccessPage`, `ErrorPage`, `ProgressPage`, or
  `EmptyPage` with your own types via initializer lambdas
- **Lock flags** -- prevent specific operations at runtime (`lockJump`, `lockGoNextPage`,
  `lockGoPreviousPage`, `lockRestart`, `lockRefresh`)
- **Parallel loading** -- preload multiple pages concurrently with `loadOrGetPageState`
- **Pluggable logging** -- implement the `Logger` interface to receive detailed logs about
  navigation, state changes, and element-level operations. No logging by default (`NoOpLogger`)
- **Context window** -- the paginator tracks a contiguous range of successfully loaded pages (
  `startContextPage..endContextPage`), which defines the visible snapshot

---

## Installation

Add JitPack to your project-level `build.gradle.kts` (or `settings.gradle.kts`):

```kotlin
repositories {
    maven { setUrl("https://jitpack.io") }
}
```

Add the dependency to your module-level `build.gradle.kts`:

```kotlin
implementation("com.github.jamal-wia:Paginator:5.2.0")
```

---

## Quick Start

### Step 1: Create a Paginator

Create a `MutablePaginator` in your ViewModel or Presenter, providing a data source lambda:

```kotlin
class MyViewModel : ViewModel() {

    private val paginator = MutablePaginator<Item>(source = { page ->
        repository.loadPage(page.toInt())
    })
}
```

The `source` lambda receives a `UInt` page number and should return a `List<T>`.

### Step 2: Observe and Start

Subscribe to the `snapshot` Flow to receive UI updates, then start the paginator by jumping to the
first page:

```kotlin
init {
    paginator.snapshot
        .filter { it.isNotEmpty() }
        .onEach { pages -> updateUI(pages) }
        .flowOn(Dispatchers.Main)
        .launchIn(viewModelScope)

    viewModelScope.launch {
        paginator.jump(bookmark = BookmarkUInt(page = 1u))
    }
}
```

### Step 3: Navigate

```kotlin
// Load next page (triggered by scroll reaching the end)
fun loadMore() {
    viewModelScope.launch { paginator.goNextPage() }
}

// Load previous page (triggered by scroll reaching the top)
fun loadPrevious() {
    viewModelScope.launch { paginator.goPreviousPage() }
}
```

### Step 4: Release

When the paginator is no longer needed, release its resources:

```kotlin
override fun onCleared() {
    paginator.release()
    super.onCleared()
}
```

---

## Core Concepts

### PageState

`PageState<E>` is a sealed class representing the state of a single page. Every page has a
`page: UInt` number, `data: List<E>` items, and a unique `id: Long`.

| Type              | Description                                                                              |
|-------------------|------------------------------------------------------------------------------------------|
| `SuccessPage<T>`  | Successfully loaded page with non-empty data                                             |
| `EmptyPage<T>`    | Successfully loaded page with no data (extends `SuccessPage`)                            |
| `ProgressPage<T>` | Page currently being loaded. May contain cached data from a previous load                |
| `ErrorPage<T>`    | Page that failed to load. Carries the `exception` and may contain previously cached data |

All `PageState` subclasses are `open`, so you can create your own custom types:

```kotlin
class MyCustomProgress<T>(
    page: UInt,
    data: List<T>,
    val progressPercent: Int = 0
) : PageState.ProgressPage<T>(page, data)
```

### MutablePaginator

`MutablePaginator<T>` is the main class. It manages a cache of `PageState<T>` objects keyed by page
number, handles navigation logic, and emits state updates via Flows.

```kotlin
val paginator = MutablePaginator<String>(source = { page ->
    api.fetchItems(page.toInt())
})
```

The constructor takes a single `source` lambda -- a suspending function that loads data for a given
page. The receiver is the paginator itself, giving you access to its properties during loading.

### Context Window

The paginator maintains a **context window** defined by `startContextPage` and `endContextPage`.
This represents the contiguous range of successfully loaded pages visible to the user. The`snapshot`
Flow emits only pages within (and adjacent to) this window.

When you call `goNextPage`, the window expands forward. When you call `goPreviousPage`, it expands
backward. When you `jump`, the window resets to the target page and expands outward.

### Bookmarks

Bookmarks are predefined page targets for quick navigation:

```kotlin
paginator.bookmarks.addAll(
    listOf(
        BookmarkUInt(5u),
        BookmarkUInt(10u),
        BookmarkUInt(15u),
    )
)
paginator.recyclingBookmark = true // Wrap around when reaching the end
```

Navigate through bookmarks with:

- `jumpForward()` -- moves to the next bookmark
- `jumpBack()` -- moves to the previous bookmark

You can also implement the `Bookmark` interface for custom bookmark types.

### Capacity & Incomplete Pages

`capacity` defines the expected number of items per page (default: 20). This is critical for the
paginator to determine whether a page is **filled** (complete) or **incomplete**.

```kotlin
paginator.resize(capacity = 10, resize = false, silently = true)
```

When a page returns fewer items than `capacity`, the paginator considers it **incomplete**. On the
next `goNextPage` call, instead of advancing to a new page, the paginator re-requests the same page.
During this re-request, it creates a `ProgressPage` containing the previously cached data, so the UI
can show the existing items alongside a loading indicator.

This is useful when a backend occasionally returns partial results.

Set `capacity` to `UNLIMITED_CAPACITY` (0) to disable capacity checks entirely.

### Final Page Limit

Set `finalPage` to enforce an upper boundary on pagination:

```kotlin
paginator.finalPage = 20u // Typically from backend metadata
```

Any attempt to navigate beyond this page (via `goNextPage`, `jump`, etc.) throws
`FinalPageExceededException`:

```kotlin
try {
    paginator.goNextPage()
} catch (e: FinalPageExceededException) {
    showMessage("Reached page ${e.finalPage}, no more data")
}
```

---

## Navigation

### goNextPage

Loads the page after the current `endContextPage`. If the paginator hasn't started, it automatically
jumps to page 1.

```kotlin
suspend fun goNextPage(): PageState<T>
```

Behavior:

1. Expands `endContextPage` to find the true end of contiguous success pages
2. Determines the next page number
3. If the next page exceeds `finalPage`, throws `FinalPageExceededException`
4. If the next page is already a `ProgressPage`, returns immediately (deduplication)
5. Otherwise, sets a `ProgressPage`, loads from source, and updates the cache

### goPreviousPage

Loads the page before the current `startContextPage`. Requires the paginator to be started.

```kotlin
suspend fun goPreviousPage(): PageState<T>
```

Behavior mirrors `goNextPage` but in the backward direction. Shows a loading indicator at the top.

### jump

Jumps directly to any page by bookmark:

```kotlin
suspend fun jump(bookmark: Bookmark): Pair<Bookmark, PageState<T>>
```

If the target page is already cached as a filled success page, returns immediately without
reloading. Otherwise, resets the context window to the target page and loads it.

### jumpForward / jumpBack

Navigate through the bookmark list:

```kotlin
suspend fun jumpForward(): Pair<Bookmark, PageState<T>>?  // null if no more bookmarks
suspend fun jumpBack(): Pair<Bookmark, PageState<T>>?     // null if no more bookmarks
```

When `recyclingBookmark = true`, the iterator wraps around.

### restart

Clears all cached pages except page 1's structure, resets the context to page 1, and reloads it:

```kotlin
suspend fun restart()
```

Ideal for swipe-to-refresh.

### refresh

Reloads specific pages in parallel without clearing the cache:

```kotlin
suspend fun refresh(pages: List<UInt>)
```

Sets all specified pages to `ProgressPage` (preserving cached data), then reloads them concurrently.
Use the extension `refreshAll()` to refresh every cached page.

---

## Reactive State

### Snapshot Flow

The primary way to observe the paginator's visible state:

```kotlin
val snapshot: Flow<List<PageState<T>>>
```

Emits a list of `PageState` objects within the context window whenever a navigation action
completes. This is what your UI should collect.

```kotlin
paginator.snapshot
    .onEach { pages -> adapter.submitList(pages) }
    .launchIn(scope)
```

### Cache Flow

For advanced use cases, observe the entire cache:

```kotlin
val cacheFlow: Flow<Map<UInt, PageState<T>>> = paginator.asFlow()
```

This emits the complete cache map (all pages, including those outside the context window).

---

## Element-Level Operations

The paginator supports CRUD operations on individual items within pages:

```kotlin
// Get an element
val item: T? = paginator.getElement(page = 3u, index = 0)

// Set/replace an element
paginator.setElement(element = updatedItem, page = 3u, index = 0)

// Remove an element (auto-rebalances pages)
val removed: T = paginator.removeElement(page = 3u, index = 2)

// Add elements (overflows cascade to next pages)
paginator.addAllElements(
    elements = listOf(newItem),
    targetPage = 3u,
    index = 0
)

// Replace all matching elements across all pages
paginator.replaceAllElement(
    providerElement = { current, _, _ -> current.copy(read = true) },
    predicate = { current, _, _ -> current.id == targetId }
)
```

When removing elements causes a page to drop below `capacity`, items are pulled from the next page
to fill the gap. When adding elements causes overflow beyond `capacity`, excess items cascade to
subsequent pages.

---

## Custom PageState Subclasses

You can create custom `PageState` subclasses and use them via the initializer lambdas:

```kotlin
// Custom progress page with additional metadata
class DetailedProgress<T>(
    page: UInt,
    data: List<T>,
    val source: String = "network"
) : PageState.ProgressPage<T>(page, data)

// Register the custom initializer
paginator.initializerProgressPage = { page, data ->
    DetailedProgress(page = page, data = data, source = "api")
}
```

Available initializer properties:

- `initializerProgressPage: (page: UInt, data: List<T>) -> ProgressPage<T>`
- `initializerSuccessPage: (page: UInt, data: List<T>) -> SuccessPage<T>`
- `initializerEmptyPage: (page: UInt, data: List<T>) -> EmptyPage<T>`
- `initializerErrorPage: (exception: Exception, page: UInt, data: List<T>) -> ErrorPage<T>`

Use `isRealProgressState(MyCustomProgress::class)` and similar extension functions to check for
specific subclasses with smart-casting.

---

## Lock Flags

Prevent specific operations at runtime:

| Flag                 | Blocks                            | Exception                          |
|----------------------|-----------------------------------|------------------------------------|
| `lockJump`           | `jump`, `jumpForward`, `jumpBack` | `JumpWasLockedException`           |
| `lockGoNextPage`     | `goNextPage`                      | `GoNextPageWasLockedException`     |
| `lockGoPreviousPage` | `goPreviousPage`                  | `GoPreviousPageWasLockedException` |
| `lockRestart`        | `restart`                         | `RestartWasLockedException`        |
| `lockRefresh`        | `refresh`, `refreshAll`           | `RefreshWasLockedException`        |

```kotlin
paginator.lockGoNextPage = true // Temporarily prevent forward pagination
```

All locks are reset to `false` on `release()`.

---

## Logger

The paginator supports pluggable logging via the `Logger` interface. By default, no logging is
performed (`NoOpLogger`). Implement the interface and assign it to `paginator.logger` to receive
logs about navigation, state changes, and element-level operations.

### Logger Interface

```kotlin
interface Logger {
    fun log(tag: String, message: String)
}
```

### Usage

```kotlin
import android.util.Log
import com.jamal_aliev.paginator.logger.Logger

object AndroidLogger : Logger {
    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}

val paginator = MutablePaginator<String>(source = { page ->
    api.fetchItems(page.toInt())
}).apply {
    logger = AndroidLogger
}
```

### Logged Operations

| Operation        | Example message                                   |
|------------------|---------------------------------------------------|
| `jump`           | `jump: page=5`                                    |
| `jumpForward`    | `jumpForward: recycling=true`                     |
| `jumpBack`       | `jumpBack: recycling=false`                       |
| `goNextPage`     | `goNextPage: page=3 result=SuccessPage`           |
| `goPreviousPage` | `goPreviousPage: page=1 result=SuccessPage`       |
| `restart`        | `restart`                                         |
| `refresh`        | `refresh: pages=[1, 2, 3]`                        |
| `setState`       | `setState: page=2 type=SuccessPage silently=false` |
| `setElement`     | `setElement: page=1 index=0`                      |
| `removeElement`  | `removeElement: page=2 index=3`                   |
| `addAllElements` | `addAllElements: targetPage=1 index=0 count=5`    |
| `removeState`    | `removeState: page=3`                             |
| `resize`         | `resize: capacity=10 resize=true`                 |
| `release`        | `release`                                         |

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
pageA gap pageB   // UInt distance between page numbers
```

### Paginator Extensions

```kotlin
// Search for elements
paginator.indexOfFirst { it.id == targetId }    // Returns Pair<UInt, Int>? (page, index)
paginator.indexOfLast { it.name == "test" }     // Search in reverse
paginator.getElement { it.id == targetId }      // Get first matching element

// Modify elements
paginator.setElement(updatedItem) { it.id == targetId }
paginator.removeElement { it.id == targetId }
paginator.addElement(newItem)  // Append to last page

// Iteration
paginator.foreEach { pageState -> /* ... */ }
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

    private val paginator = MutablePaginator<String>(source = { page ->
        repository.loadPage(page.toInt())
    }).apply {
        resize(capacity = 5, resize = false, silently = true)
        finalPage = 20u
        bookmarks.addAll(listOf(BookmarkUInt(5u), BookmarkUInt(10u), BookmarkUInt(15u)))
        recyclingBookmark = true
        logger = object : Logger {
            override fun log(tag: String, message: String) {
                Log.d(tag, message)
            }
        }
    }

    init {
        paginator.snapshot
            .filter { it.isNotEmpty() }
            .onEach { _uiState.value = it }
            .flowOn(Dispatchers.Main)
            .launchIn(viewModelScope)

        viewModelScope.launch {
            paginator.jump(BookmarkUInt(1u))
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
    fun jumpToPage(page: UInt) = viewModelScope.launch {
        try {
            paginator.jump(BookmarkUInt(page))
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
    fun retryPage(page: UInt) = viewModelScope.launch {
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

---

## API Reference

### MutablePaginator Properties

| Property              | Type                                            | Description                                     |
|-----------------------|-------------------------------------------------|-------------------------------------------------|
| `source`              | `suspend MutablePaginator<T>.(UInt) -> List<T>` | Data source lambda                              |
| `logger`              | `Logger`                                        | Logging interface (`NoOpLogger` by default)     |
| `capacity`            | `Int` (read-only, set via `resize()`)           | Expected items per page                         |
| `isCapacityUnlimited` | `Boolean`                                       | `true` if `capacity == 0`                       |
| `pages`               | `List<UInt>`                                    | All cached page numbers (sorted)                |
| `pageStates`          | `List<PageState<T>>`                            | All cached page states (sorted)                 |
| `size`                | `Int`                                           | Number of cached pages                          |
| `startContextPage`    | `UInt`                                          | Left boundary of visible context                |
| `endContextPage`      | `UInt`                                          | Right boundary of visible context               |
| `isStarted`           | `Boolean`                                       | `true` if context pages are set                 |
| `finalPage`           | `UInt`                                          | Maximum allowed page (default `UInt.MAX_VALUE`) |
| `bookmarks`           | `MutableList<Bookmark>`                         | Bookmark list (default: page 1)                 |
| `recyclingBookmark`   | `Boolean`                                       | Wrap-around bookmark iteration                  |
| `snapshot`            | `Flow<List<PageState<T>>>`                      | Visible page states flow                        |
| `lockJump`            | `Boolean`                                       | Lock jump operations                            |
| `lockGoNextPage`      | `Boolean`                                       | Lock forward navigation                         |
| `lockGoPreviousPage`  | `Boolean`                                       | Lock backward navigation                        |
| `lockRestart`         | `Boolean`                                       | Lock restart                                    |
| `lockRefresh`         | `Boolean`                                       | Lock refresh                                    |

### MutablePaginator Methods

| Method                                   | Returns                         | Description               |
|------------------------------------------|---------------------------------|---------------------------|
| `jump(bookmark)`                         | `Pair<Bookmark, PageState<T>>`  | Jump to a page            |
| `jumpForward()`                          | `Pair<Bookmark, PageState<T>>?` | Next bookmark             |
| `jumpBack()`                             | `Pair<Bookmark, PageState<T>>?` | Previous bookmark         |
| `goNextPage()`                           | `PageState<T>`                  | Load next page            |
| `goPreviousPage()`                       | `PageState<T>`                  | Load previous page        |
| `restart()`                              | `Unit`                          | Reset and reload page 1   |
| `refresh(pages)`                         | `Unit`                          | Reload specific pages     |
| `loadOrGetPageState(page, forceLoading)` | `PageState<T>`                  | Load or get cached page   |
| `getStateOf(page)`                       | `PageState<T>?`                 | Get cached page state     |
| `setState(state, silently)`              | `Unit`                          | Set a page state          |
| `removeState(page, silently)`            | `PageState<T>?`                 | Remove a page             |
| `getElement(page, index)`                | `T?`                            | Get element by position   |
| `setElement(element, page, index)`       | `Unit`                          | Replace element           |
| `removeElement(page, index)`             | `T`                             | Remove element            |
| `addAllElements(elements, page, index)`  | `Unit`                          | Insert elements           |
| `replaceAllElement(provider, predicate)` | `Unit`                          | Bulk replace              |
| `isFilledSuccessState(state)`            | `Boolean`                       | Check if page is complete |
| `snapshot(pageRange?)`                   | `Unit`                          | Emit snapshot             |
| `scan(pagesRange)`                       | `List<PageState<T>>`            | Get pages in range        |
| `walkWhile(pivot, next, predicate)`      | `PageState<T>?`                 | Traverse pages            |
| `findNearContextPage(start, end)`        | `Unit`                          | Find nearest context      |
| `asFlow()`                               | `Flow<Map<UInt, PageState<T>>>` | Full cache flow           |
| `resize(capacity, resize, silently)`     | `Unit`                          | Change capacity           |
| `release(capacity, silently)`            | `Unit`                          | Full reset                |

### Operators

| Operator                 | Description          |
|--------------------------|----------------------|
| `paginator[page]`        | Get page state       |
| `paginator[page, index]` | Get element          |
| `paginator += pageState` | Set page state       |
| `paginator -= page`      | Remove page          |
| `page in paginator`      | Check if page exists |

---

## License

```
The MIT License (MIT)

Copyright (c) 2023 Jamal Aliev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
