# Paginator

[![Maven Central](https://img.shields.io/maven-central/v/io.github.jamal-wia/paginator)](https://central.sonatype.com/artifact/io.github.jamal-wia/paginator) [![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin)
![Android](https://img.shields.io/badge/target-Android-green)
![JVM](https://img.shields.io/badge/target-JVM-blue)
![iOS](https://img.shields.io/badge/target-iOS-lightgrey)

## [**📲 Download Demo APK**](https://raw.githubusercontent.com/jamal-wia/Paginator/master/PaginatorDemo.apk)

**Paginator** is a powerful, flexible pagination library for **Kotlin Multiplatform (KMP)** that goes far beyond
simple "load next page" patterns. It provides a full-featured page management system with support
for jumping to arbitrary pages, bidirectional navigation, bookmarks, page caching, element-level
CRUD, incomplete page handling, capacity management, and reactive state via Kotlin Flows.

Built entirely with pure Kotlin and without platform-specific dependencies, 
Paginator can be seamlessly used across all layers of an application 
— from data to domain to presentation — while preserving Clean Architecture principles and proper layer separation.

**Supported targets:** Android · JVM · iosX64 · iosArm64 · iosSimulatorArm64

---
## AI Docs - https://deepwiki.com/jamal-wia/Paginator
---

[Telegram Community](https://t.me/+0eeAM-EJpqgwNGZi)

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
    - [PageState](#pagestate)
    - [Paginator vs MutablePaginator](#paginator-vs-mutablepaginator)
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
- [Dirty Pages](#dirty-pages)
- [Reactive State](#reactive-state)
    - [Snapshot Flow](#snapshot-flow)
    - [Cache Flow](#cache-flow)
- [Element-Level Operations](#element-level-operations)
- [Custom PageState Subclasses](#custom-pagestate-subclasses)
- [State Serialization](#state-serialization)
- [Transaction (Atomic Operations)](#transaction-atomic-operations)
- [Lock Flags](#lock-flags)
- [Prefetch (Auto-Pagination on Scroll)](#prefetch-auto-pagination-on-scroll)
- [Cache Eviction Strategies](#cache-eviction-strategies)
    - [LruPagingCore](#lrupagingcore)
    - [FifoPagingCore](#fifopagingcore)
    - [TtlPagingCore](#ttlpagingcore)
    - [SlidingWindowPagingCore](#slidingwindowpagingcore)
    - [Eviction Listener](#eviction-listener)
    - [Custom Strategies](#custom-strategies)
- [Persistent Cache (L2)](#persistent-cache-l2)
- [Logger](#logger)
- [Extension Functions](#extension-functions)
    - [PageState Extensions](#pagestate-extensions)
    - [Paginator Extensions](#paginator-extensions)
- [Full Example](#full-example)
- [API Reference](#api-reference)
- [Releasing a New Version](#releasing-a-new-version)
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
- **Cache eviction strategies** -- pluggable eviction via decorator subclasses of `PagingCore`:
  LRU, FIFO, TTL, and Sliding Window (context-only). Eviction listener callback for reacting to
  page removal
- **Reactive state** -- observe page changes via `snapshot` Flow (visible pages) or `asFlow()` (
  entire cache)
- **Element-level CRUD** -- get, set, add, remove, and replace individual elements within pages,
  with automatic page rebalancing
- **Capacity management** -- resize pages on the fly with automatic data redistribution
- **Custom PageState subclasses** -- extend `SuccessPage`, `ErrorPage`, `ProgressPage`, or
  `EmptyPage` with your own types via initializer lambdas
- **Dirty pages** -- mark pages as "dirty" so they are automatically refreshed (fire-and-forget) on
  the next navigation (`goNextPage`, `goPreviousPage`, `jump`). CRUD operations can also mark pages
  dirty via the `isDirty` flag
- **Two-tier API** -- `Paginator` (read-only navigation, dirty tracking, release) and
  `MutablePaginator` (element-level CRUD, resize, public `setState`)
- **Lock flags** -- prevent specific operations at runtime (`lockJump`, `lockGoNextPage`,
  `lockGoPreviousPage`, `lockRestart`, `lockRefresh`)
- **Scroll-based prefetch** -- `PaginatorPrefetchController` monitors scroll position and
  automatically loads the next/previous page before the user reaches the edge of content
- **Parallel loading** -- preload multiple pages concurrently with `loadOrGetPageState`
- **Pluggable logging** -- implement the `PaginatorLogger` interface to receive detailed logs about
  navigation, state changes, and element-level operations. No logging by default (`null`)
- **State serialization** -- save and restore the paginator's cache to/from JSON via
  `kotlinx.serialization`, enabling seamless recovery after process death on any KMP target
- **Transaction** -- execute a block of operations atomically with `transaction { }`. If any
  exception occurs (including coroutine cancellation), the entire paginator state is rolled back
- **Context window** -- the paginator tracks a contiguous range of successfully loaded pages (
  `startContextPage..endContextPage`), which defines the visible snapshot

---

## Installation

The library is published to **Maven Central**. No additional repository configuration needed.

### Kotlin Multiplatform (KMP)

Add the dependency to `commonMain` in your module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:paginator:7.7.0")
        }
    }
}
```

Gradle automatically resolves the correct platform artifact (`android`, `jvm`, `iosArm64`, etc.)
from the KMP metadata.

### Android-only project

```kotlin
dependencies {
    implementation("io.github.jamal-wia:paginator:7.7.0")
}
```

### JVM (Desktop / Server)

```kotlin
dependencies {
    implementation("io.github.jamal-wia:paginator-jvm:7.7.0")
}
```

---

## Quick Start

### Step 1: Create a Paginator

Create a `MutablePaginator` in your ViewModel or Presenter, providing a data source lambda:

```kotlin
class MyViewModel : ViewModel() {

    private val paginator = MutablePaginator<Item>(source = { page ->
        repository.loadPage(page)
    })
}
```

The `source` lambda receives an `Int` page number and should return a `List<T>`.

### Step 2: Observe and Start

Subscribe to the `snapshot` Flow to receive UI updates, then start the paginator by jumping to the
first page:

```kotlin
init {
    paginator.core.snapshot
        .filter { it.isNotEmpty() }
        .onEach { pages -> updateUI(pages) }
        .flowOn(Dispatchers.Main)
        .launchIn(viewModelScope)

    viewModelScope.launch {
        paginator.jump(bookmark = BookmarkInt(page = 1))
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
`page: Int` number, `data: List<E>` items, and a unique `id: Long`.

| Type              | Description                                                                              |
|-------------------|------------------------------------------------------------------------------------------|
| `SuccessPage<T>`  | Successfully loaded page with non-empty data                                             |
| `EmptyPage<T>`    | Successfully loaded page with no data (extends `SuccessPage`)                            |
| `ProgressPage<T>` | Page currently being loaded. May contain cached data from a previous load                |
| `ErrorPage<T>`    | Page that failed to load. Carries the `exception` and may contain previously cached data |

All `PageState` subclasses are `open`, so you can create your own custom types:

```kotlin
class MyCustomProgress<T>(
    page: Int,
    data: List<T>,
    val progressPercent: Int = 0
) : PageState.ProgressPage<T>(page, data)
```

### Paginator vs MutablePaginator

The library provides two classes with different levels of access:

| | `Paginator<T>` | `MutablePaginator<T>` |
|---|---|---|
| **Role** | Read-only base class | Full-featured mutable extension |
| **Navigation** | `jump`, `goNextPage`, `goPreviousPage`, `restart`, `refresh` | Inherits all from `Paginator` |
| **State access** | `getStateOf`, `getElement`, `scan`, `snapshot` | `setState` (public), `removeState` |
| **CRUD** | -- | `setElement`, `removeElement`, `addAllElements`, `replaceAllElements` |
| **Capacity** | Read-only `capacity` | `core.resize()` |
| **Dirty pages** | `markDirty`, `clearDirty`, `isDirty` | Inherits + `isDirty` param on CRUD ops |
| **Transaction** | `transaction { }` | Inherits |
| **Lifecycle** | `release()` | Inherits |

**When to use `Paginator`:** Use it when you only need to navigate and observe pages (e.g., a
read-only list screen). It keeps `setState` protected, preventing accidental cache mutations from
outside the class hierarchy.

**When to use `MutablePaginator`:** Use it when you need element-level CRUD, capacity resizing, or
direct state manipulation. Most use cases will use this class.

```kotlin
// Most common: full-featured paginator
val paginator = MutablePaginator<String>(source = { page ->
    api.fetchItems(page)
})

// Read-only: only navigation, no element mutations
val readOnlyPaginator = Paginator<String>(source = { page ->
    api.fetchItems(page)
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
        BookmarkInt(5),
        BookmarkInt(10),
        BookmarkInt(15),
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
paginator.core.resize(capacity = 10, resize = false, silently = true)
```

When a page returns fewer items than `capacity`, the paginator considers it **incomplete**. On the
next `goNextPage` call, instead of advancing to a new page, the paginator re-requests the same page.
During this re-request, it creates a `ProgressPage` containing the previously cached data, so the UI
can show the existing items alongside a loading indicator.

This is useful when a backend occasionally returns partial results.

Set `capacity` to `UNLIMITED_CAPACITY` (0) to disable capacity checks entirely.

#### coerceToCapacity

The paginator provides two overloaded `coerceToCapacity` functions that enforce the capacity limit
on data and page states. They are called automatically during all loading paths (`jump`,
`goNextPage`, `goPreviousPage`, `restart`, `refresh`) to guarantee that no page ever holds more
items than `capacity`.

```kotlin
// Trims a list to capacity. Always returns a MutableList.
paginator.coerceToCapacity(data: List<T>): List<T>

// Trims a PageState's data to capacity via copy(). Returns the same instance if no trim needed.
paginator.coerceToCapacity(state: PageState<T>): PageState<T>
```

When `capacity` is set to `UNLIMITED_CAPACITY`, both functions return their input unchanged.

### Final Page Limit

Set `finalPage` to enforce an upper boundary on pagination:

```kotlin
paginator.finalPage = 20 // Typically from backend metadata
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
suspend fun refresh(pages: List<Int>)
```

Sets all specified pages to `ProgressPage` (preserving cached data), then reloads them concurrently.
Use the extension `refreshAll()` to refresh every cached page.

---

## Dirty Pages

Pages can be marked as **dirty** to indicate that their data is stale and needs to be refreshed.
When a navigation function (`jump`, `goNextPage`, `goPreviousPage`) completes, it automatically
checks for dirty pages within the current context window (`startContextPage..endContextPage`) and
launches a **fire-and-forget** refresh for them in parallel.

### Marking Pages Dirty

```kotlin
// Mark a single page
paginator.core.markDirty(3)

// Mark multiple pages
paginator.core.markDirty(listOf(1, 2, 3))

// CRUD operations can also mark the affected page as dirty
paginator.setElement(updatedItem, page = 3, index = 0, isDirty = true)
paginator.removeElement(page = 3, index = 2, isDirty = true)
paginator.addAllElements(listOf(newItem), targetPage = 3, index = 0, isDirty = true)
```

### Clearing Dirty Flags

```kotlin
// Clear a single page
paginator.core.clearDirty(3)

// Clear multiple pages
paginator.core.clearDirty(listOf(1, 2))

// Clear all dirty flags
paginator.core.clearAllDirty()
```

Dirty flags are also automatically cleared:
- After `refresh()` completes for the refreshed pages
- After `release()` resets the paginator

### Querying Dirty State

```kotlin
paginator.core.isDirty(3)     // true if page 3 is dirty
paginator.core.dirtyPages      // Set<Int> snapshot of all dirty page numbers
```

### How It Works

When navigation completes (e.g., `goNextPage` loads page 5 successfully):

1. The paginator checks `dirtyPages` for pages in `startContextPage..endContextPage`
2. If any dirty pages are found, they are removed from the dirty set
3. A `refresh(pages = dirtyInContext)` is launched in a separate coroutine (fire-and-forget)
4. The navigation function returns immediately -- it does **not** wait for the dirty refresh

This ensures the user sees the navigation result instantly while stale pages are silently refreshed
in the background.

---

## Reactive State

### Snapshot Flow

The primary way to observe the paginator's visible state:

```kotlin
val snapshot: Flow<List<PageState<T>>> = paginator.core.snapshot
```

Emits a list of `PageState` objects within the context window whenever a navigation action
completes. This is what your UI should collect.

```kotlin
paginator.core.snapshot
    .onEach { pages -> adapter.submitList(pages) }
    .launchIn(scope)
```

### Cache Flow

For advanced use cases, observe the entire cache:

```kotlin
val cacheFlow: Flow<List<PageState<T>>> = paginator.core.asFlow()
```

This emits the complete cache list (all pages, including those outside the context window).

---

## Element-Level Operations

The paginator supports CRUD operations on individual items within pages:

```kotlin
// Get an element
val item: T? = paginator.core.getElement(page = 3, index = 0)

// Set/replace an element
paginator.setElement(element = updatedItem, page = 3, index = 0)

// Remove an element (auto-rebalances pages)
val removed: T = paginator.removeElement(page = 3, index = 2)

// Add elements (overflows cascade to next pages)
paginator.addAllElements(
    elements = listOf(newItem),
    targetPage = 3,
    index = 0
)

// Replace all matching elements across all pages
paginator.replaceAllElements(
    providerElement = { current, _, _ -> current.copy(read = true) },
    predicate = { current, _, _ -> current.id == targetId }
)
```

When removing elements causes a page to drop below `capacity`, items are pulled from the next page
to fill the gap. When adding elements causes overflow beyond `capacity`, excess items cascade to
subsequent pages.

All mutating operations (`setElement`, `removeElement`, `addAllElements`) accept an optional
`isDirty: Boolean = false` parameter. When set to `true`, the affected page is marked as dirty and
will be automatically refreshed on the next navigation (see [Dirty Pages](#dirty-pages)).

---

## Custom PageState Subclasses

You can create custom `PageState` subclasses and use them via the initializer lambdas:

```kotlin
// Custom progress page with additional metadata
class DetailedProgress<T>(
    page: Int,
    data: List<T>,
    val source: String = "network"
) : PageState.ProgressPage<T>(page, data)

// Register the custom initializer
paginator.core.initializerProgressPage = { page, data ->
    DetailedProgress(page = page, data = data, source = "api")
}
```

Available initializer properties on `paginator.core`:

- `initializerProgressPage: (page: Int, data: List<T>) -> ProgressPage<T>`
- `initializerSuccessPage: (page: Int, data: List<T>) -> SuccessPage<T>`
- `initializerEmptyPage: (page: Int, data: List<T>) -> EmptyPage<T>`
- `initializerErrorPage: (exception: Exception, page: Int, data: List<T>) -> ErrorPage<T>`

Use `isRealProgressState(MyCustomProgress::class)` and similar extension functions to check for
specific subclasses with smart-casting.

### PlaceholderProgressPage

The library includes a built-in `PlaceholderProgressPage<T>` -- a `ProgressPage` subclass that
appends placeholder objects to the data list during loading. This is useful for showing skeleton
UI items (shimmers) alongside any previously cached data while a page is being loaded.

```kotlin
class PlaceholderProgressPage<T>(
    override val page: Int,
    override val data: List<T>,   // must be a MutableList
    placeholderCapacity: Int,
) : PageState.ProgressPage<T>(page, data)
```

During construction, `placeholderCapacity` instances of `PlaceholderProgressPage.Placeholder` are
appended to `data`. The `data` parameter **must** be a `MutableList`; passing an immutable list
throws `IllegalArgumentException`.

#### Setup via core initializer (applies to all navigation)

```kotlin
paginator.core.initializerProgressPage = { page, data ->
    PlaceholderProgressPage(
        page = page,
        data = data.toMutableList(),
        placeholderCapacity = paginator.core.capacity,
    )
}
```

#### Per-call override (applies to a single navigation)

```kotlin
paginator.jump(
    bookmark = BookmarkInt(1),
    initProgressState = { page, data ->
        PlaceholderProgressPage(
            page = page,
            data = data.toMutableList(),
            placeholderCapacity = 10,
        )
    },
)
```

#### Rendering in UI

In your adapter or Compose list, check each item for the `Placeholder` type to render a skeleton:

```kotlin
// Jetpack Compose
items(pageState.data) { item ->
    if (item is PlaceholderProgressPage.Placeholder) {
        ShimmerPlaceholder()
    } else {
        ItemCard(item as Article)
    }
}
```

```kotlin
// RecyclerView — getItemViewType
override fun getItemViewType(position: Int): Int {
    return if (items[position] is PlaceholderProgressPage.Placeholder) {
        VIEW_TYPE_PLACEHOLDER
    } else {
        VIEW_TYPE_ITEM
    }
}
```

> **Note:** If `placeholderCapacity` exceeds `paginator.core.capacity`, the
> `coerceToCapacity` function automatically trims the data to fit within the capacity limit.
> With `UNLIMITED_CAPACITY`, placeholders are never trimmed.

---

## State Serialization

The paginator supports saving and restoring its full state via `kotlinx.serialization`. This is
essential for surviving Android/iOS process death -- when the system kills your app, you can save the
paginator state to `SavedStateHandle` or a file and restore it when the user returns.

There are two levels of serialization:

- **Paginator-level** (recommended) — saves everything: cache, context window, capacity, dirty
  pages, `finalPage`, bookmarks, bookmark position, lock flags
- **PagingCore-level** — saves only the cache layer (cache, context window, capacity, dirty pages)

### Setup

Your element type `T` must be annotated with `@Serializable`:

```kotlin
@Serializable
data class Article(val id: Long, val title: String, val body: String)
```

### Saving State (Paginator-level)

Use `saveStateToJson` to serialize the entire Paginator state into a JSON string. This is a
`suspend` function that acquires the navigation mutex for thread-safety:

```kotlin
val json: String = paginator.saveStateToJson(Article.serializer())
```

To save only pages within the current context window (reduces snapshot size):

```kotlin
val json: String = paginator.saveStateToJson(Article.serializer(), contextOnly = true)
```

Persist it using whatever mechanism fits your platform:

```kotlin
// Android — SavedStateHandle (survives process death)
savedStateHandle["paginator_state"] = json

// Any KMP target — file storage
// (use expect/actual or a KMP file I/O library)
```

### Restoring State (Paginator-level)

Use `restoreStateFromJson` to rebuild the full state from a previously saved JSON string.
The snapshot is validated before restoration — invalid data throws `IllegalArgumentException`:

```kotlin
val json: String? = savedStateHandle["paginator_state"]
if (json != null) {
    paginator.restoreStateFromJson(json, Article.serializer())
}
```

After restoration, the paginator is ready to use immediately — the `snapshot` flow emits the
restored pages, and navigation (`goNextPage`, `goPreviousPage`, `jump`) works as normal.

### What Gets Serialized

#### Paginator-level (`PaginatorSnapshot`)

| Included                        | Not included (re-initialize in code)  |
|---------------------------------|---------------------------------------|
| All cached page data            | `source` lambda                       |
| Context window boundaries       | Logger                                |
| Capacity                        |                                       |
| Dirty page flags                |                                       |
| `finalPage`                     |                                       |
| Bookmarks & bookmark position   |                                       |
| Lock flags                      |                                       |
| Error messages from ErrorPages  |                                       |

#### PagingCore-level (`PagingCoreSnapshot`)

| Included                        | Not included (re-initialize in code)  |
|---------------------------------|---------------------------------------|
| All cached page data            | `source` lambda                       |
| Context window boundaries       | `finalPage`                           |
| Capacity                        | Bookmarks                             |
| Dirty page flags                | Lock flags                            |
| Error messages from ErrorPages  |                                       |

### How ErrorPage and ProgressPage Are Handled

`ErrorPage` and `ProgressPage` cannot be serialized as-is (`Exception` is not serializable, and
in-flight loads are meaningless after process death). During save, these pages are converted:

- Their **cached data is preserved** (e.g., stale data shown before the error/reload)
- The **error message** (`exception.message`) is preserved in the `errorMessage` field of
  `PageEntry`, so the UI can display the error reason after restoration
- They are restored as `SuccessPage` (or `EmptyPage` if data was empty)
- They are automatically **marked as dirty**, so the next navigation triggers a fire-and-forget
  refresh to re-fetch fresh data from the source

### Snapshot Validation

When restoring from a snapshot, the following validations are performed:

- `capacity` must be > 0
- `startContextPage` and `endContextPage` must be >= 0
- `startContextPage` must be <= `endContextPage` (unless both are 0)
- All page numbers must be >= 1
- No duplicate page numbers are allowed

Invalid snapshots throw `IllegalArgumentException`.

### Low-Level API (PagingCore)

For advanced use cases, you can work with `PagingCore` directly. These methods are **not**
`suspend` and do **not** acquire the mutex — ensure proper synchronization if calling
concurrently:

```kotlin
// Save to a snapshot object
val snapshot: PagingCoreSnapshot<Article> = paginator.core.saveState()

// Save only context window pages
val snapshot: PagingCoreSnapshot<Article> = paginator.core.saveState(contextOnly = true)

// Restore from a snapshot object
paginator.core.restoreState(snapshot)

// JSON round-trip
val json: String = paginator.core.saveStateToJson(Article.serializer())
paginator.core.restoreStateFromJson(json, Article.serializer())
```

### Object-Level API (Paginator)

For custom serialization formats or non-JSON persistence:

```kotlin
// Save to a snapshot object (suspend, thread-safe)
val snapshot: PaginatorSnapshot<Article> = paginator.saveState()

// Restore from a snapshot object (suspend, thread-safe)
paginator.restoreState(snapshot)
```

---

## Transaction (Atomic Operations)

The `transaction` method executes a block of operations atomically: if anything inside the block
throws an exception (including `CancellationException`), the paginator's entire state is rolled
back to the point before the block was entered.

```kotlin
paginator.transaction {
    (this as MutablePaginator).setElement(updatedItem, page = 1, index = 0)
    removeElement(page = 2, index = 3)
    addAllElements(listOf(newItem), targetPage = 3, index = 0)
    // If any operation fails, ALL changes are reverted
}
```

### Return Value

`transaction` returns whatever the block returns:

```kotlin
val removedItem: String = paginator.transaction {
    (this as MutablePaginator).removeElement(page = 1, index = 0)
}
```

### What Gets Rolled Back

On failure, **everything** is restored to the pre-transaction state:

| State                          | Rolled back |
|--------------------------------|-------------|
| Cache (all page states & data) | Yes         |
| Context window boundaries      | Yes         |
| Capacity                       | Yes         |
| Dirty page flags               | Yes         |
| `finalPage`                    | Yes         |
| Bookmarks & bookmark position  | Yes         |
| Lock flags                     | Yes         |
| `recyclingBookmark`            | Yes         |

### Cancellation Safety

If the coroutine running the transaction is canceled, the rollback is performed inside
`withContext(NonCancellable)` to guarantee the state is fully restored before the
`CancellationException` propagates.

### Nesting

Nested `transaction` calls work as nested savepoints:

```kotlin
paginator.transaction {
    // outer changes...

    try {
        transaction {
            // inner changes...
            throw RuntimeException("inner failure")
        }
    } catch (e: RuntimeException) {
        // inner rolled back, outer changes still intact
    }

    // outer continues...
}
```

### Difference from `saveState` / `restoreState`

`transaction` uses an in-memory deep copy that **preserves exact `PageState` types**
(`ErrorPage`, `ProgressPage`, etc.). In contrast, `saveState`/`restoreState` are designed for
serialization and convert `ErrorPage`/`ProgressPage` to `SuccessPage`/`EmptyPage` (marking them
dirty for re-fetch). Use `transaction` for runtime atomicity; use `saveState`/`restoreState` for
persistence across process death.

### Navigation Inside Transactions

Navigation operations (`jump`, `goNextPage`, `goPreviousPage`, `restart`, `refresh`) work inside
`transaction` blocks without deadlock. The `navigationMutex` is not held during the block --
each navigation operation acquires and releases it independently.

```kotlin
paginator.transaction {
    jump(BookmarkInt(5), silentlyLoading = true, silentlyResult = true)
    goNextPage(silentlyLoading = true, silentlyResult = true)
    // If goNextPage fails, both the jump and goNextPage are reverted
}
```

### Optimistic Updates

A common pattern: apply a change immediately in the UI, make the server request, and roll back
automatically if the request fails.

```kotlin
data class Post(val id: Long, val title: String, val liked: Boolean, val likesCount: Int)

fun likePost(post: Post, page: Int, index: Int) = viewModelScope.launch {
    try {
        paginator.transaction {
            // 1. Optimistically apply the change — UI updates immediately
            (this as MutablePaginator).setElement(
                element = post.copy(liked = true, likesCount = post.likesCount + 1),
                page = page,
                index = index,
            )

            // 2. Send the request — if it throws, the setElement above is rolled back
            api.likePost(post.id)
        }
    } catch (e: Exception) {
        showError("Failed to like post")
        // No manual rollback needed — transaction already restored the original state
    }
}
```

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

## Prefetch (Auto-Pagination on Scroll)

`PaginatorPrefetchController` monitors scroll position and automatically calls `goNextPage()` /
`goPreviousPage()` **before** the user reaches the edge of loaded content. This eliminates manual
"load more" buttons and provides a seamless infinite-scroll experience.

The controller is **platform-agnostic** -- it receives visibility information via the `onScroll()`
method, so the same logic works with RecyclerView, LazyColumn, UITableView, or any other list.

### Creating a Controller

```kotlin
val prefetch = paginator.prefetchController(
    scope = viewModelScope,       // coroutine scope for prefetch jobs
    prefetchDistance = 10,         // start loading when 10 items from the edge
    enableBackwardPrefetch = true, // also prefetch when scrolling up (default: false)
)
```

The first `onScroll()` call is **calibration only** -- it records the initial position without
triggering any load, preventing a false positive when the list first appears.

### Android RecyclerView

```kotlin
recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
        val lm = rv.layoutManager as LinearLayoutManager
        prefetch.onScroll(
            firstVisibleIndex = lm.findFirstVisibleItemPosition(),
            lastVisibleIndex  = lm.findLastVisibleItemPosition(),
            totalItemCount    = adapter.itemCount,
        )
    }
})
```

### Jetpack Compose LazyColumn

```kotlin
val listState = rememberLazyListState()

LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo) {
    val info = listState.layoutInfo
    prefetch.onScroll(
        firstVisibleIndex = listState.firstVisibleItemIndex,
        lastVisibleIndex  = info.visibleItemsInfo.lastOrNull()?.index ?: 0,
        totalItemCount    = info.totalItemsCount,
    )
}
```

> **Important:** `totalItemCount` must reflect only **data items**. Do not include headers, footers,
> dividers, or loading indicators -- otherwise `prefetchDistance` will trigger inaccurately.

### Configuration

All properties can be changed at runtime:

| Property                 | Default                          | Description                                             |
|--------------------------|----------------------------------|---------------------------------------------------------|
| `prefetchDistance`       | *(required)*                     | Items from edge to trigger load. Must be > 0            |
| `enableBackwardPrefetch` | `false`                          | Enable `goPreviousPage()` on scroll up                  |
| `enabled`                | `true`                           | Master switch. `false` = `onScroll()` is a no-op        |
| `silentlyLoading`        | `true`                           | Suppress `ProgressPage` emission during prefetch        |
| `silentlyResult`         | `false`                          | Suppress snapshot emission when prefetched page arrives |
| `loadGuard`              | `{ _, _ -> true }`               | Guard callback passed to navigation functions           |
| `onPrefetchError`        | `null`                           | Callback for errors (excluding `CancellationException`) |

### Lifecycle Control

```kotlin
// Pause prefetching (e.g. modal is shown), in-flight jobs continue
prefetch.enabled = false

// Cancel in-flight jobs, but new scrolls can still trigger prefetch
prefetch.cancel()

// Full reset: cancel jobs + clear calibration.
// Next onScroll() becomes calibration again (no prefetch on first call).
// Use after jump() or restart() when the list contents change entirely.
prefetch.reset()
```

### Interaction with Other Mechanisms

- **Incomplete pages:** if `removeElement()` makes the last loaded page shorter than `capacity`,
  `goNextPage()` re-fetches that page instead of loading the next one -- this is standard paginator
  behavior and the prefetch controller respects it transparently.
- **Dirty pages:** CRUD operations with `isDirty = true` mark pages for refresh. The next prefetch
  navigation triggers a fire-and-forget refresh for all dirty pages in the context window.
- **Lock flags:** `lockGoNextPage` / `lockGoPreviousPage` block the corresponding prefetch direction.
  Unlocking mid-scroll resumes prefetching on the next `onScroll()` call.
- **`finalPage`:** forward prefetch stops when `endContextPage >= finalPage`. Changing `finalPage`
  at runtime immediately affects the next scroll check.
- **`resize` / `release`:** after `release()` or significant `resize()`, call `prefetch.reset()` to
  re-calibrate from the new list state.

---

## Cache Eviction Strategies

By default, `PagingCore` uses `DefaultPagingCache` -- every loaded page stays in memory
forever. For long-lived paginators or memory-constrained environments, pass a
**cache strategy** via the `cache` parameter of `PagingCore`:

```kotlin
val paginator = MutablePaginator<Item>(
    core = PagingCore(cache = LruPagingCache(maxSize = 50)),
    source = { page -> api.loadPage(page) }
)
```

Available strategies:

| Strategy                   | Eviction rule                     | Key parameter   |
|----------------------------|-----------------------------------|-----------------|
| `DefaultPagingCache`       | None -- unlimited cache           | --              |
| `LruPagingCache`           | Least recently used page          | `maxSize`       |
| `FifoPagingCache`          | First inserted page               | `maxSize`       |
| `TtlPagingCache`           | Pages older than TTL              | `ttl: Duration` |
| `SlidingWindowPagingCache` | Everything outside context window | `margin`        |

### LruPagingCache

Evicts the **least recently used** page when the cache exceeds `maxSize`. Both reads
(`getStateOf`, `getElement`) and writes (`setState`) count as "usage".

```kotlin
val paginator = MutablePaginator<Item>(
    core = PagingCore(
        cache = LruPagingCache(
            maxSize = 50,                   // max pages in cache
            protectContextWindow = true,    // never evict visible pages (default)
        )
    ),
    source = { page -> api.loadPage(page) }
)
```

| Parameter              | Default      | Description                                                   |
|------------------------|--------------|---------------------------------------------------------------|
| `maxSize`              | *(required)* | Maximum cached pages. Must be > 0                             |
| `protectContextWindow` | `true`       | Pages in `startContextPage..endContextPage` are never evicted |
| `evictionListener`     | `null`       | Callback when a page is evicted                               |

### FifoPagingCache

Evicts the **oldest inserted** page when the cache exceeds `maxSize`. Unlike LRU, reading
a page does **not** change its eviction priority -- only the original insertion order matters.

```kotlin
val paginator = MutablePaginator<Item>(
    core = PagingCore(cache = FifoPagingCache(maxSize = 30)),
    source = { page -> api.loadPage(page) }
)
```

Parameters are the same as `LruPagingCache`.

### TtlPagingCache

Evicts pages whose age exceeds a **time-to-live** duration. There is no `maxSize` --
the cache can hold any number of pages as long as they are fresh.

```kotlin
import kotlin.time.Duration.Companion.minutes

val paginator = MutablePaginator<Item>(
    core = PagingCore(
        cache = TtlPagingCache(
            ttl = 5.minutes,            // max page age
            refreshOnAccess = false,    // true = reading resets the timer
            protectContextWindow = true,
        )
    ),
    source = { page -> api.loadPage(page) }
)
```

| Parameter              | Default                | Description                                                |
|------------------------|------------------------|------------------------------------------------------------|
| `ttl`                  | *(required)*           | Maximum page age. Must be positive                         |
| `refreshOnAccess`      | `false`                | If `true`, `getStateOf` / `getElement` reset the TTL timer |
| `protectContextWindow` | `true`                 | Expired pages in the context window are kept               |
| `timeSource`           | `TimeSource.Monotonic` | Override for deterministic testing                         |
| `evictionListener`     | `null`                 | Callback when a page is evicted                            |

### SlidingWindowPagingCache

Keeps **only** pages within (or near) the current context window. After a `jump()` to a
distant page, all pages from the previous location are discarded immediately. This is the
most aggressive strategy -- ideal for memory-constrained environments.

```kotlin
val paginator = MutablePaginator<Item>(
    core = PagingCore(
        cache = SlidingWindowPagingCache(
            margin = 2,   // keep 2 extra pages beyond each edge of the context window
        )
    ),
    source = { page -> api.loadPage(page) }
)
```

| Parameter          | Default | Description                                               |
|--------------------|---------|-----------------------------------------------------------|
| `margin`           | `0`     | Pages to keep beyond each edge. `0` = strict window only  |
| `evictionListener` | `null`  | Callback when a page is evicted                           |

With `margin = 0`, the cache contains exactly `startContextPage..endContextPage`. With
`margin = 2`, it keeps `(startContextPage - 2)..(endContextPage + 2)`.

### Composing Strategies

Strategies implement `WrappablePagingCache<T>` and can be **chained** with the `+` operator.
The leftmost strategy is the outermost decorator -- its eviction policy takes priority.

```kotlin
// LRU(50) governs eviction count; TTL(5min) evicts stale pages from the inner cache
val cache = LruPagingCache<Item>(maxSize = 50) + TtlPagingCache(ttl = 5.minutes)

val paginator = MutablePaginator<Item>(
    core = PagingCore(cache = cache),
    source = { page -> api.loadPage(page) }
)
```

`+` is left-associative, so `a + b + c` builds the chain `a → b → c → DefaultPagingCache`:

```kotlin
val cache = LruPagingCache<Item>(maxSize = 50) +
            TtlPagingCache(ttl = 5.minutes) +
            SlidingWindowPagingCache(margin = 1)
```

### Eviction Listener

All strategies accept an optional `evictionListener` to react when a page is removed
from the cache (e.g., to persist data before it is lost):

```kotlin
val cache = LruPagingCache<Item>(
    maxSize = 20,
    evictionListener = CacheEvictionListener { evictedPage ->
        println("Evicted page ${evictedPage.page} with ${evictedPage.data.size} items")
    }
)
```

The listener is a `fun interface`, so a lambda works directly.

### Custom Strategies

Implement `PagingCache<T>` to define your own eviction logic. To participate in `+`
composition, also implement `WrappablePagingCache<T>` and use the `withLeaf()` helper
inside `replaceLeaf()`:

```kotlin
class MyCache<T>(
    private val cache: PagingCache<T> = DefaultPagingCache(),
    val myParam: Int,
) : PagingCache<T> by cache, WrappablePagingCache<T> {

    override fun replaceLeaf(newLeaf: PagingCache<T>): MyCache<T> =
        MyCache(cache = cache.withLeaf(newLeaf), myParam = myParam)

    override fun setState(state: PageState<T>, silently: Boolean) {
        cache.setState(state, silently)
        // your eviction logic here
    }

    override fun removeFromCache(page: Int): PageState<T>? = cache.removeFromCache(page)
    override fun clear() = cache.clear()
    override fun release(capacity: Int, silently: Boolean) = cache.release(capacity, silently)
}

// Composes with built-in strategies via +:
val cache = LruPagingCache<Item>(maxSize = 50) + MyCache(myParam = 42)
```

`cache.withLeaf(newLeaf)` recurses down the delegation chain and replaces the
`DefaultPagingCache` leaf with `newLeaf`, so your strategy works correctly at any depth.

---

## Persistent Cache (L2)

The paginator supports an optional **second-level persistent cache** that allows pages to survive
process death. While the in-memory `PagingCache` (L1) provides fast synchronous access, a
`PersistentPagingCache` (L2) stores pages in a durable backend (Room, SQLite, files, etc.).

The library ships only the interface -- the implementation is entirely up to you.

### How It Works

**Read path:** L1 (in-memory) → L2 (persistent) → source (network).
When a navigation method encounters a cache miss in L1, it checks L2 before calling the source
lambda. If L2 has the page, it is promoted back into L1 and returned immediately -- no loading
indicator, no network call.

**Write path:** After every successful source load, the resulting `SuccessPage` (or `EmptyPage`)
is automatically saved to L2.

### PersistentPagingCache Interface

```kotlin
interface PersistentPagingCache<T> {
    suspend fun save(state: PageState<T>)
    suspend fun saveAll(states: List<PageState<T>>) { states.forEach { save(it) } }
    suspend fun load(page: Int): PageState<T>?
    suspend fun loadAll(): List<PageState<T>>
    suspend fun remove(page: Int)
    suspend fun removeAll(pages: List<Int>) { pages.forEach { remove(it) } }
    suspend fun clear()
    suspend fun <R> transaction(block: suspend PersistentPagingCache<T>.() -> R): R = block()
}
```

All methods are `suspend`, enabling native integration with Room, SQLite, or any async storage.
Override `transaction` to wrap the block in a real DB transaction (e.g., Room's `withTransaction`).
Override `saveAll` / `removeAll` for batch-optimized storage.

### Usage

Provide your implementation when constructing the `PagingCore`:

```kotlin
val paginator = MutablePaginator(
    core = PagingCore(
        cache = LruPagingCache(maxSize = 50),
        persistentCache = RoomPagingCache(dao, json, serializer),
    ),
    source = { page -> api.loadPage(page) }
)
```

### Example Room Implementation

```kotlin
class RoomPagingCache(
    private val dao: PageDao,
    private val json: Json,
    private val serializer: KSerializer<MyItem>,
) : PersistentPagingCache<MyItem> {

    override suspend fun save(state: PageState<MyItem>) {
        val jsonData = json.encodeToString(ListSerializer(serializer), state.data)
        dao.upsert(PageEntity(page = state.page, data = jsonData, isEmpty = state.isEmptyState()))
    }

    override suspend fun load(page: Int): PageState<MyItem>? {
        val entity = dao.getPage(page) ?: return null
        val data = json.decodeFromString(ListSerializer(serializer), entity.data)
        return if (entity.isEmpty) EmptyPage(page, data)
        else SuccessPage(page, data.toMutableList())
    }

    override suspend fun loadAll(): List<PageState<MyItem>> =
        dao.getAllPages().map { load(it.page)!! }

    override suspend fun remove(page: Int) = dao.deletePage(page)

    override suspend fun clear() = dao.deleteAll()
}
```

### Persisting CRUD Changes

CRUD operations (`setElement`, `removeElement`, `addAllElements`, `replaceAllElements`,
`removeState`, `+=`) are synchronous and modify only L1. To flush those changes to L2, call
`flush()`:

```kotlin
paginator.setElement("updated", page = 1, index = 0)
paginator.removeElement(page = 2, index = 3)

// Flush all CRUD changes to L2 in one batch
paginator.flush()
```

Inside a `transaction`, CRUD changes are **auto-persisted** on success and **discarded** on
failure — no manual flush needed:

```kotlin
paginator.transaction {
    (this as MutablePaginator).setElement("a", page = 1, index = 0)
    removeElement(page = 2, index = 3)
    // If everything succeeds, changes are auto-persisted to L2.
    // If anything throws, L1 is rolled back and L2 remains unchanged.
}
```

The method tracks affected pages automatically (including cascading effects from rebalancing
and overflow). At flush time, pages still present in L1 as `SuccessPage` are saved; pages that
no longer exist in L1 are removed from L2.

### Lifecycle

- **`restart()`** does **not** clear the persistent cache. Fresh data from the source overwrites
  stale persistent entries via auto-persist.
- **`release()`** does **not** clear the persistent cache. L2 is meant to outlive the paginator
  instance.
- **Transaction rollback** does **not** affect the persistent cache (eventual consistency).
- **MutablePaginator CRUD** operations track affected pages internally. Call
  `flush()` to flush changes to L2 (automatic inside `transaction`).
- To manually clear L2, call `persistentCache.clear()` directly.

### Composing with Eviction Strategies

Persistent cache works alongside eviction strategies. When a page is evicted from L1 (e.g., by
`LruPagingCache`), it remains in L2. The next time that page is requested, it is restored from
L2 instead of making a network call:

```kotlin
val paginator = MutablePaginator(
    core = PagingCore(
        cache = LruPagingCache(maxSize = 20),     // L1: keep only 20 pages in memory
        persistentCache = MyRoomPagingCache(dao),  // L2: unlimited persistent storage
    ),
    source = { page -> api.loadPage(page) }
)
```

---

## Logger

The paginator supports pluggable logging via the `PaginatorLogger` interface. By default, no logging is
performed (logger is `null`). Implement the interface and assign it to `paginator.logger` to receive
logs about navigation, state changes, and element-level operations.

### PaginatorLogger Interface

```kotlin
interface PaginatorLogger {
    fun log(tag: String, message: String)
}
```

### Usage

```kotlin
import com.jamal_aliev.paginator.logger.PaginatorLogger

// Platform-agnostic (works on all KMP targets)
object ConsoleLogger : PaginatorLogger {
    override fun log(tag: String, message: String) {
        println("[$tag] $message")
    }
}

// Android-specific
object AndroidLogger : PaginatorLogger {
    override fun log(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }
}

val paginator = MutablePaginator<String>(source = { page ->
    api.fetchItems(page)
}).apply {
    logger = ConsoleLogger // or AndroidLogger on Android
}
```

### Logged Operations

| Operation                     | Example message                                              |
|-------------------------------|--------------------------------------------------------------|
| `jump`                        | `jump: page=5`                                               |
| `jumpForward`                 | `jumpForward: recycling=true`                                |
| `jumpBack`                    | `jumpBack: recycling=false`                                  |
| `goNextPage`                  | `goNextPage: page=3 result=SuccessPage`                      |
| `goPreviousPage`              | `goPreviousPage: page=1 result=SuccessPage`                  |
| `restart`                     | `restart`                                                    |
| `refresh`                     | `refresh: pages=[1, 2, 3]`                                   |
| `setState`                    | `setState: page=2`                                           |
| `setElement`                  | `setElement: page=1 index=0 isDirty=false`                   |
| `removeElement`               | `removeElement: page=2 index=3 isDirty=false`                |
| `addAllElements`              | `addAllElements: targetPage=1 index=0 count=5 isDirty=false` |
| `removeState`                 | `removeState: page=3`                                        |
| `resize`                      | `resize: capacity=10 resize=true`                            |
| `release`                     | `release`                                                    |
| `markDirty`                   | `markDirty: page=3`                                          |
| `clearDirty`                  | `clearDirty: page=3`                                         |
| `clearAllDirty`               | `clearAllDirty`                                              |
| `refreshDirtyPagesInContext`  | `refreshDirtyPagesInContext: pages=[2, 3]`                   |

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

    private val paginator = MutablePaginator<String>(source = { page ->
        repository.loadPage(page)
    }).apply {
        core.resize(capacity = 5, resize = false, silently = true)
        finalPage = 20
        bookmarks.addAll(listOf(BookmarkInt(5), BookmarkInt(10), BookmarkInt(15)))
        recyclingBookmark = true
        logger = object : PaginatorLogger {
            override fun log(tag: String, message: String) {
                println("[$tag] $message")
            }
        }
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

---

## API Reference

### Paginator Properties

**Direct `Paginator` properties:**

| Property              | Type                                    | Description                                     |
|-----------------------|-----------------------------------------|-------------------------------------------------|
| `source`              | `suspend Paginator<T>.(Int) -> List<T>` | Data source lambda                              |
| `logger`              | `PaginatorLogger?`                      | Logging interface (`null` by default)           |
| `finalPage`           | `Int`                                   | Maximum allowed page (default `Int.MAX_VALUE`)  |
| `bookmarks`           | `MutableList<Bookmark>`                 | Bookmark list (default: page 1)                 |
| `recyclingBookmark`   | `Boolean`                               | Wrap-around bookmark iteration                  |
| `lockJump`            | `Boolean`                               | Lock jump operations                            |
| `lockGoNextPage`      | `Boolean`                               | Lock forward navigation                         |
| `lockGoPreviousPage`  | `Boolean`                               | Lock backward navigation                        |
| `lockRestart`         | `Boolean`                               | Lock restart                                    |
| `lockRefresh`         | `Boolean`                               | Lock refresh                                    |

**Via `paginator.core` (`PagingCore`):**

| Property                  | Type                       | Description                                     |
|---------------------------|----------------------------|-------------------------------------------------|
| `core.snapshot`           | `Flow<List<PageState<T>>>` | Visible page states flow                        |
| `core.capacity`           | `Int`                      | Expected items per page                         |
| `core.isCapacityUnlimited`| `Boolean`                  | `true` if `capacity == 0`                       |
| `core.pages`              | `List<Int>`                | All cached page numbers (sorted)                |
| `core.states`             | `List<PageState<T>>`       | All cached page states (sorted)                 |
| `core.size`               | `Int`                      | Number of cached pages                          |
| `core.dirtyPages`         | `Set<Int>`                 | Snapshot of all dirty page numbers              |
| `core.startContextPage`   | `Int`                      | Left boundary of visible context                |
| `core.endContextPage`     | `Int`                      | Right boundary of visible context               |
| `core.isStarted`          | `Boolean`                  | `true` if context pages are set                 |

### Paginator Methods

**Direct `Paginator` methods:**

| Method                                   | Returns                         | Description                            |
|------------------------------------------|---------------------------------|----------------------------------------|
| `jump(bookmark)`                         | `Pair<Bookmark, PageState<T>>`  | Jump to a page                         |
| `jumpForward()`                          | `Pair<Bookmark, PageState<T>>?` | Next bookmark                          |
| `jumpBack()`                             | `Pair<Bookmark, PageState<T>>?` | Previous bookmark                      |
| `goNextPage()`                           | `PageState<T>`                  | Load next page                         |
| `goPreviousPage()`                       | `PageState<T>`                  | Load previous page                     |
| `restart()`                              | `Unit`                          | Reset and reload page 1                |
| `refresh(pages)`                         | `Unit`                          | Reload specific pages                  |
| `loadOrGetPageState(page, forceLoading)` | `PageState<T>`                  | Load or get cached page (inline)       |
| `release(capacity, silently)`            | `Unit`                          | Full reset                             |
| `transaction(block)`                     | `R`                             | Atomic block with rollback on failure  |
| `saveState(contextOnly)`                 | `PaginatorSnapshot<T>`          | Full state snapshot (suspend)          |
| `restoreState(snapshot, silently)`       | `Unit`                          | Restore full state (suspend)           |
| `saveStateToJson(serializer, json)`      | `String`                        | Save full state as JSON (suspend, ext) |
| `restoreStateFromJson(str, serializer)`  | `Unit`                          | Restore full state from JSON (suspend, ext) |

**Via `paginator.core` (`PagingCore`) methods:**

| Method                                        | Returns                    | Description                           |
|-----------------------------------------------|----------------------------|---------------------------------------|
| `core.getStateOf(page)`                       | `PageState<T>?`            | Get cached page state                 |
| `core.getElement(page, index)`                | `T?`                       | Get element by position               |
| `core.markDirty(page)` / `markDirty(pages)`  | `Unit`                     | Mark page(s) as dirty                 |
| `core.clearDirty(page)` / `clearDirty(pages)`| `Unit`                     | Remove dirty flag from page(s)        |
| `core.clearAllDirty()`                        | `Unit`                     | Remove all dirty flags                |
| `core.isDirty(page)`                          | `Boolean`                  | Check if page is dirty                |
| `core.isFilledSuccessState(state)`            | `Boolean`                  | Check if page is complete             |
| `core.snapshot(pageRange?)`                   | `Unit`                     | Emit snapshot manually                |
| `core.scan(pagesRange)`                       | `List<PageState<T>>`       | Get pages in range                    |
| `core.walkWhile(pivot, next, predicate)`      | `PageState<T>?`            | Traverse pages                        |
| `core.findNearContextPage(start, end)`        | `Unit`                     | Find nearest context                  |
| `core.asFlow()`                               | `Flow<List<PageState<T>>>` | Full cache flow                       |
| `core.resize(capacity, resize, silently)`     | `Unit`                     | Change capacity                       |
| `core.saveStateToJson(serializer)`            | `String`                   | Save core state as JSON (ext)         |
| `core.restoreStateFromJson(str, serializer)`  | `Unit`                     | Restore core state from JSON (ext)    |

**Operators (on `Paginator`):**

| Expression               | Description               |
|--------------------------|---------------------------|
| `paginator[page]`        | Get page state            |
| `paginator[page, index]` | Get element               |
| `page in paginator`      | Check if page is cached   |

### MutablePaginator Methods (additional)

| Method                                           | Returns         | Description                             |
|--------------------------------------------------|-----------------|-----------------------------------------|
| `setState(state, silently)`                      | `Unit`          | Set a page state (public)               |
| `removeState(page, silently)`                    | `PageState<T>?` | Remove a page                           |
| `setElement(element, page, index, isDirty)`      | `Unit`          | Replace element (optionally mark dirty) |
| `removeElement(page, index, isDirty)`            | `T`             | Remove element (optionally mark dirty)  |
| `addAllElements(elements, page, index, isDirty)` | `Unit`          | Insert elements (optionally mark dirty) |
| `replaceAllElements(provider, predicate)`        | `Unit`          | Bulk replace/remove across all pages    |
| `flush()`                                        | `Unit`          | Flush CRUD changes to L2 (suspend)      |

### MutablePaginator Operators

| Operator                 | Description          |
|--------------------------|----------------------|
| `paginator += pageState` | Set page state in L1 |
| `paginator -= page`      | Remove page          |

---

## Releasing a New Version

Step-by-step guide for publishing a new release of the library to Maven Central.

### Prerequisites

Make sure the following **GitHub Secrets** are configured in the repository
(`Settings → Secrets and variables → Actions → Repository secrets`):

| Secret                     | Description                                                                    |
|----------------------------|--------------------------------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME`   | Sonatype Central Portal token username                                         |
| `MAVEN_CENTRAL_PASSWORD`   | Sonatype Central Portal token password                                         |
| `GPG_KEY_ID`               | Last 8 characters of your GPG key ID                                           |
| `GPG_KEY_PASSWORD`         | Passphrase for the GPG key                                                     |
| `GPG_KEY`                  | Base64-encoded GPG private key (`gpg --export-secret-keys <KEY_ID> \| base64`) |

### Step 1 — Update the Version

In `paginator/build.gradle.kts`, change the `version` property:

```kotlin
version = "7.7.0" // ← new version
```

### Step 2 — Update README Installation Examples

Update the version in all `implementation(...)` snippets in this README
(sections **KMP**, **Android-only**, and **JVM**) to match the new version.

### Step 3 — Commit and Push

```bash
git add -A
git commit -m "Bump version to 7.7.0"
git push origin master
```

### Step 4 — Create a GitHub Release

1. Go to **[Releases → New release](https://github.com/jamal-wia/Paginator/releases/new)**
2. Click **"Choose a tag"** and type the new version (e.g. `7.7.0`), then select **"Create new tag on publish"**
3. Set **Release title** (e.g. `7.7.0`)
4. Describe the changes in the description
5. Click **"Publish release"**

This triggers the **`Publish to Maven Central`** GitHub Actions workflow automatically.

### Step 5 — Monitor the Workflow

1. Go to **[Actions](https://github.com/jamal-wia/Paginator/actions)** and open the running workflow
2. Wait for the workflow to complete successfully (green checkmark)

### Step 6 — Verify on Maven Central

1. Go to [Sonatype Central Portal](https://central.sonatype.com/) → **Deployments**
2. The deployment should appear with status **PUBLISHING** → **PUBLISHED**
3. Once **PUBLISHED**, the artifact is available at:
   ```
   io.github.jamal-wia:paginator:<version>
   ```
4. It may take **5–30 minutes** for the artifact to become resolvable via Gradle after status changes to PUBLISHED

### Manual Publishing (without GitHub Actions)

If you need to publish from your local machine instead of CI:

1. Add credentials to `~/.gradle/gradle.properties` (NOT the project's `gradle.properties`):
   ```properties
   mavenCentralUsername=<your-sonatype-token-username>
   mavenCentralPassword=<your-sonatype-token-password>
   signing.keyId=<last-8-chars-of-gpg-key>
   signing.password=<gpg-key-passphrase>
   signing.secretKeyRingFile=<path-to-secring.gpg>
   ```

2. Run:
   ```bash
   ./gradlew :paginator:publishAndReleaseToMavenCentral --no-configuration-cache
   ```

3. The `automaticRelease = true` flag in `build.gradle.kts` ensures the deployment is
   automatically released after validation. No manual "Release" button click is needed.

### Troubleshooting

| Problem                                 | Solution                                                                                                                      |
|-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Workflow fails at "Import GPG key"      | Re-export and update the `GPG_KEY` secret: `gpg --export-secret-keys <KEY_ID> \| base64`                                      |
| Deployment stuck at **VALIDATED**       | `automaticRelease = true` was not set. Either click "Release" manually in the Central Portal, or re-run with the flag enabled |
| Deployment **FAILED**                   | Check the Central Portal for validation errors (missing POM fields, signature issues, etc.)                                   |
| Artifact not resolvable after PUBLISHED | Wait up to 30 minutes. Maven Central syncing can be slow                                                                      |
| Signing error locally                   | Ensure `signing.secretKeyRingFile` points to a valid `.gpg` file and passphrase is correct                                    |

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
