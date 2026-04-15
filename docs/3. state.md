# State, Transactions, and Locks

[← Back to README](../README.md)

## Table of Contents

- [Dirty Pages](#dirty-pages)
    - [Marking Pages Dirty](#marking-pages-dirty)
    - [Clearing Dirty Flags](#clearing-dirty-flags)
    - [Querying Dirty State](#querying-dirty-state)
    - [How It Works](#how-it-works)
- [Reactive State](#reactive-state)
    - [Snapshot Flow](#snapshot-flow)
    - [Cache Flow](#cache-flow)
- [Transaction (Atomic Operations)](#transaction-atomic-operations)
- [Lock Flags](#lock-flags)

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

## Transaction (Atomic Operations)

The `transaction` method executes a block of operations atomically: if anything inside the block
throws an exception (including `CancellationException`), the paginator's entire state is rolled
back to the point before the block was entered.

```kotlin
paginator.transaction {
  val mp = this as MutablePaginator<Item>
  mp.setElement(updatedItem, page = 1, index = 0)
  mp.removeElement(page = 2, index = 3)
  mp.addAllElements(listOf(newItem), targetPage = 3, index = 0)
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
