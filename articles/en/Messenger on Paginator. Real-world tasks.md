In my previous article I compared Paginator with Paging 3 at a toy level: "here's a simple feed,
look — three lines instead of thirty." That's useful for a first introduction, but it doesn't answer
the main question: **how does it hold up when the product starts demanding things people usually end
up writing their own layer on top of Paging 3 for?**

In this article I take a messenger app — because a messenger is an honest proving ground. It has:

- a message feed with upward and downward loading,
- automatic prefetch on scroll (no "Load more" buttons),
- new messages from WebSocket in real time,
- optimistic send with rollback on error,
- edit and delete,
- deeplink to a specific message and jumps to pinned messages,
- date separators and a "New messages" banner,
- transactional edits (multiple changes atomically, with server-side rollback),
- offline support that survives process death.

Nine real-world tasks. One ViewModel. No workarounds.

## Disclaimer about cursor pagination

Before we start: if your backend returns messages not by page number but by `nextCursor` /
`prevCursor` (GraphQL connections, Slack API, Instagram, Reddit, and other feeds with a "moving
edge") — you need not `Paginator` but its cursor sibling `CursorPaginator`.

It's a separate type because cursors and Int indexes live by different rules: a cursor has no "page
42", no random-access jumps to an arbitrary number, no `resize(capacity)`. Instead it has
`CursorBookmark(prev, self, next)` and a LinkedList model where each page knows only its neighbors.

The API is a mirror image:

```kotlin
val paginator = mutableCursorPaginator<Message>(capacity = 50) {
    load { cursor ->
        val page = api.getMessages(cursor?.self as? String)
        CursorLoadResult(
            data = page.items,
            bookmark = CursorBookmark(
                prev = page.prevCursor,
                self = page.selfCursor,
                next = page.nextCursor,
            ),
        )
    }
}
```

The same `uiState`, `jump`, `goNextPage`, `interweave`, `transaction`, L2 cache — all present. The
patterns from this article transfer one-to-one; only the key changes (`Int` → `self: Any`). Details
are in
the [separate documentation](https://github.com/jamal-wia/Paginator/blob/main/docs/13.%20cursor-pagination.md).

The rest of this article uses plain `Paginator`. We'll assume the backend serves
`GET /chats/:id/messages?page=N`.

## Task 0: Setup

```kotlin
class ChatViewModel(
    private val api: ChatApi,
    private val chatId: String,
) : ViewModel() {

    private val paginator = mutablePaginator<Message>(capacity = 50) {
        load { page ->
            val response = api.getMessages(chatId, page)
            this.finalPage = response.totalPages  // learn the feed boundary on first load
            LoadResult(response.items)
        }
    }

    val uiState = paginator.uiState
        .stateIn(viewModelScope, SharingStarted.Eagerly, PaginatorUiState.Idle)

    init {
        viewModelScope.launch { paginator.restart() }
    }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }
}
```

Three lines — and we already have a state machine with
`Idle / Loading / Empty / Error / Content(items, prependState, appendState)`. In the UI this becomes
a five-line `when` and a LazyColumn. The first task is closed before we even had time to state it.

Note `this.finalPage = response.totalPages` inside `load`: the lambda's receiver is the paginator
itself, so we assign `finalPage` right there, without observing `uiState` or manual synchronization.
When `goNextPage` tries to jump past the boundary it throws `FinalPageExceededException`, and the UI
shows a "Start of conversation" banner.

## Task 1: History and upward loading

The user opened a chat. We need to show the last 50 messages, and load older ones when scrolling up.

Question for `Paginator`: **which direction is up and which is down?** A messenger has an inverted
axis: "page 1" is the freshest messages, "page 2" is older. So `goNextPage` in our case means "load
older history."

```kotlin
fun onScrolledToTop() {
    viewModelScope.launch { paginator.goNextPage() }
}

fun onSwipeToRefresh() {
    viewModelScope.launch { paginator.restart() }
}
```

`goNextPage` knows what a "filled" page is (the server returned `capacity` elements) versus an "
underfilled" one (fewer were returned). If the server returned an underfilled page, the next call to
`goNextPage` **won't skip ahead** but will re-request the same page via `isFilledSuccessState` — in
case the backend sent more since then. On top of this the UI already has a `ProgressPage` with
previously cached data, so the user sees old content and a loading indicator at the same time. This
comes out of the box — nothing to write by hand.

## Task 2: Prefetch — loading without "More" buttons

Manual `onScrolledToTop` in 2026 is an anachronism. Modern UX: the paginator should start fetching
the next page **several screens before** the user reaches the edge.

For this there is `PaginatorPrefetchController` — a platform-independent controller that accepts
information about visible items and calls `goNextPage` / `goPreviousPage` itself:

```kotlin
private val prefetch = paginator.prefetchController(
    scope = viewModelScope,
    prefetchDistance = 10,           // start loading 10 items before the edge
    enableBackwardPrefetch = true,   // upward too (history) and downward (if backend provides)
)

fun onScroll(firstVisible: Int, lastVisible: Int, total: Int) {
    prefetch.onScroll(firstVisible, lastVisible, total)
}
```

In the UI — minimal:

```kotlin
val listState = rememberLazyListState()

LaunchedEffect(listState) {
    snapshotFlow {
        Triple(
            listState.firstVisibleItemIndex,
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            listState.layoutInfo.totalItemsCount,
        )
    }.collect { (first, last, total) -> viewModel.onScroll(first, last, total) }
}
```

Important things the controller does:

- **The first `onScroll` is calibration.** The paginator records the starting position and doesn't
  begin loading — to avoid a false fetch on the first appearance of the screen.
- **Silent loading.** By default `silentlyLoading = true` — meaning `ProgressPage` is not emitted.
  The UI doesn't flash "Loading" every time the user approaches the edge.
- **Respects `finalPage`.** Once the end of the feed is reached, prefetch stops — no spurious
  requests into the void.
- **Respects dirty pages.** If a page in the context window is marked stale (e.g., after offline
  editing), the next prefetch triggers a background refresh of those pages in parallel.
- **Easy to disable.** Modal dialog? `prefetch.enabled = false`, and the controller is silent until
  you re-enable it.

After `jump` or `restart` the list state changes completely — we need to reset calibration:

```kotlin
fun openDeeplink(messageId: String) {
    viewModelScope.launch {
        val location = api.locate(chatId, messageId)
        paginator.jump(BookmarkInt(location.page))
        prefetch.reset()  // next onScroll becomes the calibration call
    }
}
```

One line of setup in the ViewModel, one line of integration in LazyColumn — and infinite scroll
works "on its own." Try reproducing this behavior in Paging 3 without loading indicators in the
middle of the list. Let's see how long that takes.

## Task 3: New message from WebSocket

A push arrives: `{"type": "message.new", "message": {...}}`. We need to insert it at the very top (
in our axis — page 1, index 0), without re-fetching the feed.

```kotlin
fun onWebSocketMessage(msg: Message) {
    paginator.addAllElements(
        elements = listOf(msg),
        targetPage = 1,
        index = 0,
    )
}
```

What happens internally:

1. The message is inserted into page=1 at position 0.
2. Page=1 already contains `capacity=50` elements — so after insertion there are 51. The overflow
   cascades forward: the last element of page=1 moves to the start of page=2, the last of page=2 to
   the start of page=3, and so on through the chain of cached pages. The invariant "no more than
   `capacity` elements per page" is maintained automatically.

That's it. One line per WebSocket event, the library handles the capacity invariant itself. In
Paging 3 this required a `RemoteMediator` + manual Room work + `invalidate()` + flickering — and it
still came out wrong.

## Task 4: Optimistic send

The user pressed "Send." The message should **appear instantly** in the feed with a "sending" badge,
and when the server responds — replace it with the real one with a server-assigned ID. If the server
returns an error — show a "not sent" badge with a retry button.

This is where something I only briefly mentioned in the first article becomes useful: **`PageState`
is an open hierarchy**. We can define our own page and element types.

For the element, a status field is enough:

```kotlin
data class Message(
    val id: String,          // local UUID before confirmation, server ID after
    val text: String,
    val createdAt: Instant,
    val status: MessageStatus = MessageStatus.Sent,
)

enum class MessageStatus { Sending, Sent, Failed }
```

The send flow itself:

```kotlin
fun sendMessage(text: String) {
    val localId = Uuid.random().toString()
    val pending = Message(
        id = localId,
        text = text,
        createdAt = Clock.System.now(),
        status = MessageStatus.Sending,
    )

    // 1. Optimistic insert
    paginator.addAllElements(listOf(pending), targetPage = 1, index = 0, isDirty = true)

    viewModelScope.launch {
        runCatching { api.send(chatId, text) }
            .onSuccess { serverMsg ->
                // 2. Replace the pending message with the server message
                paginator.updateWhere(
                    predicate = { it.id == localId },
                    transform = { serverMsg.copy(status = MessageStatus.Sent) },
                )
            }
            .onFailure {
                // 3. Mark as failed
                paginator.updateWhere(
                    predicate = { it.id == localId },
                    transform = { it.copy(status = MessageStatus.Failed) },
                )
            }
    }
}
```

`updateWhere` is an extension on `MutablePaginator` that walks all pages in cache and replaces
elements matching the predicate. Returns the count of affected elements. For our case it's O(1) per
page (the pending message was just inserted into page=1, the search finds it immediately), but even
searching the whole chat — that's a few pages of 50 elements, not an issue.

You can go further and define a custom `PageState` that the UI distinguishes from a regular Success:

```kotlin
class PendingSendPage<T>(
    page: Int,
    data: List<T>,
    val pendingIds: Set<String>,
) : PageState.SuccessPage<T>(page, data)
```

But for 90% of cases a status field on the element is enough.

## Task 5: Edit and delete

The user opened the message menu and tapped "Edit." We send the request to the server, get the
updated message back, and patch it:

```kotlin
fun editMessage(messageId: String, newText: String) {
    viewModelScope.launch {
        val updated = api.edit(messageId, newText)
        paginator.updateWhere(
            predicate = { it.id == messageId },
            transform = { updated },
        )
    }
}
```

Delete:

```kotlin
fun deleteMessage(messageId: String) {
    viewModelScope.launch {
        api.delete(messageId)
        paginator.removeAll { it.id == messageId }
    }
}
```

There's a nice detail worth mentioning here. When we remove an element from the middle of a page,
`capacity - 1` elements remain on that page. On the next `goNextPage` call the library checks this
through `isFilledSuccessState` and — if the page became underfilled — fetches the missing element
from the next cached page. The invariant "a page has either `capacity` elements or we're at the
tail" is maintained automatically.

In Paging 3, the same scenario would require writing a custom `RemoteMediator`, triggering
`invalidate()`, and hoping for correct scroll restoration. Here — two lines.

## Task 6: Transaction — multiple edits atomically

Sometimes we change several things at once and want either all of them to apply or none. The classic
example is **"Mark chat as read"**: all unread messages in the list should become read, the counter
in the header should reset to zero, the "N new" banner should disappear — and all of this must be
confirmed by the server. If the server fails — we roll back to the previous state **completely**,
with no half-measures.

`Paginator` has `transaction { }` for this — an atomic block with a deep-copy savepoint under the
hood. If any exception is thrown inside (including `CancellationException`), the entire state rolls
back: cache, context window, dirty flags, capacity, finalPage, bookmarks, lock flags. Everything.

```kotlin
fun markChatRead() {
    viewModelScope.launch {
        try {
            paginator.transaction {
                // 1. Optimistically mark all loaded messages as read
                (this as MutablePaginator).updateAll { msg ->
                    if (msg.isRead) msg else msg.copy(isRead = true)
                }

                // 2. Send to server. If it fails — transaction rolls back updateAll
                api.markChatRead(chatId)
            }
            // 3. Success — the counter already reacted to updateAll via uiState
        } catch (e: IOException) {
            showError("Failed to mark as read")
            // No manual rollback needed — transaction already reverted everything
        }
    }
}
```

What it would look like without `transaction`:

1. Call `updateAll { ... }` on L1 — UI updated.
2. Catch a server error.
3. **Manually** revert all elements. But we no longer know which ones were `isRead = false` and
   which were `isRead = true` before the call — their state was overwritten.
4. Trigger `refresh` on the entire visible window, wait for the network, UI flickers, the user
   sees "blinking" read markers.

With `transaction` none of this happens: the optimistic change is applied instantly, and if
something breaks — the state returns **bit-for-bit** to what it was before the block.

A more brutal scenario — **forwarding several messages to another chat while simultaneously deleting
them from the current one**:

```kotlin
fun forwardAndDelete(messageIds: List<String>, targetChatId: String) {
    viewModelScope.launch {
        try {
            paginator.transaction {
                val mp = this as MutablePaginator<Message>

                // 1. Optimistically remove from the current chat
                val removed = mp.removeAll { it.id in messageIds }
                check(removed == messageIds.size) { "not all messages found in cache" }

                // 2. Navigation inside a transaction is allowed (!)
                //    jump/goNext/refresh work without deadlock — mutex
                // 3. Send to server
                api.forward(messageIds, targetChatId)

                // If forward fails — removeAll rolls back, messages return to the feed
            }
        } catch (e: Exception) {
            showError("Failed to forward")
        }
    }
}
```

One more nice detail: `transaction` automatically calls `flush()` on success. So if you have L2
connected — all changes that happened inside the block are atomically written to the database after
success. If the block failed — L2 was never touched. "Eventual consistency" at Room quality from a
single line.

## Task 7: Deeplink and jump to pinned message

The user tapped a notification: "Reply in chat X to message msg_42." The app opens; we need to **not
just open the chat, but scroll to the target message** — with context around it.

The backend can return "which page this message is on":
`GET /chats/:id/locate/:messageId → {page: 7}`.

```kotlin
fun openDeeplink(messageId: String) {
    viewModelScope.launch {
        val location = api.locate(chatId, messageId)
        paginator.jump(BookmarkInt(location.page))
        prefetch.reset()  // the list changes completely — re-calibrate
    }
}
```

After `jump` the following happens: the context window (`startContextPage..endContextPage`) is
rebuilt around page 7. The snapshot the UI receives will contain pages 6, 7, 8 — the message with
context "before" and "after." If the user scrolls up after jumping, `goPreviousPage` loads pages 5,
4, 3 — and when it reaches a page that was already cached (if scrolled there before) — **the windows
merge without duplicates**, because the cache is keyed by `page: Int` and page 3 is always the same
page 3.

For **pinned messages** the mechanism is the same, but with bookmarks. The backend returns the list
of pinned messages along with their pages:

```kotlin
viewModelScope.launch {
    val pinned = api.getPinned(chatId)  // List<{messageId, page}>
    paginator.bookmarks.clear()
    paginator.bookmarks.addAll(pinned.map { BookmarkInt(it.page) })
    paginator.recyclingBookmark = true
}
```

And in the UI — two buttons, "next pin" / "previous pin":

```kotlin
fun nextPinned() = viewModelScope.launch { paginator.jumpForward() }
fun prevPinned() = viewModelScope.launch { paginator.jumpBack() }
```

`jumpForward` / `jumpBack` take care of not jumping to a pin that's already visible on screen. The
user navigates between pins, the context around each loads automatically, the windows merge.

> Small aside: if your backend returns not `{page: 7}` but a cursor `msg_abc123` — that's exactly
> the case for `CursorPaginator`. There it's
`jump(CursorBookmark(prev = null, self = "msg_abc123", next = null))`, and the server fills in the
> real `prev`/`next` in the response.

## Task 8: Date separators and the "New messages" banner

Classic chat UX: messages grouped by day, with a "Today", "Yesterday", "April 17" separator. Plus a
bold "N new messages" banner at the boundary of unread content.

This **is not a paginator task**. The paginator operates on pages and elements; separators are a UI
concept that should be inserted between elements of the final stream. But the library provides a
clean tool for this — `Interweaver`.

```kotlin
sealed interface ChatRow {
    data class Msg(val m: Message) : ChatRow
    data class DateSeparator(val day: LocalDate) : ChatRow
    data class UnreadBanner(val count: Int) : ChatRow
}

val chatRows: Flow<List<ChatRow>> = paginator.uiState
    .interweave { prev, curr, index ->
        buildList {
            // "New" banner — between read and unread messages
            if (prev != null && prev.isRead && !curr.isRead) {
                add(WovenEntry.Inserted(ChatRow.UnreadBanner(unreadCount)))
            }
            // Day separator
            val prevDay = prev?.createdAt?.toLocalDate()
            val currDay = curr.createdAt.toLocalDate()
            if (prevDay != currDay) {
                add(WovenEntry.Inserted(ChatRow.DateSeparator(currDay)))
            }
            add(WovenEntry.Original(ChatRow.Msg(curr)))
        }
    }
```

The weaver is a pure function "previous element, current element → what to insert." The paginator
knows nothing about separators; the UI receives a ready `Flow<List<ChatRow>>`. When a page loads,
the flow is recalculated automatically, and separators land exactly where they should.

Important: this same mechanism works verbatim for `CursorPaginator` — `interweave` is implemented at
the `PaginatorUiState` level, which doesn't care how pages are addressed.

## Task 9: Offline-first

This is the finale. The user opens a chat while on the subway — something should appear. They killed
the app, opened it half an hour later — it should open in the same place, with the same scroll
position. They edited a message while offline — the change should sync when the connection returns.
And all of this — without UI flickering.

This is the largest task in the article because it sits at the intersection of several mechanisms:
L2 cache, dirty tracking, process death, warm-up, refresh. Let's break it down by layer.

### 9.1. L2 cache on top of Room

The library doesn't write to the database itself — it provides the `PersistentPagingCache<T>`
interface with five methods: `save`, `load`, `loadAll`, `remove`, `clear`. The implementation is on
your side. A boilerplate Room backend looks like this:

```kotlin
@Entity(tableName = "messages_pages")
data class PageEntity(
    @PrimaryKey val page: Int,
    val chatId: String,
    val dataJson: String,
    val isEmpty: Boolean,
    val updatedAt: Long,
)

@Dao
interface PageDao {
    @Upsert
    suspend fun upsert(entity: PageEntity)

    @Query("SELECT * FROM messages_pages WHERE chatId = :chatId AND page = :page")
    suspend fun get(chatId: String, page: Int): PageEntity?

    @Query("SELECT * FROM messages_pages WHERE chatId = :chatId ORDER BY page")
    suspend fun getAll(chatId: String): List<PageEntity>

    @Query("DELETE FROM messages_pages WHERE chatId = :chatId AND page = :page")
    suspend fun delete(chatId: String, page: Int)

    @Query("DELETE FROM messages_pages WHERE chatId = :chatId")
    suspend fun clear(chatId: String)
}

class RoomMessagesCache(
    private val dao: PageDao,
    private val chatId: String,
) : PersistentPagingCache<Message> {

    private val serializer = ListSerializer(Message.serializer())

    override suspend fun save(state: PageState<Message>) {
        dao.upsert(
            PageEntity(
                page = state.page,
                chatId = chatId,
                dataJson = Json.encodeToString(serializer, state.data),
                isEmpty = state.isEmptyState(),
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            )
        )
    }

    override suspend fun load(page: Int): PageState<Message>? {
        val entity = dao.get(chatId, page) ?: return null
        val data = Json.decodeFromString(serializer, entity.dataJson)
        return if (entity.isEmpty) EmptyPage(page, data)
        else SuccessPage(page, data.toMutableList())
    }

    override suspend fun loadAll(): List<PageState<Message>> =
        dao.getAll(chatId).mapNotNull { load(it.page) }

    override suspend fun remove(page: Int) = dao.delete(chatId, page)
    override suspend fun clear() = dao.clear(chatId)
}
```

Wire it up in the DSL:

```kotlin
private val paginator = mutablePaginator<Message>(capacity = 50) {
    load { page ->
        val response = api.getMessages(chatId, page)
        this.finalPage = response.totalPages
        LoadResult(response.items)
    }
    cache = LruPagingCache(maxSize = 20)        // L1: keep 20 pages in memory
    persistentCache = RoomMessagesCache(dao, chatId)  // L2: everything
}
```

And that's it. The chain then works automatically:

- **Read path**: L1 → L2 → network. On a memory cache miss the paginator checks Room, and if the
  page is there it is promoted to L1 and returned instantly. No network call, no loader shown.
- **Write path**: after each successful `load` the page is automatically written to L2. So
  everything the user has seen at least once is saved.

### 9.2. Warm-up on cold start

By default L2 is read **lazily** — only when the paginator needs a specific page. But for a chat
that's not what we want. We want the entire last-saved feed to be **immediately available** when the
app opens in offline mode, without "Loading...".

For this there is `warmUpFromPersistent()`:

```kotlin
init {
    viewModelScope.launch {
        val inserted = paginator.warmUpFromPersistent()
        if (inserted == 0) {
            // Cache is empty — this is the first time in the chat. Fetch from server.
            paginator.restart()
        } else {
            // There's cached data. Show immediately, refresh in the background.
            paginator.refresh(pages = paginator.core.affectedPages.toList())
        }
    }
}
```

`warmUpFromPersistent` returns the number of inserted pages and silently (without emitting a
snapshot) places them in L1. The next `jump/goNextPage` hits L1 directly, with no network request.

Nuance: if we have `LruPagingCache(maxSize = 20)` but Room holds 100 pages — only 20 make it into
L1 (the most recent ones, because warm-up goes through the normal `setState`). The other 80 stay in
L2 and are pulled in as the user scrolls.

### 9.3. Process death: `SavedStateHandle`

Android can kill the process at any moment. L2 survives that — but **scroll position, context
window, bookmarks, lock flags** live in the paginator's memory. We need to persist its state
entirely.

```kotlin
class ChatViewModel(
    private val api: ChatApi,
    private val chatId: String,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val paginator = mutablePaginator<Message>(capacity = 50) {
        load { page ->
            val response = api.getMessages(chatId, page)
            this.finalPage = response.totalPages
            LoadResult(response.items)
        }
        persistentCache = RoomMessagesCache(dao, chatId)
    }

    init {
        viewModelScope.launch {
            // 1. Try to restore a snapshot from SavedStateHandle (process death)
            val snapshot: String? = savedState[SNAPSHOT_KEY]
            if (snapshot != null) {
                paginator.restoreStateFromJson(snapshot, Message.serializer())
            } else {
                // 2. Try to warm up from Room (cold start)
                val inserted = paginator.warmUpFromPersistent()
                if (inserted == 0) paginator.restart()
            }
        }

        // Save a snapshot every time something changes
        paginator.uiState
            .debounce(500)
            .onEach {
                savedState[SNAPSHOT_KEY] = paginator.saveStateToJson(
                    elementSerializer = Message.serializer(),
                    contextOnly = true,   // only the visible pages
                )
            }
            .launchIn(viewModelScope)
    }

    companion object {
        private const val SNAPSHOT_KEY = "chat_paginator_snapshot"
    }
}
```

`contextOnly = true` is the key detail. Without it we'd serialize the entire cache (potentially
hundreds of pages), and the Bundle could exceed the `TransactionTooLargeException` limit (1MB). With
`contextOnly = true` only the current window's pages are saved — typically 3–5 pages, a hundred
kilobytes of JSON, fits without issue.

On restore:

- `ErrorPage` and `ProgressPage` are converted to `SuccessPage` / `EmptyPage` and marked dirty — so
  the paginator updates them on the next approach.
- Context window, bookmarks, lock flags, `finalPage` — restored as-is.

After `restoreStateFromJson` the paginator looks as if process death never happened — same scroll,
same context.

### 9.4. Dirty tracking and deferred sync

Now the most interesting part. The user is offline:

1. Edited a message — `updateWhere` with `isDirty = true` on the corresponding page.
2. Deleted a message — `removeAll` with `isDirty = true`.
3. Sent a new message — `addAllElements(... isDirty = true)`.

All these changes live in L1. They need to be:

- **Saved to L2**, so that killing the app doesn't lose them.
- **Sent to the server**, when the connection returns.

For L2 — `flush()`:

```kotlin
// After a batch of changes — explicit flush
paginator.flush()
```

Or automatically — inside `transaction { }`, flush is called on success.

`MutablePaginator` tracks changes itself: `affectedPages: Set<Int>` shows which pages were touched,
`hasPendingFlush: Boolean` — whether there is anything unsaved at all. Useful for a "unsaved
changes" UI indicator or for tests.

For the server — a separate mechanism at the repository level (we can't automatically know which API
to call for an "edited message"), but we have everything needed to build it:

```kotlin
fun onNetworkAvailable() {
    viewModelScope.launch {
        // 1. Sync the outgoing changes queue with your REST client
        outboxSyncer.syncAll()  // your custom code

        // 2. Refresh the visible context — new data may have arrived from the server
        val visiblePages = paginator.core.run { startContextPage..endContextPage }.toList()
        paginator.refresh(visiblePages)

        // 3. Just in case — flush L1 to L2
        paginator.flush()
    }
}
```

### 9.5. What we end up with

Let's put it all in one picture:

- **User riding the subway** → opens the chat. Immediately sees the last 20 pages — prefetch pulls
  in more from L2 as they scroll.
- **Writes something** → message inserted into L1 with `isDirty = true`, sits in memory.
- **Kills the app** → `SavedStateHandle` saved a snapshot of the current window.
- **Opens it an hour later** → `restoreStateFromJson` restores the window with the same scroll.
  Everything else — from L2.
- **Network returns** → `outboxSyncer.syncAll()` sends the deferred changes, `refresh` updates the
  visible window, `flush` writes the result to L2.

Not a single `invalidate()`. Not a single `Flow<PagingData>`. Not a single flicker.

## What we got

One ViewModel. Nine real-world tasks. Let's sum up:

| Task                             | Call                                               | Lines |
|----------------------------------|----------------------------------------------------|-------|
| History and upward loading       | `goNextPage()`, `restart()`                        | 2     |
| Prefetch                         | `prefetchController(...)` + `onScroll(...)`        | ~10   |
| New message from WebSocket       | `addAllElements(..., targetPage = 1)`              | 1     |
| Optimistic send                  | `addAllElements` + `updateWhere`                   | ~15   |
| Edit / delete                    | `updateWhere`, `removeAll`                         | 2     |
| Transaction                      | `transaction { updateAll + api.call() }`           | ~10   |
| Deeplink + pinned messages       | `jump(BookmarkInt)`, `bookmarks`, `jumpForward`    | ~8    |
| Date separators + "New messages" | `uiState.interweave { ... }`                       | ~15   |
| Offline-first + process death    | `warmUpFromPersistent`, `saveStateToJson`, `flush` | ~40   |

**Not a single `RemoteMediator`. Not a single `PagingSource`. Not a single `invalidate()`.**

And the best part — this is fully Kotlin Multiplatform code. The same ViewModel compiles for iOS,
and there `uiState` hooks up to SwiftUI through a thin adapter just as well. Paging 3 exits the chat
at this point, because it simply doesn't exist outside Android.

And if your backend returns cursors instead of page numbers — everything is exactly the same, only
`Paginator` becomes `CursorPaginator`, `BookmarkInt(N)` becomes `CursorBookmark(prev, self, next)`,
`targetPage = 1` becomes `targetSelf = headCursor`. The rest of the patterns work verbatim.

---

In the next article we'll look at **how it's built internally**: three layers (`PagingCore` /
`Paginator` / `MutablePaginator`), mutex instead of races, transactions with a savepoint for
rollback, and why `PageState` is sealed but all its subclasses are `open`. For those who enjoy
reading not just the API but the internals.

**If you liked it — a star on [GitHub](https://github.com/jamal-wia/Paginator) helps a lot, thank
you.**
