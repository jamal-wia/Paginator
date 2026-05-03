# Upgrading Paginator from 3.x to 8.x

> If you've been using **Paginator 3.3.0** and decide to jump straight to the current **8.6.2**,
> you're not really doing an upgrade — you're migrating to what is, in practice, a different
> library. The core ideas (page state, bookmarks, jumps, snapshot/cacheFlow) are the same,
> but between those two versions there are 200+ commits, **five major releases** (4.x → 8.x),
> a move from JitPack to Maven Central, a switch from Android-only to **Kotlin Multiplatform**,
> a split into multiple artifacts, and a fairly aggressive renaming pass through the public API.

This article walks through what actually changed and the order I'd recommend touching things in,
based on a diff between commits `f58bf336` (3.3.0) and `c8389580` (8.6.2).

---

## 1. Artifact coordinates and repository

This is the very first change, and it's mandatory.

**3.3.0 — JitPack:**

```kotlin
repositories {
    maven { setUrl("https://jitpack.io") }
}
dependencies {
    implementation("com.github.jamal-wia:Paginator:3.3.0")
}
```

**8.6.2 — Maven Central + BOM + multi-module:**

```kotlin
repositories {
    mavenCentral() // JitPack is no longer required
}
dependencies {
    implementation(platform("io.github.jamal-wia:paginator-bom:8.6.2"))
    implementation("io.github.jamal-wia:paginator")           // core, required
    implementation("io.github.jamal-wia:paginator-compose")   // optional — Compose / CMP
    implementation("io.github.jamal-wia:paginator-view")      // optional — Android Views
}
```

The `groupId` changed (`com.github.jamal-wia` → `io.github.jamal-wia`), the artifact was split
into `paginator` / `paginator-compose` / `paginator-view`, and a `paginator-bom` was added so that
all three artifacts stay aligned on the classpath.

## 2. Platforms

3.3.0 lives under `paginator/src/main/java/...` — it's an **Android-only** library.

8.6.2 is **Kotlin Multiplatform**: sources moved to `commonMain`, and published targets are
**Android, JVM, iosX64, iosArm64, iosSimulatorArm64**. If you have a KMP project, you can now put
`paginator` directly into `commonMain.dependencies` and Gradle resolves the right platform
artifact from KMP metadata.

## 3. Page numbers: `UInt` → `Int`

In 3.3.0 page numbers were `UInt` (`1u`, `BookmarkUInt(page = 5u)`, `page: UInt`). In 8.6.2 they
are **`Int`**. Anything ending in `u` and any `UInt`/`BookmarkUInt` reference needs to be
rewritten.

```kotlin
// 3.3.0
paginator.setPageState(page = 1u, ...)
paginator.bookmarks += BookmarkUInt(page = 5u)

// 8.6.2
paginator.setState(page = 1, ...)
paginator.bookmarks += BookmarkInt(page = 5)
```

The contract is `page >= 1`; `0` for context pages now means "not started yet".

## 4. The `load` lambda

This is probably the breaking change you'll hit first when you actually start writing code.

**3.3.0:**

```kotlin
val paginator = Paginator<Item> { page: UInt ->
    repo.loadPage(page.toInt()) // returns List<Item>
}
```

**8.6.2:**

```kotlin
val paginator = Paginator<Item> { page: Int ->
    LoadResult(repo.loadPage(page))
    // or, with API metadata:
    // LoadResult(items, MyMetadata(totalCount = response.total))
}
```

Three things changed at once: the property is now called `load` instead of `source`, the page
parameter is `Int`, and the lambda must return a `LoadResult<T>` (`data` + an optional
`Metadata`). `LoadResult` is the official extension point for things like total counts, cursors,
or any per-page metadata you want to forward to `PageState.metadata`.

## 5. `Paginator` vs `MutablePaginator`

In 3.3.0 a single `Paginator` class did everything — navigation, `addElement`, `removeElement`,
`resize`, `setPageState`. Starting with the 6.x line that was split:

- `Paginator<T>` — read-only; navigation only (`jump*`, `goNextPage`, `goPreviousPage`, `restart`,
  `refresh`).
