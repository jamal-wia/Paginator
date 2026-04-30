## Introduction you can skip if you've done pagination before

In short: pagination is when you don't load 100,000 catalog items in one request, but instead show
them in pages of 20–50 and fetch the next batch when the user scrolls to the bottom.

Sounds like a half-day task. In practice — it varies.

I've been writing mobile apps for a long time, and every time pagination showed up in a new project,
the same set of bugs and ad-hoc solutions appeared a month or two later. Flags like
`isLoadingNextPage`, `isLoadingPrevious`, `isRefreshing`, `isEmpty`, `hasError`, `hasNextPage`.
Attempts to "just update an item without reloading the page". Restoring scroll position after
process death. Jumping to a specific page via deeplink.

On Android there's Jetpack Paging 3, and it's the default choice. But as soon as you go beyond "load
the next 20 items on scroll down" — things get interesting. And if your project is Kotlin
Multiplatform, Paging 3 is a hard sell: upstream sources have KMP targets, but the published
artifacts and the surrounding ecosystem (Room, RecyclerView, Compose adapters) are still
Android-first, so on iOS you're effectively on your own.

I'll talk about the open-source library [Paginator](https://github.com/jamal-wia/Paginator), which
I've been building for the past several years. It works identically on Android, JVM, and iOS from a
single `commonMain`, handles complex scenarios out of the box — and even for a plain feed, it's
configured more concisely than Paging 3. This isn't a campaign against Paging 3 or an attempt to
prove anything. It's simply a description of the fact that there's another tool, and it does the
same thing more compactly.

---

## The simple case — even here Paging 3 asks for more

Imagine a classic product feed. Scroll down — load more. That's it.

A minimal Paging 3 implementation looks roughly like this:

```kotlin
class ItemsPagingSource(private val api: Api) : PagingSource<Int, Item>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Item> {
        val page = params.key ?: 1
        return try {
            val items = api.fetch(page)
            LoadResult.Page(
                data = items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (items.isEmpty()) null else page + 1
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Item>): Int? {
        ...
    }
}

val pager = Pager(PagingConfig(pageSize = 20)) { ItemsPagingSource(api) }
// + PagingDataAdapter / collectAsLazyPagingItems / LoadStateAdapter ...
```

To make this work, you need to keep in mind: `PagingSource`, `LoadParams`, `LoadResult.Page`,
`prevKey`/`nextKey`, `getRefreshKey`, `Pager`, `PagingConfig`, and also `PagingDataAdapter` or
`LazyPagingItems` on the UI side.

The same scenario with Paginator:

```kotlin
val paginator = mutablePaginator<Item> {
    load { page -> LoadResult(api.fetch(page)) }
}
```

That's it. No `PagingSource`, no `Pager`, no key parsing in both directions — because the page
number is just an `Int`, and the library knows how to move forward and backward from it on its own.

Then either subscription variant:

```kotlin
// Ready-made UI state: Idle / Loading / Empty / Error / Content
paginator.uiState.collect { render(it) }
```

```kotlin
// Or raw pages, if you want full control
paginator.core.snapshot.collect { pages -> render(pages) }
```

And navigation:

```kotlin
viewModelScope.launch { paginator.goNextPage() }
```

This isn't an exaggeration for effect. On a simple feed, Paginator really does configure in three
lines, and those three lines give you working loading, error, empty result states, duplicate request
protection, and a page cache. And those same three lines work — whether on Android or iOS.

Now about what Paging 3 either lacks or requires you to assemble by hand.

---

## What product usually asks for (and what happens with it)

### 1. "Open page 47 directly from a link"

A classic deeplink: the user received a notification, and on tap you need to open an item with ID
`12345`, which according to the backend is on page 47.

Paging 3 doesn't have this navigation out of the box — the library is built around the idea of "load
where the user scrolls," not "show me page N." You can scroll the RecyclerView to the needed
position after loading the first page — but that's not the same as showing page 47 directly. For a
true jump you need to override `itemsBefore` / `itemsAfter` and enable placeholders, and product
requirements rarely fit into placeholders cleanly.

In Paginator this is a built-in operation:

```kotlin
paginator.jump(BookmarkInt(page = 47))
```

If the page is already in cache — the return is instant. If not — it loads. After that the user can
page forward **or backward** from the current position.

### 2. "We have a chat / feed anchored in the middle"

Classic case: opened a discussion at a specific message, need to load both old messages (upward) and
new ones (downward). Or a catalog opened at the middle of the alphabet.

Paging 3 supports prepend/append, but the implementation of "start from the middle and go in both
directions" is quite blunt: you have to negotiate with the backend about keys for both directions
and correctly return `prevKey`/`nextKey`.

In Paginator it's the same API:

```kotlin
paginator.jump(BookmarkInt(page = 47)) // entry point
paginator.goNextPage()                 // page down
paginator.goPreviousPage()             // and up — out of the box
```

No different keys for different directions. One page — one number.

### 3. "Let users edit an item without reloading the page"

The user liked a post, changed a color on a card, or deleted their comment. Need to update the item.
In place. Without flickering.

