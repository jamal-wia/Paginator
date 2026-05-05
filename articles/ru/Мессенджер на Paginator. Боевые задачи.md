В прошлой статье я сравнивал Paginator с Paging 3 на кошачьем уровне: «вот простой фид, смотрите —
три строки вместо тридцати». Это полезно для первого знакомства, но не отвечает на главный вопрос: *
*а как оно себя поведёт, когда продукт начнёт требовать то, ради чего люди обычно и пишут свой
велосипед поверх Paging 3?**

В этой статье я беру мессенджер — потому что мессенджер это честный полигон. Там есть:

- лента сообщений с подгрузкой вверх и вниз,
- автоматическая подгрузка на скролле (prefetch) без кнопок «Загрузить ещё»,
- новые сообщения из WebSocket в реальном времени,
- optimistic send с откатом при ошибке,
- редактирование и удаление,
- deeplink на конкретное сообщение и прыжки на закреплённые,
- date-разделители и плашка «Новые сообщения»,
- транзакционные правки (несколько изменений атомарно, с откатом на сервере),
- работа оффлайн с переживанием process death.

Девять боевых задач. Одна ViewModel. Никаких костылей.

## Дисклеймер про курсорную пагинацию

Прежде чем начнём: если ваш бэкенд отдаёт сообщения не по номеру страницы, а по `nextCursor` /
`prevCursor` (GraphQL connections, Slack API, Instagram, Reddit и прочие ленты с «подвижным
краем»), — вам нужен не `Paginator`, а его курсорный брат `CursorPaginator`.

Это отдельный тип, потому что курсоры и Int-индексы живут по разным правилам: у курсора нет
«страницы 42», нет random-access прыжков на произвольный номер, нет `resize(capacity)`. Зато есть
`CursorBookmark(prev, self, next)` и LinkedList-модель, где страница знает только своих соседей.

API при этом — зеркальное:

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

