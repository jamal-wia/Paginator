# Why I wrote Paginator instead of Paging 3

This is an article by the author of the library, so it won't be neutral. But it isn't a story
about a particular project either — it's an analysis of the kinds of tasks where, in my view,
Paging 3 starts to creak, and of how [Paginator](https://github.com/jamal-wia/Paginator) is built
to handle them. Paginator is a KMP pagination library for Android, iOS, JVM and Desktop. Below is
why it exists as a separate library rather than a fork or a wrapper around Paging 3.

## The scene where Paging 3 stops being convenient

Take the list of requirements that sooner or later land on any feed, chat or search-results
screen — usually not at the start, but a few months into the product:

— open a conversation (or a feed, or search results) on a specific element via a deeplink from a
push notification;
— let the user like an item or mark it as "read" so that exactly one element changes, with no
full-list refresh and no visual flicker;
— after a process death, return the user to exactly where they were, **with the entire visible
context**, not to the first page;
— add a "jump to newest" or "jump to bookmark" affordance that lands at an arbitrary point in
the feed;
— and the same, please, on iOS.

On any one of these points Paging 3 doesn't fold — there's `RemoteMediator`, there's `cachedIn`,
there are ways to work around things. But when they arrive as a bundle — and in modern products
they do arrive as a bundle — the workarounds take more time than the feature itself. At some
point a team notices that it's spending more time thinking about how to make `PagingSource`
address a page rather than a key than about how the screen should actually behave.

From that point on Paging 3 stops being a tool and becomes a constraint that the task is being
fitted to. That's a bad sign — and it's exactly the moment I tried to push further away when
designing Paginator.

## Where Paging 3 starts to give

I don't think Paging 3 is a bad library — it has a good core for a specific class of tasks. But
that core is built around a particular set of decisions, and those decisions hit a ceiling
sooner than one would like. Specifically, in four places.

**`PagingSource` addresses by keys, not by pages.** That makes sense when a feed is paged
forwards and backwards by cursors and the ordinal index of an item is irrelevant. But the moment
the task becomes "open the page containing message `msg_817`", it turns out you can only jump to
a page whose key you already know. The canonical answer is Room as the source of truth and
`RemoteMediator` synchronizing the database with the network. For projects that already have a
database, that's fine. If they don't, the database has to be introduced just so pagination
works. That's an inverted dependency: a product scenario starts dictating the architecture of
the data layer.

**`PagingData` is immutable and tailored for the UI.** When a task requires changing a single
element in the feed — toggling a like, updating a "read" status, deleting locally — there's no
direct path. `PagingData` is a stream of events for an adapter, not a structure you can tell
"replace the element with `id = 42`". The standard answer once again leads to Room: change a
row, invalidation triggers the adapter. If the backend is ephemeral and there is no database, it
has to be introduced. If you want to apply a mutation optimistically before the server responds,
you need extra flags on rows. Every additional requirement adds another layer to the solution.

**Pagination lives in the UI layer.** If you keep clean architecture in moderate doses,
pagination is domain behaviour, not UI. And if three screens share the same feed, it's
convenient to have one pagination object in a use case that three view models subscribe to. With
`Flow<PagingData<T>>` that becomes an exercise — the flow is one-shot, scope-bound, and dragging
it through layers means dragging UI abstractions into the domain along with it.

**iOS simply isn't there.** KMP targets are gradually appearing in the upstream paging-common
sources, which is good news for the future. But the published artifacts, the surrounding tooling
(Room, RecyclerView, Compose adapters) and the actual ecosystem are Android-first. When the iOS
team needs the same pagination behaviour as Android, there's no out-of-the-box answer.

Each of these four points is solvable on its own. All four together — not really.

## Why not a fork and not a wrapper

Before reaching for a separate library, the natural question is whether a patch or an overlay
would do. The short answer is no, and here's why.

The core of Paging 3 is built around a pull model of an event stream for the UI. To turn it into
an ordinary state object that lives in the domain and can be controlled from the outside
(`jump`, `replace`, `serialize`), almost every key type — `PagingSource`, `PagingData`,
`LoadStates` — would have to be reinvented. After that kind of rework, essentially nothing of
the original library remains except the name.

A wrapper doesn't work either: a wrapper around `Flow<PagingData<T>>` is still a wrapper around
an immutable stream. You can't expose element mutation, cache serialization or jumping to an
arbitrary page through it — because none of those operations exist underneath.

So Paginator isn't an alternative in the sense of "the same approach, done differently" — it's a
different model: a page is an addressable cell in a cache, the cache is an ordinary data
structure, and navigation is ordinary methods.

## Principle 1. Pages are addressable

In Paging 3 movement through a feed is movement through keys: "give me the page at this
cursor", "give me the next one". In Paginator a page is addressed directly — by number, by
cursor, or by a bookmark:

```kotlin
paginator.goNextPage()
paginator.goPreviousPage()
paginator.jump(BookmarkInt(page = 42))
paginator.jump(CursorBookmark(prev = null, self = "msg_817", next = null))
```

It's a small decision, but it changes a lot. Opening a conversation on a message from a push —
one line. Remembering the user's position and returning a day later — one line. Setting a named
bookmark "start of unread" and cycling between bookmarks with `jumpForward` / `jumpBack` is a
built-in mechanism, not something hand-rolled on top of adapter state.

Addressability has another consequence: pages can be stored in an ordinary sorted collection
and treated as a collection. The snapshot is an ordered list of loaded pages, and it can be
handed to the UI directly, with no intermediaries like `LazyPagingItems`. If you'd rather, you
can subscribe to a ready-made `paginator.uiState: Flow<PaginatorUiState<T>>` that already
collapses the snapshot into `Idle / Loading / Empty / Error / Content(items, prependState,
appendState)` for typical screens.

## Principle 2. Mutability on demand

Paginator exposes two interfaces, and the split between them is intentional.

`Paginator` is read-only navigation. It can be handed to the UI or to any component that
shouldn't change the data. There are no `set`, `replace` or `remove` methods in its public API —
the compiler guarantees that the place reading the data won't break a page.

`MutablePaginator` is the extension that adds CRUD operations on individual elements:

```kotlin
mutablePaginator.updateWhere(predicate = { it.id == 42 }, transform = { it.copy(liked = true) })
mutablePaginator.removeAll { it.deleted }
mutablePaginator.insertAfter(target = anchor, element = newMessage)
```

After every operation pages are automatically rebalanced according to `capacity`, the cache
stays consistent, and the snapshot is re-emitted. No invalidation, no "let's reload the page".
A single like changes a single element.

The same mechanism handles optimistic updates: apply the mutation locally, send the request,
roll it back through `transaction { }` on error — an atomic block where every change is rolled
back as a whole on any exception, including coroutine cancellation. That removes a whole class
of bugs around UI/server divergence at the moment of a network failure.

## Principle 3. State that survives

Process death is an old Android problem. `cachedIn(viewModelScope)` in Paging 3 lives exactly as
long as the scope, and the scope dies with the process. After the user comes back, scroll
position may be restored, but the data is fetched again — and you'd better hope the backend
returns it in the same order.

In Paginator the cache is an ordinary data structure, and from the start it was designed to be
serializable:

```kotlin
val saved: String = paginator.saveStateToJson(Message.serializer())
// ... process death ...
paginator.restoreStateFromJson(saved, Message.serializer())
```

Serialization runs through `kotlinx.serialization`, so it works on any KMP target — Android,
iOS, Desktop. The result isn't "return to the same scroll position" but "return to the same
state, with all visible pages and the current location". A user who opened a screen via a
deeplink, after their process gets killed, comes back to the same message with the same
neighbours around it — and without a network round-trip.

This isn't magic — it's a consequence of pagination state being stored separately from the
flow and not being tied to the UI lifecycle.

## Principle 4. A library, not a framework

Paginator is written in pure Kotlin with no platform dependencies. That means a few practical
things.

First, it lives in `commonMain`. The same pagination code runs on Android, iOS, Desktop and on
the server. Not "eventually" — today, in the published artifacts. Pagination logic becomes part
of the shared domain layer of a KMP project, with no copy-pasting between platforms and no two
separate "the way we do it here" implementations.

Second, it doesn't have to live in a ViewModel. Pagination is behaviour, not UI state, and it
fits naturally in a use case or a repository. One `Paginator` in the data layer — three view
models subscribed to it. The same `Paginator` can be passed to a worker for background
prefetching. It can be unit-tested without `runTest` boilerplate and without mocking AndroidX.

Third, it's easy to remove. If you decide tomorrow that something else suits you better,
Paginator is localized in one place in the architecture rather than smeared across the UI and
the database. That's a rarely discussed but important property of any library: not just being
easy to plug in, but being easy to take out.

## Principle 5. Cursors as a first-class peer, not a workaround

In Paging 3 cursor pagination is built as a special case of `PagingSource` with a string-typed
key. It works, but it requires you to assemble the `prev` / `self` / `next` model for
GraphQL-style connections by hand and to handle `EndOfPaginationReached` carefully.

In Paginator cursor pagination is a first-class peer: `CursorPaginator` sits next to
`Paginator`, with the same state model, the same `uiState`, the same `transaction { }`, the
same serialization. The only difference is addressing — instead of a page number, the triple
`prev / self / next` is used:

```kotlin
val messages = mutableCursorPaginator<Message> {
    load { cursor ->
        val page = api.getMessages(cursor?.self as? String)
        CursorLoadResult(
            data = page.items,
            bookmark = CursorBookmark(prev = page.prev, self = page.self, next = page.next),
        )
    }
}
```

For GraphQL connections, chats and activity streams that's the right model out of the box — no
reinventing the wheel on top of `LoadParams`.

## Where it stands today

At the time of writing, Paginator is published on Maven Central as version 8.6.2. Supported
targets are Android, JVM (Desktop / Server), iosX64, iosArm64, iosSimulatorArm64. There's a
separate `paginator-compose` artifact with bindings for Jetpack Compose / Compose Multiplatform
— it adds a single line of scroll-driven prefetch for `LazyColumn` / `LazyRow` /
`LazyVerticalGrid` and their variants, with no manual `LaunchedEffect` / `snapshotFlow`
plumbing.

Documentation lives in [`docs/`](https://github.com/jamal-wia/Paginator/tree/master/docs) in
the repository, broken down by topic — from core concepts to cursor pagination and
interweaving. If you'd rather just feel it, there's a
[demo APK](https://raw.githubusercontent.com/jamal-wia/Paginator/master/PaginatorDemo.apk).

## When you probably don't need Paginator

If you have a simple feed, the source of truth is Room, and the product requirements are
stable — scroll-down loading, pull-to-refresh, and nothing else on top is on the horizon —
Paging 3 is most likely already working for you, and switching just for the sake of switching
makes no sense. That's a different class of tasks, and in that class Paginator won't give you a
noticeable advantage.

## Links

- Repository: [github.com/jamal-wia/Paginator](https://github.com/jamal-wia/Paginator)
- A detailed real-world walkthrough — building a messenger:
  [Messenger on Paginator. Real-world tasks.](Messenger%20on%20Paginator.%20Real-world%20tasks.md)
- A condensed feature-by-feature comparison:
  [Paging 3 is good. Until you need something more.](Paging%203%20is%20good.%20Until%20you%20need%20something%20more.md)
- Documentation: [docs/](../../docs/)