Paging 3
has [historically struggled with this](https://jermainedilao.medium.com/android-paging-3-library-how-to-update-an-item-52f00d9c99b2):
`PagingData` doesn't allow you to directly change an item. Typical workarounds:

- Make item properties mutable and call `notifyItemChanged` manually.
- Call `invalidate()` and reload the entire source.
- Store an "overlay" of edits in a separate `StateFlow` and merge it with `PagingData`.

All of this works, but none of the options can be called obvious.

In Paginator, editing items is a first-class API:

```kotlin
paginator.setElement(updatedItem, page = 3, index = 1)
paginator.removeElement(page = 3, index = 1)
paginator.addAllElements(newItems, targetPage = 3, index = 0)

// By predicate, across all pages at once:
paginator.updateWhere(
    predicate = { it.id == updatedItem.id },
    transform = { updatedItem }
)
```

No `notifyItemChanged`, no `invalidate()`. The page cache changed — the `snapshot` Flow emitted a
new list — the UI re-rendered.

### 4. "The backend sometimes returns fewer items than expected"

A very real scenario: the expected page size is 20, the backend returned 13 due to a
filter/personalization/bad index. Paging 3 will honestly deliver those 13 to `PagingData`, and if
you don't write extra logic — pagination "collapses": `RecyclerView` sees that the scroll doesn't
reach the load trigger, and the next request never fires.

Paginator recognizes such pages as **underfilled** (via the `isFilledSuccessState` check) and on the
next `goNextPage` automatically re-requests that same page, showing the user the already-loaded 13
items plus a top/bottom loading indicator.

```kotlin
val paginator = mutablePaginator<Item>(capacity = 20) {
    load { page -> LoadResult(api.fetch(page)) }
}
// Got 13 instead of 20 — on the next goNextPage, the page will be re-requested.
```

### 5. "We're on KMP, we need shared code"

This paragraph is short. Paging 3 is part of AndroidX. It doesn't run on iOS. In KMP projects this
means you're still writing pagination logic for Android and iOS separately, and the shared
`commonMain` remains without it.

Paginator is written in pure Kotlin, with no platform-specific dependencies, and is available as a
KMP artifact:

```kotlin
commonMain.dependencies {
    implementation("io.github.jamal-wia:paginator:8.1.0")
}
```

Targets: Android, JVM, `iosX64`, `iosArm64`, `iosSimulatorArm64`. Written once in `commonMain` —
works everywhere.

### 6. "App was killed — bring me back exactly where I was"

Process death on Android is a reality, not an edge case. The user was on page 5, minimized the app,
their process was killed for a background map, the user returned — and the feed scrolled back to
page one, because the cache is gone.

Paginator has built-in state serialization via `kotlinx.serialization`:

```kotlin
// Save
savedStateHandle["paginator"] = paginator.saveState()

// Restore
savedStateHandle.get<PaginatorSnapshot<Item>>("paginator")
    ?.let { paginator.restoreState(it) }
```

---

## Meet Paginator

The basic introduction was already above — those same three lines of setup. I'll repeat them to have
everything in one place:

```kotlin
val paginator = mutablePaginator<Item> {
    load { page -> LoadResult(repository.loadPage(page)) }
}

paginator.uiState.collect { state ->
    when (state) {
        PaginatorUiState.Idle -> Unit
        PaginatorUiState.Loading -> showFullscreenLoader()
        PaginatorUiState.Empty -> showEmptyScreen()
        is PaginatorUiState.Error -> showError(state.error)
        is PaginatorUiState.Content -> render(state)
    }
}
```

The library then has two levels of depth. You can stay at this setup and get full-featured
pagination with all states. Or, when needed, plug in extras:

- Two-level cache (L1 in memory + your L2, usually Room).
- L1 eviction strategies — LRU, FIFO, TTL, sliding window, composable via the `+` operator.
- Transactions with rollback via `transaction { }`.
- Dirty pages — silent background page refresh on next navigation.
- Bookmarks and `jumpForward` / `jumpBack` with wraparound.
- Scroll-based prefetch controller.
- State serialization via `kotlinx.serialization`.
- Pluggable logger.

All of this is opt-in. For a simple feed, none of the above is needed.

How it works internally — why `PageState` is a sealed type, how the page context window works, why
there are three layers `PagingCore` / `Paginator` / `MutablePaginator`, how cache strategies
compose — we'll cover that in the next articles in the series. The goal here was different: to show
that there's a tool that covers both the simple and the complex, and does it more concisely.

---

## Conclusion

Paging 3 is a solid library. It does what it was built for. But:

- Even for a plain infinite feed, its configuration requires more code and more concepts than
  Paginator.
- For scenarios like page jumping, bidirectional pagination, item editing, incomplete backend
  responses, and state restoration after process death — Paging 3 either requires significant
  workarounds or doesn't cover the case at all.
- KMP projects aren't supported by Paging 3 at all.

Paginator covers everything listed out of the box while not making you pay in complexity for the
simple cases.

If pagination is a regular part of your work, give it a try. The repository is active,
on [Maven Central](https://central.sonatype.com/artifact/io.github.jamal-wia/paginator), mature (
current version 8.1.0), and covered with documentation. Feedback and stars help.

- **GitHub:** [github.com/jamal-wia/Paginator](https://github.com/jamal-wia/Paginator)
- **Maven Central:** `io.github.jamal-wia:paginator:8.1.0`
- **Telegram community:** [t.me/+0eeAM-EJpqgwNGZi](https://t.me/+0eeAM-EJpqgwNGZi)
- **Documentation:** by section in [docs/](https://github.com/jamal-wia/Paginator/tree/master/docs)

In the next article in the series — we'll dig into the architecture: why three layers, how
`PageState` and the context window work, how cache strategies compose, and why transactions use
savepoints instead of event sourcing.