Те же `uiState`, `jump`, `goNextPage`, `interweave`, `transaction`, L2-кэш — всё на месте. Паттерны
из этой статьи переносятся один-в-один, меняется только ключ (`Int` → `self: Any`). Детали —
в [отдельной документации](https://github.com/jamal-wia/Paginator/blob/main/docs/13.%20cursor-pagination.md).

Дальше в статье — всё на обычном `Paginator`. Будем считать, что бэкенд отдаёт
`GET /chats/:id/messages?page=N`.

## Задача 0: сетап

```kotlin
class ChatViewModel(
    private val api: ChatApi,
    private val chatId: String,
) : ViewModel() {

    private val paginator = mutablePaginator<Message>(capacity = 50) {
        load { page ->
            val response = api.getMessages(chatId, page)
            this.finalPage = response.totalPages  // узнаём границу ленты сразу при загрузке
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

Три строки — и у нас уже есть стейт-машина с
`Idle / Loading / Empty / Error / Content(items, prependState, appendState)`. В UI это превращается
в пятистрочный `when` и LazyColumn. Первая задача закрыта до того, как мы успели её поставить.

Обратите внимание на `this.finalPage = response.totalPages` внутри `load`: ресивер лямбды — сам
пагинатор, поэтому мы присваиваем `finalPage` прямо на месте, без наблюдения `uiState` и ручной
синхронизации. Когда `goNextPage` попытается прыгнуть за границу, он бросит
`FinalPageExceededException`, и UI покажет плашку «Начало переписки».

## Задача 1: история и подгрузка вверх

Юзер открыл чат. Нужно показать последние 50 сообщений, а при скролле вверх — подгрузить более
старые.

Вопрос к `Paginator`: **а где тут верх и где низ?** У мессенджера перевёрнутая ось: «страница 1» —
это самые свежие сообщения, «страница 2» — старее. То есть `goNextPage` в нашем случае означает
«грузи более старую историю».

```kotlin
fun onScrolledToTop() {
    viewModelScope.launch { paginator.goNextPage() }
}

fun onSwipeToRefresh() {
    viewModelScope.launch { paginator.restart() }
}
```

`goNextPage` знает, что такое «filled» страница (пришло `capacity` элементов) и «незаполненная» (
пришло меньше). Если сервер вернул незаполненную страницу, на следующий вызов `goNextPage` он **не
перескочит вперёд**, а повторно запросит ту же страницу через `isFilledSuccessState` — на случай,
если бэк дослал. Поверх этого в UI уже есть `ProgressPage` с ранее закэшированными данными, так что
пользователь увидит старый контент и индикатор загрузки одновременно. Это из коробки, писать руками
нечего.

## Задача 2: prefetch — подгрузка без кнопок «Ещё»

Ручной `onScrolledToTop` в 2026 году — анахронизм. Современный UX: пагинатор должен начать качать
следующую страницу **за несколько экранов до того**, как пользователь доскроллит до края.

Для этого есть `PaginatorPrefetchController` — платформо-независимый контроллер, принимающий
информацию о видимых элементах и сам вызывающий `goNextPage` / `goPreviousPage`:

```kotlin
private val prefetch = paginator.prefetchController(
    scope = viewModelScope,
    prefetchDistance = 10,           // начинаем грузить за 10 элементов до края
    enableBackwardPrefetch = true,   // и вверх тоже (история), и вниз (если бэк отдаёт)
)

fun onScroll(firstVisible: Int, lastVisible: Int, total: Int) {
    prefetch.onScroll(firstVisible, lastVisible, total)
}
```

В UI — минимальное:

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

Важные детали, которые делает контроллер:

- **Первый `onScroll` — калибровочный.** Пагинатор запомнит стартовую позицию и ничего не начнёт
  грузить — чтобы не было ложной подгрузки при первом появлении экрана.
- **Тихая подгрузка.** По умолчанию `silentlyLoading = true` — это значит, что `ProgressPage` не
  эмитится. UI не мигает «Loading» при каждом подлёте к краю.
- **Уважает `finalPage`.** Если дошли до конца ленты — prefetch останавливается, лишних запросов в
  пустоту не будет.
- **Уважает dirty pages.** Если какая-то страница в контекст-окне помечена как устаревшая (например,
  после оффлайн-редактирования), следующий prefetch запустит фоновой refresh этих страниц
  параллельно.
- **Легко выключается.** Модальный диалог? `prefetch.enabled = false`, и контроллер молчит, пока вы
  его не включите обратно.

После `jump` или `restart` состояние списка меняется полностью — нужно сбросить калибровку:

```kotlin
fun openDeeplink(messageId: String) {
    viewModelScope.launch {
        val location = api.locate(chatId, messageId)
        paginator.jump(BookmarkInt(location.page))
        prefetch.reset()  // следующий onScroll станет калибровочным
    }
}
```

Одна строка сетапа на ViewModel, одна строка интеграции в LazyColumn — и бесконечный скролл работает
«сам». Попробуйте воспроизвести это поведение на Paging 3 без загрузочных лоадеров в середине
списка. Посмотрим, сколько займёт.

## Задача 3: новое сообщение из WebSocket

Приходит пуш: `{"type": "message.new", "message": {...}}`. Нужно вставить на самый верх (в нашей
оси — на страницу 1, индекс 0), не перезапрашивая ленту.

```kotlin
fun onWebSocketMessage(msg: Message) {
    paginator.addAllElements(
        elements = listOf(msg),
        targetPage = 1,
        index = 0,
    )
}
```

Что тут происходит внутри:

1. Сообщение вставляется в page=1 на позицию 0.
2. Page=1 уже содержит `capacity=50` элементов — значит, после вставки их стало 51. Переполнение
   каскадирует вперёд: последний элемент page=1 уезжает в начало page=2, последний page=2 — в начало
   page=3, и так далее по цепочке закэшированных страниц. Инвариант «на странице не больше
   `capacity` элементов» держится автоматически.

Всё. Одна строка на событие WebSocket, library сама разбирается с capacity invariant. В Paging 3
такое делалось через `RemoteMediator` + ручная работа с Room + `invalidate()` + мерцание — и всё
равно получалось криво.

## Задача 4: optimistic send

Юзер нажал «Отправить». Сообщение должно **мгновенно появиться** в ленте с плашкой «отправляется», а
когда придёт ответ сервера — заменить его на настоящее с серверным id. Если сервер вернул ошибку —
показать плашку «не отправлено» с кнопкой ретрая.

Тут пригодится штука, про которую в первой статье я упоминал мельком: **`PageState` — open-иерархия
**. Мы можем завести свои типы страниц и элементов.

Для элемента достаточно поля статуса:

```kotlin
data class Message(
    val id: String,          // локальный UUID до подтверждения, серверный после
    val text: String,
    val createdAt: Instant,
    val status: MessageStatus = MessageStatus.Sent,
)

enum class MessageStatus { Sending, Sent, Failed }
```

Сам поток отправки:

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
                // 2. Заменяем pending на серверное сообщение
                paginator.updateWhere(
                    predicate = { it.id == localId },
                    transform = { serverMsg.copy(status = MessageStatus.Sent) },
                )
            }
            .onFailure {
                // 3. Помечаем как failed
                paginator.updateWhere(
                    predicate = { it.id == localId },
                    transform = { it.copy(status = MessageStatus.Failed) },
                )
            }
    }
}
```

`updateWhere` — extension на `MutablePaginator`, обходит все страницы в кэше и заменяет элементы по
предикату. Возвращает количество затронутых. Для нашего случая O(1) по страницам (pending только что
вставили в page=1, поиск найдёт его сразу), но даже если бы искали по всему чату — это несколько
страниц по 50 элементов, не проблема.

Можно пойти дальше и сделать кастомный `PageState`, который UI будет отличать от обычного Success:

```kotlin
class PendingSendPage<T>(
    page: Int,
    data: List<T>,
    val pendingIds: Set<String>,
) : PageState.SuccessPage<T>(page, data)
```

Но для 90% случаев достаточно статуса на элементе.

## Задача 5: редактирование и удаление

Юзер открыл меню сообщения, нажал «Изменить». Отправили на сервер, получили обновлённое — патчим:

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

Удаление:

```kotlin
fun deleteMessage(messageId: String) {
    viewModelScope.launch {
        api.delete(messageId)
        paginator.removeAll { it.id == messageId }
    }
}
```

Тут есть красивая деталь, о которой стоит сказать. Когда мы удаляем элемент из середины страницы, на
странице остаётся `capacity - 1` элемент. Дальше при `goNextPage` библиотека посмотрит на эту
ситуацию через `isFilledSuccessState` и — если страница стала незаполненной — дозаберёт недостающий
элемент из следующей закэшированной страницы. Инвариант «на странице либо `capacity` элементов, либо
мы на хвосте» держится автоматически.

В Paging 3 для того же сценария пришлось бы писать свой `RemoteMediator`, триггерить `invalidate()`,
надеяться на корректное восстановление скролла. Здесь — две строки.

## Задача 6: transaction — несколько правок атомарно

Бывает сценарий, когда мы меняем несколько вещей сразу и хотим, чтобы либо все они применились, либо
ни одна. Классика — **«Отметить чат как прочитанный»**: в списке сообщений все непрочитанные должны
стать прочитанными, счётчик в шапке должен обнулиться, плашка «N новых» должна исчезнуть, и всё это
должно быть подтверждено сервером. Если сервер упадёт — откатываемся на предыдущее состояние *
*полностью**, без полумер.

У `Paginator` для этого есть `transaction { }` — атомарный блок с deep-copy savepoint под капотом.
Если внутри бросится любое исключение (включая `CancellationException`), всё состояние откатится:
кэш, контекст-окно, dirty flags, capacity, finalPage, bookmarks, lock-флаги. Всё.

```kotlin
fun markChatRead() {
    viewModelScope.launch {
        try {
            paginator.transaction {
                // 1. Optimistic: помечаем все загруженные сообщения как прочитанные
                (this as MutablePaginator).updateAll { msg ->
                    if (msg.isRead) msg else msg.copy(isRead = true)
                }

                // 2. Шлём на сервер. Если упадёт — transaction откатит updateAll
                api.markChatRead(chatId)
            }
            // 3. Успех — счётчик уже реагировал на updateAll через uiState
        } catch (e: IOException) {
            showError("Не удалось отметить как прочитанные")
            // Ручной откат не нужен — transaction уже всё вернул
        }
    }
}
```

Что было бы без `transaction`:

1. Пишем `updateAll { ... }` на L1 — UI обновился.
2. Ловим ошибку сервера.
3. **Вручную** возвращаем все элементы обратно. Но мы уже не знаем, какие из них были
   `isRead = false`, а какие `isRead = true` до вызова — их состояние затёрлось.
4. Дёргаем `refresh` всего видимого окна, ждём сеть, UI мигает, пользователь видит «моргнувшие»
   метки прочтения.

С `transaction` ничего этого нет: оптимистичное изменение применяется мгновенно, и если что-то
ломается — состояние возвращается **бит-в-бит** к тому, каким оно было до блока.

Более злой сценарий — **пересылка нескольких сообщений в другой чат с одновременным удалением из
текущего**:

```kotlin
fun forwardAndDelete(messageIds: List<String>, targetChatId: String) {
    viewModelScope.launch {
        try {
            paginator.transaction {
                val mp = this as MutablePaginator<Message>

                // 1. Оптимистично удаляем из текущего чата
                val removed = mp.removeAll { it.id in messageIds }
                check(removed == messageIds.size) { "не все сообщения найдены в кэше" }

                // 2. Навигация внутри транзакции разрешена (!)
                //    jump/goNext/refresh работают без дедлока — mutex
                // 3. Шлём на сервер
                api.forward(messageIds, targetChatId)

                // Если forward упал — removeAll откатится, сообщения вернутся в ленту
            }
        } catch (e: Exception) {
            showError("Не удалось переслать")
        }
    }
}
```

Ещё одна приятная деталь: `transaction` внутри вызывает `flush()` автоматически на успехе. То есть
если у вас подключён L2 — все изменения, которые произошли внутри блока, атомарно запишутся в БД
после успеха. Если блок упал — L2 вообще не трогался. «Eventual consistency» уровня Room из одной
строки.

## Задача 7: deeplink и прыжок на закреп

Пользователь тапнул на уведомление: «Ответ в чате X на сообщение msg_42». Приложение открылось, надо
**не просто открыть чат, а проскроллить к нужному сообщению** — и чтобы вокруг него был контекст.

Бэкенд умеет отдавать «на какой странице лежит это сообщение»:
`GET /chats/:id/locate/:messageId → {page: 7}`.

```kotlin
fun openDeeplink(messageId: String) {
    viewModelScope.launch {
        val location = api.locate(chatId, messageId)
        paginator.jump(BookmarkInt(location.page))
        prefetch.reset()  // список меняется целиком — калибруемся заново
    }
}
```

После `jump` происходит следующее: контекст-окно (`startContextPage..endContextPage`)
перестраивается вокруг страницы 7. Снимок, который получит UI, будет содержать страницы 6, 7, 8 — то
есть сообщение с контекстом «до» и «после». Если юзер после прыжка начинает скроллить вверх,
`goPreviousPage` будет догружать 5, 4, 3 — и когда дойдёт до уже закэшированной (если ранее был
скролл оттуда) — **окна сомкнутся без дубликатов**, потому что кэш ключится по `page: Int` и
страница 3 — это всегда та же самая страница 3.

Для **закреплённых сообщений** механика та же, но с bookmarks. Бэк отдаёт список закреплённых вместе
с их страницами:

```kotlin
viewModelScope.launch {
    val pinned = api.getPinned(chatId)  // List<{messageId, page}>
    paginator.bookmarks.clear()
    paginator.bookmarks.addAll(pinned.map { BookmarkInt(it.page) })
    paginator.recyclingBookmark = true
}
```

И в UI — две кнопки «следующий закреп» / «предыдущий закреп»:

```kotlin
fun nextPinned() = viewModelScope.launch { paginator.jumpForward() }
fun prevPinned() = viewModelScope.launch { paginator.jumpBack() }
```

`jumpForward` / `jumpBack` сами следят, чтобы не прыгать на закреп, который уже виден на экране.
Юзер листает между закрепами, контекст вокруг каждого догружается сам, окна смыкаются.

> Небольшая сноска: если ваш бэкенд отдаёт не `{page: 7}`, а курсор `msg_abc123`, — это тот самый
> случай для `CursorPaginator`. Там это
`jump(CursorBookmark(prev = null, self = "msg_abc123", next = null))`, и сервер в ответе дорисует
> настоящие `prev`/`next`.

## Задача 8: date-разделители и плашка «Новые сообщения»

Классический UX чата: сообщения, сгруппированные по дням, с разделителем «Сегодня», «Вчера», «17
апреля». Плюс жирная плашка «N новых сообщений» на границе непрочитанного.

Это **не задача пагинатора**. Пагинатор оперирует страницами и элементами; разделители — это
UI-концепт, который должен вставляться между элементами финального потока. Но библиотека предлагает
для этого чистый инструмент — `Interweaver`.

```kotlin
sealed interface ChatRow {
    data class Msg(val m: Message) : ChatRow
    data class DateSeparator(val day: LocalDate) : ChatRow
    data class UnreadBanner(val count: Int) : ChatRow
}

val chatRows: Flow<List<ChatRow>> = paginator.uiState
    .interweave { prev, curr, index ->
        buildList {
            // Плашка «Новые» — между прочитанными и непрочитанными
            if (prev != null && prev.isRead && !curr.isRead) {
                add(WovenEntry.Inserted(ChatRow.UnreadBanner(unreadCount)))
            }
            // Разделитель дня
            val prevDay = prev?.createdAt?.toLocalDate()
            val currDay = curr.createdAt.toLocalDate()
            if (prevDay != currDay) {
                add(WovenEntry.Inserted(ChatRow.DateSeparator(currDay)))
            }
            add(WovenEntry.Original(ChatRow.Msg(curr)))
        }
    }
```

Weaver — это чистая функция «предыдущий элемент, текущий элемент → что вставить». Пагинатор про
разделители ничего не знает, UI получает готовый поток `List<ChatRow>`. Когда страница догружается —
поток пересчитывается автоматически, разделители встают туда, куда надо.

Важное: эта же механика дословно работает для `CursorPaginator` — `interweave` реализован на уровне
`PaginatorUiState`, которому всё равно, как адресуются страницы.

## Задача 9: оффлайн-first

Это финал. Юзер открывает чат в метро — должно что-то показаться. Убил приложение, открыл через
полчаса — должно открыться на том же месте, с тем же скроллом. В оффлайне отредактировал сообщение —
изменение должно синхронизироваться, когда вернётся сеть. И всё это — без мерцания UI.

Это самая большая задача в статье, потому что она на стыке нескольких механизмов: L2-кэш,
dirty-tracking, process death, warm-up, refresh. Разложим по слоям.

### 9.1. L2-кэш поверх Room

Библиотека сама ничего в БД не пишет — она предоставляет интерфейс `PersistentPagingCache<T>` с
пятью методами: `save`, `load`, `loadAll`, `remove`, `clear`. Реализация — на вашей стороне.
Шаблонный Room-бэкенд выглядит так:

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
        return SuccessPage(page, data)
    }

    override suspend fun loadAll(): List<PageState<Message>> =
        dao.getAll(chatId).mapNotNull { load(it.page) }

    override suspend fun remove(page: Int) = dao.delete(chatId, page)
    override suspend fun clear() = dao.clear(chatId)
}
```

Подключаем в DSL:

```kotlin
private val paginator = mutablePaginator<Message>(capacity = 50) {
    load { page ->
        val response = api.getMessages(chatId, page)
        this.finalPage = response.totalPages
        LoadResult(response.items)
    }
    cache = MostRecentPagingCache(maxSize = 20)   // L1: держим в памяти 20 страниц
    persistentCache = RoomMessagesCache(dao, chatId)  // L2: всё
}
```

И всё. Дальше цепочка работает автоматически:

- **Read-path**: L1 → L2 → network. На cache-miss в памяти — пагинатор заглядывает в Room, и если
  страница там есть, она промотируется в L1 и возвращается мгновенно. Сеть не дёргается, лоадер не
  показывается.
- **Write-path**: после каждого успешного `load` страница автоматически пишется в L2. То есть всё,
  что юзер видел хотя бы раз, — сохранено.

### 9.2. Warm-up на холодном старте

По умолчанию L2 читается **лениво** — только когда пагинатору нужна конкретная страница. Но для чата
это не то, что мы хотим. Мы хотим, чтобы при открытии приложения в оффлайне **вся последняя
сохранённая лента** была сразу доступна, без «Loading...».

Для этого есть `warmUpFromPersistent()`:

```kotlin
init {
    viewModelScope.launch {
        val inserted = paginator.warmUpFromPersistent()
        if (inserted == 0) {
            // Кэш пуст — это первый заход в чат. Тянем с сервера.
            paginator.restart()
        } else {
            // Есть закэшированное. Показываем немедленно, в фоне обновляем.
            paginator.refresh(pages = paginator.core.affectedPages.toList())
        }
    }
}
```

`warmUpFromPersistent` вернёт количество вставленных страниц и тихо (без эмита snapshot) разложит их
по L1. Следующий `jump/goNextPage` попадёт сразу в L1, без сетевого запроса.

Нюанс: если у нас `MostRecentPagingCache(maxSize = 20)`, а в Room лежит 100 страниц — в L1 попадут
только
20 (последние, потому что прогрев идёт через обычный `setState`). Остальные 80 останутся в L2 и
подтянутся по мере скролла.

### 9.3. Process death: `SavedStateHandle`

Android может прибить процесс в любой момент. L2 это, конечно, переживёт — но **позиция скролла,
контекст-окно, bookmarks, lock-флаги** живут в памяти пагинатора. Нужно сохранить его состояние
целиком.

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
            // 1. Пробуем восстановить снимок из SavedStateHandle (process death)
            val snapshot: String? = savedState[SNAPSHOT_KEY]
            if (snapshot != null) {
                paginator.restoreStateFromJson(snapshot, Message.serializer())
            } else {
                // 2. Пробуем прогреть из Room (cold start)
                val inserted = paginator.warmUpFromPersistent()
                if (inserted == 0) paginator.restart()
            }
        }

        // Сохраняем снимок каждый раз, когда что-то меняется
        paginator.uiState
            .debounce(500)
            .onEach {
                savedState[SNAPSHOT_KEY] = paginator.saveStateToJson(
                    elementSerializer = Message.serializer(),
                    contextOnly = true,   // только видимые страницы
                )
            }
            .launchIn(viewModelScope)
    }

    companion object {
        private const val SNAPSHOT_KEY = "chat_paginator_snapshot"
    }
}
```

