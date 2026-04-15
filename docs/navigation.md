# Navigation

[← Back to README](../README.md)

## Table of Contents

- [goNextPage](#gonextpage)
- [goPreviousPage](#gopreviouspage)
- [jump](#jump)
- [jumpForward / jumpBack](#jumpforward--jumpback)
- [restart](#restart)
- [refresh](#refresh)

---

## goNextPage

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

## goPreviousPage

Loads the page before the current `startContextPage`. Requires the paginator to be started.

```kotlin
suspend fun goPreviousPage(): PageState<T>
```

Behavior mirrors `goNextPage` but in the backward direction. Shows a loading indicator at the top.

## jump

Jumps directly to any page by bookmark:

```kotlin
suspend fun jump(bookmark: Bookmark): Pair<Bookmark, PageState<T>>
```

If the target page is already cached as a filled success page, returns immediately without
reloading. Otherwise, resets the context window to the target page and loads it.

## jumpForward / jumpBack

Navigate through the bookmark list:

```kotlin
suspend fun jumpForward(): Pair<Bookmark, PageState<T>>?  // null if no more bookmarks
suspend fun jumpBack(): Pair<Bookmark, PageState<T>>?     // null if no more bookmarks
```

When `recyclingBookmark = true`, the iterator wraps around.

## restart

Clears all cached pages, resets the context window to page 1, and reloads it:

```kotlin
suspend fun restart()
```

Ideal for swipe-to-refresh.

## refresh

Reloads specific pages in parallel without clearing the cache:

```kotlin
suspend fun refresh(pages: List<Int>)
```

Sets all specified pages to `ProgressPage` (preserving cached data), then reloads them concurrently.
Use the extension `refreshAll()` to refresh every cached page.
