# API Reference

[← Back to README](../README.md)

## Table of Contents

- [Paginator Properties](#paginator-properties)
- [Paginator Methods](#paginator-methods)
- [MutablePaginator Methods (additional)](#mutablepaginator-methods-additional)
- [MutablePaginator Operators](#mutablepaginator-operators)

---

## Paginator Properties

**Direct `Paginator` properties:**

| Property             | Type                                          | Description                                    |
|----------------------|-----------------------------------------------|------------------------------------------------|
| `load`               | `suspend Paginator<T>.(Int) -> LoadResult<T>` | Data source lambda                             |
| `logger`             | `PaginatorLogger?`                            | Logging interface (`null` by default)          |
| `finalPage`          | `Int`                                         | Maximum allowed page (default `Int.MAX_VALUE`) |
| `bookmarks`          | `MutableList<Bookmark>`                       | Bookmark list (default: page 1)                |
| `recyclingBookmark`  | `Boolean`                                     | Wrap-around bookmark iteration                 |
| `lockJump`           | `Boolean`                                     | Lock jump operations                           |
| `lockGoNextPage`     | `Boolean`                                     | Lock forward navigation                        |
| `lockGoPreviousPage` | `Boolean`                                     | Lock backward navigation                       |
| `lockRestart`        | `Boolean`                                     | Lock restart                                   |
| `lockRefresh`        | `Boolean`                                     | Lock refresh                                   |

**Via `paginator.core` (`PagingCore`):**

| Property                   | Type                       | Description                        |
|----------------------------|----------------------------|------------------------------------|
| `core.snapshot`            | `Flow<List<PageState<T>>>` | Visible page states flow           |
| `core.capacity`            | `Int`                      | Expected items per page            |
| `core.isCapacityUnlimited` | `Boolean`                  | `true` if `capacity == 0`          |
| `core.pages`               | `List<Int>`                | All cached page numbers (sorted)   |
| `core.states`              | `List<PageState<T>>`       | All cached page states (sorted)    |
| `core.size`                | `Int`                      | Number of cached pages             |
| `core.dirtyPages`          | `Set<Int>`                 | Snapshot of all dirty page numbers |
| `core.startContextPage`    | `Int`                      | Left boundary of visible context   |
| `core.endContextPage`      | `Int`                      | Right boundary of visible context  |
| `core.isStarted`           | `Boolean`                  | `true` if context pages are set    |

## Paginator Methods

**Direct `Paginator` methods:**

| Method                                   | Returns                         | Description                                 |
|------------------------------------------|---------------------------------|---------------------------------------------|
| `jump(bookmark)`                         | `Pair<Bookmark, PageState<T>>`  | Jump to a page                              |
| `jumpForward()`                          | `Pair<Bookmark, PageState<T>>?` | Next bookmark                               |
| `jumpBack()`                             | `Pair<Bookmark, PageState<T>>?` | Previous bookmark                           |
| `goNextPage()`                           | `PageState<T>`                  | Load next page                              |
| `goPreviousPage()`                       | `PageState<T>`                  | Load previous page                          |
| `restart()`                              | `Unit`                          | Reset and reload page 1                     |
| `refresh(pages)`                         | `Unit`                          | Reload specific pages                       |
| `loadOrGetPageState(page, forceLoading)` | `PageState<T>`                  | Load or get cached page (inline)            |
| `release(capacity, silently)`            | `Unit`                          | Full reset                                  |
| `transaction(block)`                     | `R`                             | Atomic block with rollback on failure       |
| `saveState(contextOnly)`                 | `PaginatorSnapshot<T>`          | Full state snapshot (suspend)               |
| `restoreState(snapshot, silently)`       | `Unit`                          | Restore full state (suspend)                |
| `saveStateToJson(serializer, json)`      | `String`                        | Save full state as JSON (suspend, ext)      |
| `restoreStateFromJson(str, serializer)`  | `Unit`                          | Restore full state from JSON (suspend, ext) |

**Via `paginator.core` (`PagingCore`) methods:**

| Method                                        | Returns                    | Description                        |
|-----------------------------------------------|----------------------------|------------------------------------|
| `core.getStateOf(page)`                       | `PageState<T>?`            | Get cached page state              |
| `core.getElement(page, index)`                | `T?`                       | Get element by position            |
| `core.markDirty(page)` / `markDirty(pages)`   | `Unit`                     | Mark page(s) as dirty              |
| `core.clearDirty(page)` / `clearDirty(pages)` | `Unit`                     | Remove dirty flag from page(s)     |
| `core.clearAllDirty()`                        | `Unit`                     | Remove all dirty flags             |
| `core.isDirty(page)`                          | `Boolean`                  | Check if page is dirty             |
| `core.isFilledSuccessState(state)`            | `Boolean`                  | Check if page is complete          |
| `core.snapshot(pageRange?)`                   | `Unit`                     | Emit snapshot manually             |
| `core.scan(pagesRange)`                       | `List<PageState<T>>`       | Get pages in range                 |
| `core.walkWhile(pivot, next, predicate)`      | `PageState<T>?`            | Traverse pages                     |
| `core.findNearContextPage(start, end)`        | `Unit`                     | Find nearest context               |
| `core.asFlow()`                               | `Flow<List<PageState<T>>>` | Full cache flow                    |
| `core.resize(capacity, resize, silently)`     | `Unit`                     | Change capacity                    |
| `core.saveStateToJson(serializer)`            | `String`                   | Save core state as JSON (ext)      |
| `core.restoreStateFromJson(str, serializer)`  | `Unit`                     | Restore core state from JSON (ext) |

**Operators (on `Paginator`):**

| Expression               | Description             |
|--------------------------|-------------------------|
| `paginator[page]`        | Get page state          |
| `paginator[page, index]` | Get element             |
| `page in paginator`      | Check if page is cached |

## MutablePaginator Methods (additional)

| Method                                                | Returns         | Description                             |
|-------------------------------------------------------|-----------------|-----------------------------------------|
| `removeState(pageToRemove, silently)`                 | `PageState<T>?` | Remove a page                           |
| `setElement(element, page, index, silently, isDirty)` | `Unit`          | Replace element (optionally mark dirty) |
| `removeElement(page, index, silently, isDirty)`       | `T`             | Remove element (optionally mark dirty)  |
| `addAllElements(elements, targetPage, index, …)`      | `Unit`          | Insert elements (optionally mark dirty) |
| `replaceAllElements(providerElement, …, predicate)`   | `Unit`          | Bulk replace/remove across all pages    |
| `flush()`                                             | `Unit`          | Flush CRUD changes to L2 (suspend)      |

> **Note:** there is no direct `setState` method on `MutablePaginator`. Use
> `paginator.core.setState(state)` or the `paginator += state` operator.

## MutablePaginator Operators

| Operator                 | Description          |
|--------------------------|----------------------|
| `paginator += pageState` | Set page state in L1 |
| `paginator -= page`      | Remove page          |