`contextOnly = true` — ключевая деталь. Без неё мы бы серилизовали весь кэш (потенциально сотни
страниц), и Bundle мог бы превысить лимит TransactionTooLargeException (1MB). С `contextOnly = true`
сохраняются только страницы текущего окна — обычно 3-5 штук, сотня килобайт JSON, влезает без
проблем.

При восстановлении:

- `ErrorPage` и `ProgressPage` конвертируются в `SuccessPage` и помечаются dirty —
  чтобы при первом же подходе к ним пагинатор их обновил (страница с пустыми данными определяется
  через `isEmptyState()`).
- Контекст-окно, bookmarks, lock-флаги, `finalPage` — восстанавливаются как есть.

После `restoreStateFromJson` пагинатор выглядит так, как будто process death не было — тот же
скролл, тот же контекст.

### 9.4. Dirty-tracking и отложенная синхронизация

А теперь самое интересное. Юзер в оффлайне:

1. Отредактировал сообщение — `updateWhere` с `isDirty = true` на соответствующей странице.
2. Удалил сообщение — `removeAll` с `isDirty = true`.
3. Отправил новое сообщение — `addAllElements(... isDirty = true)`.

Все эти изменения лежат в L1. Их нужно:

- **Сохранить в L2**, чтобы при убийстве приложения они не потерялись.
- **Отправить на сервер**, когда вернётся сеть.

