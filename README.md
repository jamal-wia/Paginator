# Paginator — KMP pagination library for Android, iOS, JVM & Web

> Pagination / paging library for **Kotlin Multiplatform** (Android, iOS, JVM, Desktop, Server, JS,
> Wasm).
> A pure-Kotlin alternative to **Jetpack Paging 3** with cursor pagination, bidirectional scroll,
> bookmarks, page caching, element-level CRUD, infinite scroll, prefetch and Flow-based UI state.

**Keywords:** Kotlin Multiplatform pagination · KMP paging · Android paging library · iOS paging ·
Kotlin JS pagination · Kotlin Wasm pagination · web pagination · cursor pagination · infinite
scroll ·
endless list · load more · Jetpack Paging 3 alternative · bidirectional pagination ·
chat / messenger feed · GraphQL connections · coroutines · Flow.

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/jamal-wia/Paginator)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.jamal-wia/paginator)](https://central.sonatype.com/artifact/io.github.jamal-wia/paginator) [![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin)
![Android](https://img.shields.io/badge/target-Android-green)
![JVM](https://img.shields.io/badge/target-JVM-blue)
![iOS](https://img.shields.io/badge/target-iOS-lightgrey)
![JS](https://img.shields.io/badge/target-JS-f7df1e?logo=javascript&logoColor=black)
![WasmJs](https://img.shields.io/badge/target-WasmJs-654ff0?logo=webassembly&logoColor=white)

## [**📲 Download Demo APK**](https://raw.githubusercontent.com/jamal-wia/Paginator/master/PaginatorDemo.apk)

**Paginator** is a powerful, flexible **pagination library for Kotlin Multiplatform (KMP)** —
Android, iOS, JVM, Desktop, JS and Wasm — that goes far beyond simple "load next page" patterns. It
is a
production-ready alternative to **Jetpack Paging 3** / `Pager` / `AsyncPagingDataDiffer` with a
full-featured page management system: jumping to arbitrary pages, bidirectional navigation,
bookmarks, page caching, cursor-based pagination, element-level CRUD, incomplete page handling,
capacity management and reactive UI state via Kotlin Flows.

The library exposes **two flavors** that share the same page-state model, caches, CRUD, UI state
and snapshot flows:

- **`Paginator` / `MutablePaginator`** — offset/page-number addressing (`MutableList`-like).
- **`CursorPaginator` / `MutableCursorPaginator`** — cursor-based, `prev`/`self`/`next` linked
  navigation (`LinkedList`-like). See
  [Cursor-Based Pagination](docs/13.%20cursor-pagination.md).

Built entirely with pure Kotlin and without platform-specific dependencies, 
Paginator can be seamlessly used across all layers of an application 
— from data to domain to presentation — while preserving Clean Architecture principles and proper layer separation.

**Supported targets:** Android · JVM · iosX64 · iosArm64 · iosSimulatorArm64 · js · wasmJs

---
## AI Docs - https://deepwiki.com/jamal-wia/Paginator
---

## Table of Contents

- [Why Paginator? (vs Jetpack Paging 3)](#why-paginator-vs-jetpack-paging-3)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Infinite Scroll / Infinite Feed](#infinite-scroll--infinite-feed)
- [Cursor-Based Pagination](#cursor-based-pagination)
- [Features](#features)
- [Articles](#articles)
- [Documentation](#documentation)
- [License](#license)

---

## Why Paginator? (vs Jetpack Paging 3)

Most Android developers reach for **Jetpack Paging 3**, which is Android-centric in practice
(KMP targets exist in upstream sources, but the published artifacts and ecosystem — Room,
RecyclerView, Compose adapters — are Android-first), ViewModel/UI-coupled and intentionally
opinionated. Paginator was built for the cases Paging 3 doesn't cover well:

| Capability                              | Jetpack Paging 3                                   | **Paginator**                                                    |
|-----------------------------------------|----------------------------------------------------|------------------------------------------------------------------|
| Targets (published artifacts)           | Android-first (KMP in sources, ecosystem AndroidX) | **Android · iOS · JVM · Desktop · JS · WasmJs**                  |
| Layer                                   | UI / ViewModel                                     | **Data / Domain / Presentation**                                 |
| Bidirectional scroll (chat / messenger) | Limited                                            | **First-class**                                                  |
| Cursor pagination (GraphQL, chat)       | Manual                                             | **Built-in `CursorPaginator`**                                   |
| Jump to arbitrary page / bookmarks      | No                                                 | **Yes (`jump`, `BookmarkInt`, …)**                               |
| Element-level CRUD inside pages         | No (immutable `PagingData`)                        | **Yes (`MutablePaginator`)**                                     |
| Page caching strategies                 | Fixed window around viewport (`maxSize`)           | **Pluggable: MostRecent / Queued / TimeLimited / ContextWindow** |
| State serialization (process death)     | Last `PagingData` only via `cachedIn`              | **Full cache via `kotlinx.serialization`**                       |
| Dependencies                            | AndroidX                                           | **Pure Kotlin, zero platform deps**                              |

See the side-by-side
write-up: [Paging 3 is good. Until you need something more.](articles/en/Paging%203%20is%20good.%20Until%20you%20need%20something%20more.md)

---

## Installation

The library is published to **Maven Central**. No additional repository configuration needed.

The recommended way is to import the **BOM** once and then declare the artifacts you
need without versions — that way `paginator`, `paginator-compose`, and `paginator-view`
cannot drift on your classpath:

```kotlin
dependencies {
  // Pin all Paginator artifacts together. Latest version: see the Maven Central badge above.
  implementation(platform("io.github.jamal-wia:paginator-bom:8.7.0"))

  // Core — required
  implementation("io.github.jamal-wia:paginator")

  // Optional UI bindings — pick what you actually use
  implementation("io.github.jamal-wia:paginator-compose")  // Jetpack Compose / Compose Multiplatform
  implementation("io.github.jamal-wia:paginator-view")     // Android Views / RecyclerView
}
```

For **Kotlin Multiplatform**, Gradle automatically resolves the correct platform artifact
(`paginator-jvm`, `paginator-iosArm64`, `paginator-js`, etc.) from the KMP metadata.
`paginator-compose`
(KMP) goes in the shared Compose source set; `paginator-view` is Android-only and belongs
in the Android source set.

> **Kotlin 2.3+ / KMP note:** calling `platform()` inside
`kotlin { sourceSets { commonMain.dependencies { } } }`
> is deprecated ([KT-58759](https://youtrack.jetbrains.com/issue/KT-58759)) and scheduled for
> removal.
> Declare the BOM in the **top-level** `dependencies {}` block using `commonMainImplementation`:
>
> ```kotlin
> // top-level dependencies {} block — NOT inside kotlin { sourceSets { } }
> dependencies {
>     commonMainImplementation(platform("io.github.jamal-wia:paginator-bom:8.7.0"))
> }
>
> // inside kotlin { sourceSets { commonMain.dependencies { } } } — no version needed
> kotlin {
>     sourceSets {
>         commonMain.dependencies {
>             implementation("io.github.jamal-wia:paginator")
>             implementation("io.github.jamal-wia:paginator-compose")
>         }
>     }
> }
> ```

The BOM only pins *Paginator* artifacts; it does not constrain Compose, Kotlin, AndroidX,
or anything else on your classpath.

**Supported targets:** Android · JVM · iosX64 · iosArm64 · iosSimulatorArm64 · js · wasmJs.

### What each UI artifact does

`paginator-compose` provides scroll-driven prefetch for `LazyColumn` / `LazyRow` /
`LazyVerticalGrid` / `LazyVerticalStaggeredGrid` (and horizontal counterparts) — no manual
`LaunchedEffect` / `snapshotFlow` plumbing. The recommended entry point is
`rememberPaginated` + the `paginated { }` DSL — zero manual numbers (`dataItemCount` is
read from `paginator.uiState`, header / footer counts are tallied by the DSL):

```kotlin
val listState = rememberLazyListState()
val paged = paginator.rememberPaginated(state = listState)

LazyColumn(state = listState) {
    paginated(paged) {
        header { StickyTitle() }
        items(uiState.items, key = { it.id }) { Row(it) }
        appendIndicator { AppendIndicator(uiState.appendState) }
    }
}
```

A one-call `PrefetchOnScroll(state, dataItemCount, …)` and a low-level
`rememberPrefetchController` + `BindToLazyList` are also available if you want to keep
counts explicit or hold a reference to the controller. See
[docs/7. prefetch.md](docs/7.%20prefetch.md#jetpack-compose-paginator-compose) for the full
guide — including `PrefetchOptions`, reactive error handling via `PrefetchErrorChannel`,
and advanced knobs (`restartKey`, `scrollSampleMillis`).

`paginator-view` removes the `OnScrollListener` plumbing entirely and offers three
layers of integration: `bindPaginated` (auto-tracks `dataItemCount` from
`paginator.uiState`), `bindPrefetchToRecyclerView` (one-call factory + bind), and the
low-level `controller.bindToRecyclerView` for `ViewModel`-scoped controllers. All three
support `LinearLayoutManager`, `GridLayoutManager`, and `StaggeredGridLayoutManager`,
install both `OnScrollListener` and `OnLayoutChangeListener` (so partial first pages
don't stall pagination), and clean up on `ON_DESTROY`.

```kotlin
binding.recyclerView.adapter = ConcatAdapter(headerAdapter, dataAdapter, appendIndicatorAdapter)
binding.recyclerView.layoutManager = LinearLayoutManager(context)

val paged = paginator.bindPaginated(
    recyclerView = binding.recyclerView,
    lifecycleOwner = viewLifecycleOwner,
    headerCount = { headerAdapter.itemCount },
    footerCount = { appendIndicatorAdapter.itemCount },
)
// `paged.controller` for hot-update mutation; `paged.recalibrate()` after refresh().
```

Reactive prefetch errors via `PrefetchErrorChannel` (StateFlow), runtime knobs through
`PrefetchOptions` (shared with `paginator-compose`), and stable `PageLoadGuard` /
`CursorLoadGuard` are also available.

See [docs/7. prefetch.md](docs/7.%20prefetch.md#android-recyclerview-paginator-view) for details.

---

## Quick Start

### Step 1: Create a Paginator

The simplest way to create a `MutablePaginator` is via the DSL builder:

```kotlin
import com.jamal_aliev.paginator.dsl.mutablePaginator
import com.jamal_aliev.paginator.load.LoadResult

class FeedViewModel : ViewModel() {

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

Subscribe to `paginator.uiState` to receive UI updates, then start the paginator by jumping to the
first page:

```kotlin
init {
  paginator.uiState
    .onEach { state ->
      when (state) {
        is PaginatorUiState.Content -> showContent(state.items)
        is PaginatorUiState.Loading -> showLoading()
        is PaginatorUiState.Empty -> showEmpty()
        is PaginatorUiState.Error -> showError(state.cause)
        is PaginatorUiState.Idle -> Unit
      }
    }
        .flowOn(Dispatchers.Main)
        .launchIn(viewModelScope)

    viewModelScope.launch {
        paginator.jump(bookmark = BookmarkInt(page = 1))
    }
}
```

`uiState` emits `Idle` / `Loading` / `Empty` / `Error` / `Content(items, prependState, appendState)`
so your UI does not have to reason about individual `PageState`s. If you need raw page-level access,
collect `paginator.core.snapshot` instead. See
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
one `uiState` observer, and `goNextPage()` on scroll. Nothing else is required.

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
  `MostRecentPagingCache` (LRU), `QueuedPagingCache` (FIFO), `TimeLimitedPagingCache` (TTL),
  and `ContextWindowPagingCache` (evicts outside context). Eviction listener callback for reacting
  to
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
  automatically loads the next/previous page before the user reaches the edge of content,
  configurable at runtime via `PrefetchOptions` (prefetch distance, backward prefetch,
  scroll sampling, cancel-on-dispose, …) shared across all UI artifacts
- **Reactive prefetch errors** -- `PrefetchErrorChannel` exposes prefetch failures as a
  `StateFlow`, so silent background loads can still surface to the UI without coupling to
  the controller
- **Stable load guards** -- `PageLoadGuard` / `CursorLoadGuard` functional interfaces gate
  prefetch on custom conditions (network state, quotas, rate limits) without subclassing
- **Index remapping** -- `RemapIndices` translates UI scroll positions (with headers,
  footers, dividers) into data-only coordinates, so prefetch math stays correct in mixed
  `ConcatAdapter` / DSL layouts
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
- **Bill of Materials (`paginator-bom`)** -- import the BOM once and declare `paginator`,
  `paginator-compose`, `paginator-view` without versions; the BOM keeps the suite aligned on
  your classpath and only constrains Paginator artifacts (no impact on Compose / Kotlin /
  AndroidX versions)
- **Compose Multiplatform bindings (`paginator-compose`)** -- `PaginatedLazyList`,
  `PaginatedLazyGrid`, `PaginatedLazyStaggeredGrid` plus `rememberPaginated` + the
  `paginated { }` DSL for zero-boilerplate prefetch on `LazyColumn` / `LazyRow` /
  `LazyVerticalGrid` / `LazyVerticalStaggeredGrid` (and horizontal counterparts); a
  one-call `PrefetchOnScroll(state, dataItemCount, …)` and a low-level
  `rememberPrefetchController` + `BindToLazyList` / `BindToLazyGrid` /
  `BindToLazyStaggeredGrid` are also available for explicit-count or controller-scoped
  setups
- **Android RecyclerView bindings (`paginator-view`)** -- three layers of integration:
  `bindPaginated` (auto-tracks `dataItemCount` from `paginator.uiState`),
  `bindPrefetchToRecyclerView` (one-call factory + bind), and a low-level
  `controller.bindToRecyclerView` for `ViewModel`-scoped controllers; works with
  `LinearLayoutManager`, `GridLayoutManager`, `StaggeredGridLayoutManager`, installs both
  `OnScrollListener` and `OnLayoutChangeListener` (so partial first pages don't stall),
  and cleans up on `ON_DESTROY`

---

## Articles

In-depth articles comparing Paginator with Jetpack Paging 3 and demonstrating real-world
implementation patterns:

**English**

- [Paging 3 is good. Until you need something more.](articles/en/Paging%203%20is%20good.%20Until%20you%20need%20something%20more.md) —
  side-by-side comparison of Paginator and Paging 3 on a realistic feed
- [Messenger on Paginator. Real-world tasks.](articles/en/Messenger%20on%20Paginator.%20Real-world%20tasks.md) —
  building a production-grade messenger feed: bidirectional scroll, cursor pagination, CRUD,
  interweaving
- [Why I wrote Paginator instead of Paging 3.](articles/en/Why%20I%20wrote%20Paginator%20instead%20of%20Paging%203.md) —
  the author's perspective: which design decisions in Paging 3 hit a ceiling and how Paginator
  is built to avoid them

**Русский**

- [Paging 3 хорош. Пока вам не понадобится что-то ещё.](articles/ru/Paging%203%20хорош.%20Пока%20вам%20не%20понадобится%20что-то%20ещё.md) —
  сравнение Paginator и Paging 3 на реальном примере ленты
- [Мессенджер на Paginator. Боевые задачи](articles/ru/Мессенджер%20на%20Paginator.%20Боевые%20задачи.md) —
  реализация мессенджера: двунаправленный скролл, курсорная пагинация, CRUD, interweaving
- [Почему я написал Paginator вместо Paging 3.](articles/ru/Почему%20я%20написал%20Paginator%20вместо%20Paging%203.md) —
  взгляд автора: где решения Paging 3 упираются в потолок и как Paginator устроен, чтобы это
  обойти

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
6. [**Caching**](docs/6.%20caching.md) — eviction strategies (MostRecent, Queued, TimeLimited,
   ContextWindow),
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
