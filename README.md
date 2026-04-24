# Paginator

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/jamal-wia/Paginator)
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

The library exposes **two flavors** that share the same page-state model, caches, CRUD, UI state
and snapshot flows:

- **`Paginator` / `MutablePaginator`** — offset/page-number addressing (`MutableList`-like).
- **`CursorPaginator` / `MutableCursorPaginator`** — cursor-based, `prev`/`self`/`next` linked
  navigation (`LinkedList`-like). See
  [Cursor-Based Pagination](docs/13.%20cursor-pagination.md).

Built entirely with pure Kotlin and without platform-specific dependencies, 
Paginator can be seamlessly used across all layers of an application 
— from data to domain to presentation — while preserving Clean Architecture principles and proper layer separation.

**Supported targets:** Android · JVM · iosX64 · iosArm64 · iosSimulatorArm64

---
## AI Docs - https://deepwiki.com/jamal-wia/Paginator
---

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Infinite Scroll / Infinite Feed](#infinite-scroll--infinite-feed)
- [Cursor-Based Pagination](#cursor-based-pagination)
- [Features](#features)
- [Documentation](#documentation)
- [License](#license)

---

## Installation

The library is published to **Maven Central**. No additional repository configuration needed.

### Kotlin Multiplatform (KMP)

Add the dependency to `commonMain` in your module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
          implementation("io.github.jamal-wia:paginator:8.3.0")
        }
    }
}
```

Gradle automatically resolves the correct platform artifact (`android`, `jvm`, `iosArm64`, etc.)
from the KMP metadata.

### Android-only project

```kotlin
dependencies {
  implementation("io.github.jamal-wia:paginator:8.3.0")
}
```

### JVM (Desktop / Server)

```kotlin
dependencies {
  implementation("io.github.jamal-wia:paginator-jvm:8.3.0")
}
```

---

## Quick Start

### Step 1: Create a Paginator

The simplest way to create a `MutablePaginator` is via the DSL builder:

```kotlin
import com.jamal_aliev.paginator.dsl.mutablePaginator
import com.jamal_aliev.paginator.load.LoadResult

class MyViewModel : ViewModel() {

    private val paginator = mutablePaginator<Item> {
        load { page -> LoadResult(repository.loadPage(page)) }
    }
}
```

The `load { }` block is the only required call — every other knob (capacity, cache strategy,
logger, bookmarks, custom `PageState` factories) has sensible defaults. See
[DSL Builder](docs/11.%20dsl-builder.md) for the full configuration surface.

If you only need read-only navigation, use `paginator<T> { … }` instead — it returns a
`Paginator<T>`, so element-level mutations are not exposed at the call site.

The `load` lambda receives an `Int` page number and should return a `LoadResult<T>` wrapping
your data list. For the simplest case, just wrap with `LoadResult(list)`. The direct constructor
form (`MutablePaginator(load = { … })`) is also still available if you prefer it.

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

Prefer a simpler API? Collect `paginator.uiState` instead — it emits `Idle` / `Loading` / `Empty`
/ `Error` / `Content(items, prependState, appendState)` so your UI does not have to reason about
individual `PageState`s. See
[State, Transactions & Locks → PaginatorUiState](docs/3.%20state.md#paginatoruistate).

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

## Infinite Scroll / Infinite Feed

Paginator works perfectly for a simple infinite scroll — and this is a first-class use case, not an
afterthought.

Every feature in the library is **strictly opt-in**. If all you need is "load the next page when the
user scrolls down", the entire setup is what you already saw in Quick Start: one `load` lambda,
one `snapshot` observer, and `goNextPage()` on scroll. Nothing else is required.

What you still get for free, with zero extra code:

- `ProgressPage` while the next page loads — no manual loading flag needed
- `ErrorPage` with the previously cached data intact — a failed request won't clear the screen
- Incomplete page detection — if the server returns fewer items than expected, the paginator quietly
  re-requests on the next scroll instead of silently stopping

Start with the simplest setup. Adopt advanced features only if and when your product actually needs
them.

---

## Cursor-Based Pagination

If your backend returns opaque continuation tokens instead of numeric page offsets (GraphQL
connections, chat feeds, activity streams, Slack/Instagram/Reddit-style APIs), reach for the
cursor variant:

```kotlin
import com.jamal_aliev.paginator.bookmark.CursorBookmark
import com.jamal_aliev.paginator.dsl.mutableCursorPaginator
import com.jamal_aliev.paginator.load.CursorLoadResult

val messages = mutableCursorPaginator<Message>(capacity = 50) {
  load { cursor ->
    val page = api.getMessages(cursor?.self as? String)
    CursorLoadResult(
      data = page.items,
      bookmark = CursorBookmark(
        prev = page.prevCursor,   // null at the head of the feed
        self = page.selfCursor,   // required — cache key
        next = page.nextCursor,   // null at the tail of the feed
      ),
    )
  }
}

viewModelScope.launch {
  messages.restart()           // bootstrap from the first cursor (or initialCursor if set)
  messages.goNextPage()        // follows endContextCursor.next — throws EndOfCursorFeedException at tail
  messages.goPreviousPage()    // follows startContextCursor.prev — throws at head
}
```

The cursor paginator shares caches, CRUD, UI state (`paginator.uiState`), snapshot flow,
`transaction { }`, prefetch controller, logger, and serialization with the offset variant — it
differs only in **how pages are addressed**. Read the full guide at
[Cursor-Based Pagination](docs/13.%20cursor-pagination.md).

---

## Features

- **Two pagination flavours** -- offset-based `Paginator` (`MutableList`-like, numeric page
  addressing) and cursor-based `CursorPaginator` (`LinkedList`-like, `prev`/`self`/`next` tokens)
  sharing the same page-state model, caches, CRUD surface, UI state and snapshot flow. See
  [Cursor-Based Pagination](docs/13.%20cursor-pagination.md)
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
- **High-level UI state** -- `paginator.uiState: Flow<PaginatorUiState<T>>` collapses the raw
  snapshot into `Idle` / `Loading` / `Empty` / `Error` / `Content(items, prependState, appendState)`
  for screens that only need full-screen indicators and boundary activity markers
- **Element-level CRUD** -- get, set, add, remove, and replace individual elements within pages,
  with automatic page rebalancing
- **Capacity management** -- resize pages on the fly with automatic data redistribution
- **Source metadata** -- `load` returns `LoadResult<T>`, an open wrapper that carries both
  page data and arbitrary metadata from the API response (total count, cursors, etc.). Metadata
  flows through initializer lambdas into custom `PageState` subclasses
- **Custom PageState subclasses** -- extend `SuccessPage`, `ErrorPage`, `ProgressPage`, or
  `EmptyPage` with your own types via initializer lambdas
- **Dirty pages** -- mark pages as "dirty" so they are automatically refreshed (fire-and-forget) on
  the next navigation (`goNextPage`, `goPreviousPage`, `jump`). CRUD operations can also mark pages
  dirty via the `isDirty` flag
- **Two-tier API** -- `Paginator` (read-only navigation, dirty tracking, release) and
  `MutablePaginator` (element-level CRUD, resize, public `setState`)
- **DSL builder** -- declarative `paginator<T> { … }` and `mutablePaginator<T> { … }` blocks that
  collapse `PagingCore` setup, cache composition, bookmarks, logger and custom `PageState`
  initializers into one configuration site
- **Rich extension API** -- collection-style helpers on `Paginator` (`find`, `count`, `flatten`,
  `firstOrNull`, `contains`, …) and bulk CRUD on `MutablePaginator` (`prependElement`,
  `moveElement`, `swapElements`, `insertBefore`/`After`, `removeAll`, `retainAll`, `distinctBy`,
  `updateAll`/`updateWhere`)
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
- **Interweaving** -- opt-in `Flow<PaginatorUiState<T>>.interweave(weaver)` operator that inserts
  meta-rows (date headers, unread dividers, section labels, …) between data items without touching
  the paginator core, cache, CRUD, serialization, or DSL

---

## Documentation

Detailed documentation lives in the [`docs/`](docs/) directory:

1. [**Core Concepts**](docs/1.%20core-concepts.md) — `PageState`, `Paginator` vs `MutablePaginator`,
  context window, bookmarks, `LoadResult` & metadata, capacity, final page limit
2. [**Navigation**](docs/2.%20navigation.md) — `goNextPage`, `goPreviousPage`, `jump`,
   `jumpForward` /
  `jumpBack`, `restart`, `refresh`
3. [**State, Transactions & Locks**](docs/3.%20state.md) — dirty pages, reactive state (snapshot &
  cache flows), atomic `transaction { }`, lock flags
4. [**Element Operations & Custom Page States**](docs/4.%20elements.md) — element-level CRUD, custom
  `PageState` subclasses, `PlaceholderPageState`, metadata propagation
5. [**State Serialization**](docs/5.%20serialization.md) — saving & restoring paginator state via
  `kotlinx.serialization`, surviving process death
6. [**Caching**](docs/6.%20caching.md) — eviction strategies (LRU, FIFO, TTL, sliding window),
   composing
   strategies, persistent L2 cache
7. [**Prefetch**](docs/7.%20prefetch.md) — auto-pagination on scroll with
   `PaginatorPrefetchController`
8. [**Logger**](docs/8.%20logger.md) — pluggable logging via `PaginatorLogger`
9. [**Extensions**](docs/9.%20extensions.md) — extension function reference (`PageExt`,
   iteration, search/aggregation, CRUD, refresh, prefetch) plus a complete ViewModel example
10. [**API Reference**](docs/10.%20api-reference.md) — complete property / method / operator tables
11. [**DSL Builder**](docs/11.%20dsl-builder.md) — `paginator<T> { … }` and
    `mutablePaginator<T> { … }` builder DSL
12. [**Interweaving**](docs/12.%20interweaving.md) — opt-in `Flow` operator that interleaves
    meta-rows (date headers, unread dividers, …) between data items
13. [**Cursor-Based Pagination**](docs/13.%20cursor-pagination.md) — `CursorPaginator` /
    `MutableCursorPaginator` for opaque-token feeds (GraphQL connections, chat, activity streams)
14. [**Paginator vs CursorPaginator**](docs/14.%20paginator-vs-cursor.md) — full catalog of
    behavioural differences, removed APIs, signature-only changes, and a migration cheat sheet
15. [Ask the author a question](https://t.me/+0eeAM-EJpqgwNGZi)

Maintainer docs:

- [**Releasing a New Version**](RELEASING.md) — publishing the library to Maven Central

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