Для L2 — `flush()`:

```kotlin
// После пачки изменений — явный flush
paginator.flush()
```

Либо автоматически — внутри `transaction { }` flush вызывается сам на успехе.

`MutablePaginator` сам трекает изменения: `affectedPages: Set<Int>` показывает, какие страницы были
тронуты, `hasPendingFlush: Boolean` — есть ли вообще что-то незасейвленное. Это полезно для
UI-индикатора «несохранённые изменения» или для тестов.

Для сервера — отдельный механизм на уровне репозитория (мы не можем автоматически знать, какой API
вызвать для «отредактированного сообщения»), но у нас есть всё, чтобы его построить:

```kotlin
fun onNetworkAvailable() {
    viewModelScope.launch {
        // 1. Синхронизируем очередь исходящих изменений со своим REST-клиентом
        outboxSyncer.syncAll()  // ваш кастомный код

        // 2. Обновляем видимый контекст — вдруг с сервера прилетело что-то новое
        val visiblePages = paginator.core.run { startContextPage..endContextPage }.toList()
        paginator.refresh(visiblePages)

        // 3. На всякий случай — flush L1 в L2
        paginator.flush()
    }
}
```

### 9.5. Что в итоге работает

Соберём в одну картину:

- **Юзер едет в метро** → открывает чат. Тут же видит последние 20 страниц — prefetch подтягивает
  ещё из L2 по мере скролла.