- `MutablePaginator<T>` — extends `Paginator`; element-level CRUD, `resize`, `setState`.

If you call `addElement` / `removeElement` / `setPageState` / `resize` / `setState` anywhere,
your variable type needs to be `MutablePaginator<T>`. For pure read scenarios you can keep
`Paginator<T>` and get a slightly narrower API surface for free.

## 6. The 5.0.0 rename pass

Most of your post-upgrade compile errors will come from this one commit (`13aa2bf`):

| 3.3.0                            | 8.6.2                    |
|----------------------------------|--------------------------|
| `setPageState(...)`              | `setState(...)`          |
| `removePageState(...)`           | `removeState(...)`       |
| `getPageState(page)`             | `getStateOf(page)`       |
| `ignoreCapacity`                 | `isCapacityUnlimited`    |
| `isValidSuccessState`            | `isFilledSuccessState`   |
| `enforceCapacity`                | `coerceToCapacity` (7.x) |
| `fastSearchPageBefore`           | `walkBackwardWhile`      |
| `fastSearchPageAfter`            | `walkForwardWhile`       |
| `walkForwardWhile/...` (members) | extension functions      |
| `removeElement(...)` (member)    | extension function       |
| `refreshAll`, some `addElement`  | extension functions      |

Plus: the cache is no longer a directly-exposed `sortedMap`. It now lives behind
`core: PagingCore` / `cache: PagingCache`. If you reached into the internal map in 3.3.0, you'll
need to switch to the `core`/`cache` API.

## 7. Repackaging

In 3.3.0 almost everything was nested inside `Paginator` itself (`Paginator.PageState.SuccessPage`,
`Paginator.Bookmark.BookmarkUInt`, `Paginator.LockedException...`). In 8.6.2 those types live in
proper sub-packages:

- `com.jamal_aliev.paginator.page.PageState` (+ `PlaceholderPageState`, `PaginatorUiState`)
- `com.jamal_aliev.paginator.bookmark.BookmarkInt`
- `com.jamal_aliev.paginator.exception.LockedException.*`, `FinalPageExceededException`,
  `LoadGuardedException`
- `com.jamal_aliev.paginator.load.LoadResult`, `Metadata`
- `com.jamal_aliev.paginator.cache.PagingCache` and the cache strategies
- `com.jamal_aliev.paginator.logger.PaginatorLogger`

Every import needs to be rewritten. The good news is that IDE auto-import handles most of this
once you've fixed the rename pass from §6.

## 8. New things you should know about while you're in there

These aren't breaking, but they change what idiomatic code looks like in 8.x. While you're
already touching the code, consider adopting them:

- **`finalPage: Int`** — upper bound for pagination, throws `FinalPageExceededException`
  (introduced in 5.1.0). Useful when the API tells you the total page count.
- **`loadGuard`** — a callback that can veto a page load *before* the network request, throwing
  `LoadGuardedException`. Replaces ad-hoc "should I load?" flags.
- **Cache strategies** — `LRU` / `FIFO` / `TTL` / `SlidingWindow` / `Composite`, pluggable through
  `PagingCache`. In 3.3.0 your only knob was `capacity`.
- **State serialization** — `paginator.saveState(...)` / `restoreState(...)` based on
  `kotlinx.serialization`. Replaces the manual "store last page numbers in `SavedStateHandle`"
  dance for process-death.
- **`PaginatorUiState`** — a single Flow ready to be consumed by the UI layer (loading / data /
  error / empty), recommended in the new Quick Start instead of hand-stitching `snapshot` plus
  loading flags.
- **`PlaceholderProgressPage`** — built-in support for skeleton/shimmer rows.
- **`PaginatorLogger`** — pluggable logging (`AndroidLogger`, `NoOpLogger`).
- **DSL builder** — `paginator { … }` instead of the positional constructor (8.1+).
- **`paginator-compose`** — automatic prefetch for `LazyColumn` / `LazyRow` / `LazyVerticalGrid` /
  `LazyVerticalStaggeredGrid`. No more `LaunchedEffect` + `snapshotFlow` plumbing.
- **`paginator-view`** — `bindPaginated` / `bindPrefetchToRecyclerView` for Android Views.
- **`CursorPaginator` / `MutableCursorPaginator`** — a parallel cursor-based mode
  (`prev` / `self` / `next`), aimed at GraphQL connections, chats, and any API that hands you
  cursors instead of page numbers (8.3+). If your backend already speaks cursors, this is
  strictly better than emulating it on top of offset pagination.
- **Dirty pages** — tracking that lets you invalidate just the affected pages instead of the whole
  cache.

## 9. Side-by-side example

**3.3.0:**

```kotlin
class MainViewModel : ViewModel() {

    private val paginator = Paginator<Item> { page: UInt ->
        SampleRepository.loadPage(page.toInt())
    }

    init { viewModelScope.launch { paginator.jumpForward() } }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }
}
```

**8.6.2:**

```kotlin
class MainViewModel : ViewModel() {

    private val paginator = MutablePaginator<Item> { page: Int ->
        LoadResult(SampleRepository.loadPage(page))
    }.apply {
        // logger = AndroidLogger()
        // finalPage = 50
    }

    val uiState = paginator.uiState // Flow<PaginatorUiState<Item>>

    init { viewModelScope.launch { paginator.jumpForward() } }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }
}
```

## 10. The migration order I'd recommend

1. **Gradle first.** Drop JitPack, add `mavenCentral()`, switch to the BOM and the artifacts you
   actually use.
2. **Imports.** `Paginator.PageState.*` → `com.jamal_aliev.paginator.page.PageState.*`, and so on
   for the other relocated types (§7).
3. **`UInt` → `Int`** everywhere — pages, bookmarks, parameters, return types
   (`BookmarkUInt` → `BookmarkInt`).
4. **Wrap your data source.** `source = { … }` returning `List<T>` → `load = { … }` returning
   `LoadResult(...)`.
5. **Switch the variable type to `MutablePaginator`** if you call any CRUD / `setState` / `resize`.
6. **Run the rename pass** from §6 (`setPageState→setState`, `getPageState→getStateOf`,
   `ignoreCapacity→isCapacityUnlimited`, `enforceCapacity→coerceToCapacity`, …).
7. **Compile.** The compiler will surface what's left (mostly extension-functions that used to be
   members).
8. **Verify runtime behavior** on a representative dataset. The page contract is now `page >= 1`,
   `0` means "not started"; lock flags and exceptions live under the `exception` package.
9. **Optional, but worth it:** move to `uiState`, pick a `PagingCache` strategy, adopt
   `saveState` / `restoreState`, the DSL builder, and — if your API is cursor-based —
   `CursorPaginator`.

## 11. Where to look for details

The docs in [docs/](../../docs) cover the new APIs end-to-end. The most useful ones during a
migration:

- [docs/1. core-concepts.md](../../docs/1.%20core-concepts.md)
- [docs/3. state.md](../../docs/3.%20state.md) — the new `PaginatorUiState`
- [docs/5. serialization.md](../../docs/5.%20serialization.md) — `saveState` / `restoreState`
- [docs/6. caching.md](../../docs/6.%20caching.md) — cache strategies
- [docs/11. dsl-builder.md](../../docs/11.%20dsl-builder.md)
- [docs/13. cursor-pagination.md](../../docs/13.%20cursor-pagination.md) and
  [docs/14. paginator-vs-cursor.md](../../docs/14.%20paginator-vs-cursor.md)

---

## TL;DR

The concepts survived, the spelling didn't. The breaking changes that actually cost time are:

1. artifact coordinates and the move to Maven Central + BOM,
2. `UInt` → `Int` for all page numbers,
3. `source: () -> List<T>` → `load: () -> LoadResult<T>`,
4. the split into `Paginator` / `MutablePaginator`,
5. the 5.0.0 rename pass,
6. repackaging of `PageState`, `Bookmark`, exceptions, and the cache.

Close those six and the rest is either new optional features or cosmetics.