- **Написал что-то** → сообщение вставлено в L1 с `isDirty = true`, лежит в памяти.
- **Убил приложение** → `SavedStateHandle` сохранил снимок текущего окна.
- **Открыл через час** → `restoreStateFromJson` поднял окно с тем же скроллом. Всё остальное — из
  L2.
- **Появилась сеть** → `outboxSyncer.syncAll()` отправил отложенные изменения, `refresh` обновил
  видимое окно, `flush` записал итог в L2.

Ни одного `invalidate()`. Ни одного `Flow<PagingData>`. Ни одного мерцания.

## Что у нас получилось

Один ViewModel. Девять боевых задач. Давайте соберём:

| Задача                               | Вызов                                              | Строк |
|--------------------------------------|----------------------------------------------------|-------|
| История и подгрузка вверх            | `goNextPage()`, `restart()`                        | 2     |
| Prefetch                             | `prefetchController(...)` + `onScroll(...)`        | ~10   |
| Новое сообщение из WebSocket         | `addAllElements(..., targetPage = 1)`              | 1     |
| Optimistic send                      | `addAllElements` + `updateWhere`                   | ~15   |
| Редактирование / удаление            | `updateWhere`, `removeAll`                         | 2     |
| Transaction                          | `transaction { updateAll + api.call() }`           | ~10   |
| Deeplink + закрепы                   | `jump(BookmarkInt)`, `bookmarks`, `jumpForward`    | ~8    |
| Date-разделители + «Новые сообщения» | `uiState.interweave { ... }`                       | ~15   |
| Оффлайн-first + process death        | `warmUpFromPersistent`, `saveStateToJson`, `flush` | ~40   |

**Ни одного `RemoteMediator`. Ни одного `PagingSource`. Ни одного `invalidate()`.**

И самое приятное — это полноценный Kotlin Multiplatform код. Тот же ViewModel компилируется под iOS,
и там `uiState` так же подцепится к SwiftUI через тонкий адаптер. Paging 3 на этом моменте просто
выходит из чата, потому что его нет вне Android.

А если ваш бэкенд отдаёт курсоры вместо номеров страниц — всё ровно то же самое, только `Paginator`
меняется на `CursorPaginator`, `BookmarkInt(N)` — на `CursorBookmark(prev, self, next)`,
`targetPage = 1` — на `targetSelf = headCursor`. Остальные паттерны работают дословно.

---

В следующей статье — разберём, **как это устроено изнутри**: три слоя (`PagingCore` / `Paginator` /
`MutablePaginator`), mutex вместо гонок, транзакции с savepoint для отката, и почему `PageState` —
sealed, но все его наследники `open`. Это для тех, кто любит читать не только API, но и
внутренности.

**Если понравилось — звезда на [GitHub](https://github.com/jamal-wia/Paginator) сильно помогает,
спасибо.**
