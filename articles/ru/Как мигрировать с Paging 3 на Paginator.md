# Как мигрировать с Paging 3 на Paginator

Это практическое руководство для тех, у кого в проекте уже стоит Jetpack Paging 3, и кто думает
переехать на [Paginator](https://github.com/jamal-wia/Paginator). Не «почему стоит переехать» —
про это есть отдельные [статьи](https://github.com/jamal-wia/Paginator/tree/master/articles/ru),
а конкретный план: что во что превращается, что выкидывается, что остаётся, и в каком порядке это
двигать, чтобы не сломать прод по дороге.

Статья построена вокруг переноса одного экрана. Если у вас десять — повторите шаги десять раз,
большая картинка не меняется. Миграция инкрементальная: Paginator не требует выкидывать Paging 3
из всего проекта одним коммитом, экраны можно переводить по одному.

## Прежде чем начинать

Подключите Paginator. Через BOM, чтобы все артефакты держались на одной версии:

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:paginator-bom:8.7.1"))

    implementation("io.github.jamal-wia:paginator")
    implementation("io.github.jamal-wia:paginator-compose") // если у вас Compose
    implementation("io.github.jamal-wia:paginator-view")    // если у вас View / RecyclerView
}
```

Зависимость от `androidx.paging:*` пока оставьте — на время миграции нормально, что в проекте
сосуществуют оба механизма. Удалить её можно одним из последних шагов, когда не останется ни
одного `Pager` и `PagingDataAdapter`.

## Карта соответствий

Сразу таблица — что чем заменяется. Ниже каждый пункт разобран отдельно.

| Paging 3                                            | Paginator                                                                                                           |
|-----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `PagingSource<Key, Value>.load(params)`             | `load { page -> LoadResult(...) }` в DSL                                                                            |
| `LoadResult.Page(data, prevKey, nextKey)`           | `LoadResult(data, metadata?)`                                                                                       |
| `getRefreshKey(state)`                              | не нужно — пагинатор сам помнит положение                                                                           |
| `Pager(config) { source }`                          | `mutablePaginator<T>(capacity = …) { load { … } }`                                                                  |
| `PagingConfig(pageSize, prefetchDistance, maxSize)` | `capacity` + `cache = MostRecentPagingCache(maxSize)` + `PrefetchOptions(prefetchDistance)`                         |
| `Flow<PagingData<T>>`                               | `Flow<PaginatorUiState<T>>` или `paginator.core.snapshot`                                                           |
| `cachedIn(scope)`                                   | не нужно — состояние живёт в `Paginator`, сериализуется в JSON                                                      |
| `LoadStates` (refresh / prepend / append)           | `PaginatorUiState.Content(items, prependState, appendState)`                                                        |
| `PagingDataAdapter` + `submitData`                  | обычный `RecyclerView.Adapter` + `bindPaginated`                                                                    |
| `LoadStateAdapter`                                  | `appendIndicator` / `prependIndicator` в `paginated { }` DSL или отдельный адаптер для `appendState`/`prependState` |
| `LazyPagingItems` + `collectAsLazyPagingItems()`    | `paginator.rememberPaginated()` + `paginated { }` DSL                                                               |
| `RemoteMediator` + Room                             | `persistentCache: PersistentPagingCache` (L2)                                                                       |
| `pagingData.map { … }` / `filter { … }`             | `MutablePaginator.updateAll { … }` / `removeAll { … }` или `interweave { … }`                                       |
| `pagingData.insertSeparators { … }`                 | `Flow<PaginatorUiState<T>>.interweave(weaver)`                                                                      |
| `pagingDataAdapter.refresh()`                       | `paginator.restart()` (всё) или `paginator.refresh(pages)` (точечно)                                                |
| `PagingSource.invalidate()`                         | `paginator.core.markDirty(page)` или `paginator.restart()`                                                          |
| ключ-строка в `PagingSource<String, T>` (GraphQL)   | `mutableCursorPaginator<T> { load { cursor -> CursorLoadResult(…) } }`                                              |

Дальше разберём каждый блок по отдельности.

---

## Шаг 1. Заменяем `PagingSource` на `load { }`

Типичный `PagingSource` для page-based API:

```kotlin
class FeedPagingSource(private val api: Api) : PagingSource<Int, Item>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Item> {
        val page = params.key ?: 1
        return try {
            val items = api.fetch(page)
            LoadResult.Page(
                data = items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (items.isEmpty()) null else page + 1,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Item>): Int? {
        return state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey?.plus(1) }
    }
}
```

Превращается в одну лямбду:

```kotlin
val paginator = mutablePaginator<Item> {
    load { page -> LoadResult(api.fetch(page)) }
}
```

Что делось:

- **`prevKey` / `nextKey`** — не нужны. Страница в Paginator адресуется напрямую `Int`-номером, и
  пагинатор сам знает, что после страницы 5 идёт 6, а перед ней 4. Двунаправленность работает из
  коробки — `goPreviousPage` уйдёт на страницу 4, даже если вы стартовали с 5-й.
- **`getRefreshKey`** — не нужен. Состояние пагинатора (где сейчас находится контекстное окно,
  какие страницы загружены) хранится в самом объекте и переживает рефреш.
- **`try/catch` вокруг `LoadResult.Error`** — не нужен. Любое исключение из `load` Paginator сам
  ловит и превращает в `ErrorPage` с сохранением кэшированных данных.
- **`if (items.isEmpty()) null else page + 1`** — не нужно. Конец ленты вы либо сообщаете через
  `finalPage` (если бэкенд отдаёт `totalPages`), либо не сообщаете вовсе — на пустой странице
  пагинатор просто перестанет грузить дальше.

Если бэкенд отдаёт количество страниц, удобно сразу выставить границу прямо внутри `load` —
ресивер лямбды это сам пагинатор:

```kotlin
val paginator = mutablePaginator<Item> {
    load { page ->
        val response = api.fetch(page)
        finalPage = response.totalPages
        LoadResult(response.items, FeedMetadata(response.totalCount))
    }
}
```

`LoadResult` — открытый класс, второй параметр `metadata` доезжает до `SuccessPage` и доступен в
кастомных `PageState`-подклассах через `initializers { success { … } }`. Это аналог того, что в
Paging 3 пришлось бы тащить через отдельный `Flow` рядом с `PagingData`.

---

## Шаг 2. Заменяем `Pager` и `PagingConfig`

`Pager(PagingConfig(…)) { … }` отображается на DSL-конструктор Paginator один в один. Сравним
параметры:

```kotlin
// Было
val pager = Pager(
    config = PagingConfig(
        pageSize = 20,
        prefetchDistance = 10,
        initialLoadSize = 20,
        maxSize = 200,
        enablePlaceholders = false,
    ),
    pagingSourceFactory = { FeedPagingSource(api) },
).flow.cachedIn(viewModelScope)
```

```kotlin
// Стало
private val paginator = mutablePaginator<Item>(capacity = 20) {
    load { page -> LoadResult(api.fetch(page)) }
    cache = MostRecentPagingCache(maxSize = 10) // 200 элементов / 20 на страницу = 10 страниц
}
```

Покомпонентно:

- **`pageSize`** → `capacity` в `mutablePaginator(capacity = N)`. Это размер логической страницы
  пагинатора, а не «сколько грузим». Сколько грузить — решает ваш `load`.
- **`prefetchDistance`** — параметр UI-биндингов, не ядра. Передаётся через
  `PrefetchOptions(prefetchDistance = …)` при `bindPaginated` / `rememberPaginated`. Если вы не
  настраиваете — действует разумный дефолт.
- **`initialLoadSize`** — отдельного параметра нет. Если первая страница должна быть больше —
  отдайте
  больше из `load(1)` (и поставьте `capacity` под обычный размер; см.
  [Capacity & Incomplete Pages](https://github.com/jamal-wia/Paginator/blob/master/docs/1.%20core-concepts.md#capacity--incomplete-pages)).
- **`maxSize`** → стратегия кэша. Самый прямой аналог —
  `MostRecentPagingCache(maxSize = N страниц)`.
  Стратегии композируются оператором `+`, можно добавить TTL (`TimeLimitedPagingCache`) или окно
  вокруг видимой области (`ContextWindowPagingCache`). Деталей — в
  [docs/6. caching.md](https://github.com/jamal-wia/Paginator/blob/master/docs/6.%20caching.md).
- **`enablePlaceholders`** — не нужен. Paginator не показывает `null`-плейсхолдеры между страниц;
  если визуально нужен скелетон во время загрузки, используется `ProgressPage` и кастомные
  `initializers { progress { … } }`, или `paginated { appendIndicator { … } }` в Compose.
- **`cachedIn(viewModelScope)`** — не нужен. Кэш страниц живёт прямо в `Paginator`, переживает
  пересоздание UI и сериализуется на process death (см. шаг 5).

Сам пагинатор лежит как обычное поле ViewModel, без `Flow<PagingData<T>>` и без `.cachedIn`:

```kotlin
class FeedViewModel(private val api: Api) : ViewModel() {

    private val paginator = mutablePaginator<Item>(capacity = 20) {
        load { page -> LoadResult(api.fetch(page)) }
    }

    val uiState: StateFlow<PaginatorUiState<Item>> =
        paginator.uiState.stateIn(viewModelScope, SharingStarted.Eagerly, PaginatorUiState.Idle)

    init {
        viewModelScope.launch { paginator.restart() }
    }

    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }
}
```

`paginator.release()` в `onCleared` обязателен — он закрывает внутренние Flow и отменяет фоновые
задачи. У `Pager` явного аналога не было, потому что scope вытаскивал ноги из-под `cachedIn`.

---

## Шаг 3. UI — заменяем `PagingDataAdapter` / `LazyPagingItems`

### View / RecyclerView

Было:

```kotlin
class FeedAdapter : PagingDataAdapter<Item, ItemVH>(DIFF) { /* ... */ }

binding.recyclerView.adapter = adapter.withLoadStateFooter(
    footer = LoadStateAdapter { adapter.retry() },
)

viewLifecycleOwner.lifecycleScope.launch {
    viewModel.pagingData.collectLatest { adapter.submitData(it) }
}
```

Стало:

```kotlin
class FeedAdapter : ListAdapter<Item, ItemVH>(DIFF) { /* обычный ListAdapter */ }

val headerAdapter = HeaderAdapter()
val appendIndicatorAdapter = AppendIndicatorAdapter { viewModel.retry() }

binding.recyclerView.adapter = ConcatAdapter(headerAdapter, adapter, appendIndicatorAdapter)
binding.recyclerView.layoutManager = LinearLayoutManager(context)

val paged = viewModel.paginator.bindPaginated(
    recyclerView = binding.recyclerView,
    lifecycleOwner = viewLifecycleOwner,
    headerCount = { headerAdapter.itemCount },
    footerCount = { appendIndicatorAdapter.itemCount },
)

viewLifecycleOwner.lifecycleScope.launch {
    viewModel.uiState.collect { state ->
        when (state) {
            PaginatorUiState.Idle, PaginatorUiState.Loading -> showSkeleton()
            PaginatorUiState.Empty -> showEmpty()
            is PaginatorUiState.Error -> showError(state.cause)
            is PaginatorUiState.Content -> {
                adapter.submitList(state.items)
                appendIndicatorAdapter.submit(state.appendState)
            }
        }
    }
}
```

Что произошло:

- `PagingDataAdapter` → обычный `ListAdapter` (или `RecyclerView.Adapter` с любым diff-механизмом).
  `submitData` → `submitList`. Никакой магии «адаптер сам решает, когда показать индикатор» больше
  нет — индикатор это отдельный адаптер, и он явно собирает `state.appendState`.
- `withLoadStateHeader/Footer` → ваш собственный однострочный адаптер, который рендерит
  `prependState` / `appendState` (это `PageLoadState.Idle / Loading / Error`). Это не больше кода,
  но он находится в одном месте и не растворён в обвязке `LoadStateAdapter`.
- `OnScrollListener` для подгрузки → `bindPaginated`. Он сам ставит `OnScrollListener` и
  `OnLayoutChangeListener`, считает количество видимых элементов с учётом header/footer-адаптеров,
  снимает слушателей на `ON_DESTROY`. Никакой ручной триггер «дочитал до конца — позови
  `goNextPage`» писать не нужно.

`bindPaginated` работает с `LinearLayoutManager`, `GridLayoutManager` и
`StaggeredGridLayoutManager` — те же три, что покрывает Paging 3.

### Compose

Было:

```kotlin
val items = viewModel.pagingData.collectAsLazyPagingItems()

LazyColumn {
    items(items.itemCount, key = items.itemKey { it.id }) { idx ->
        val item = items[idx] ?: return@items
        Row(item)
    }

    when (val s = items.loadState.append) {
        is LoadState.Loading -> item { CircularProgressIndicator() }
        is LoadState.Error -> item { ErrorRow(s.error) { items.retry() } }
        else -> Unit
    }
}
```

Стало:

```kotlin
val uiState by viewModel.uiState.collectAsState()
val listState = rememberLazyListState()
val paged = viewModel.paginator.rememberPaginated(state = listState)

LazyColumn(state = listState) {
    paginated(paged) {
        items(uiState.items, key = { it.id }) { Row(it) }
        appendIndicator { AppendIndicator(uiState.appendState) { viewModel.retry() } }
        prependIndicator { PrependIndicator(uiState.prependState) { viewModel.retry() } }
    }
}
```

`rememberPaginated` + `paginated { }` сами считают `dataItemCount`, `headerCount` и `footerCount`,
расставляют `LaunchedEffect`/`snapshotFlow`, биндят prefetch к `LazyListState`. Если хочется
обойтись без DSL и держать счётчики руками — есть `PrefetchOnScroll(state, dataItemCount, …)`.
Подробности по слоям интеграции — в
[docs/7. prefetch.md](https://github.com/jamal-wia/Paginator/blob/master/docs/7.%20prefetch.md).

Важный нюанс: в Paging 3 `LazyPagingItems` *одновременно* и контейнер данных, и индикатор
загрузки, и интерфейс рефреша. В Paginator это разделено: данные приходят через
`PaginatorUiState.Content`, индикаторы — это отдельные элементы списка, рефреш — это вызов метода
ViewModel. Менее «всё-в-одном», но точку правки будущей бага видно сразу.

---

## Шаг 4. `LoadStates` → `prependState` / `appendState`

В Paging 3 был `CombinedLoadStates` с тремя направлениями — `refresh`, `prepend`, `append` — и
каждое могло быть `Loading`, `Error` или `NotLoading(endOfPaginationReached)`.

В Paginator всё то же самое, просто лежит внутри `PaginatorUiState.Content`:

```kotlin
sealed class PaginatorUiState<out T> {
    data object Idle : PaginatorUiState<Nothing>()
    data object Loading : PaginatorUiState<Nothing>()
    data object Empty : PaginatorUiState<Nothing>()
    data class Error(val cause: Throwable) : PaginatorUiState<Nothing>()
    data class Content<T>(
        val items: List<T>,
        val prependState: PageLoadState,
        val appendState: PageLoadState,
    ) : PaginatorUiState<T>()
}
```

Маппинг:

| Paging 3                                  | Paginator                                                                                           |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `loadState.refresh is Loading`            | `state == PaginatorUiState.Loading`                                                                 |
| `loadState.refresh is Error`              | `state is PaginatorUiState.Error`                                                                   |
| `itemCount == 0 && refresh is NotLoading` | `state == PaginatorUiState.Empty`                                                                   |
| `loadState.append is Loading`             | `state.appendState is PageLoadState.Loading`                                                        |
| `loadState.append is Error`               | `state.appendState is PageLoadState.Error`                                                          |
| `endOfPaginationReached`                  | бросается `FinalPageExceededException` (если задан `finalPage`) или просто перестаёт грузить дальше |

Разница в одном: «я ещё не начал» (`Idle`) и «иду первый раз» (`Loading`) в Paginator — отдельные
состояния. Это отдельная пара десятков строк UI-кода, которые в Paging 3 приходилось писать через
комбинацию `loadState.refresh` и `itemCount == 0`.

---

## Шаг 5. `cachedIn` и process death — теперь это сериализация

В Paging 3 паттерн «выживи рестарт ViewModel» решался `.cachedIn(viewModelScope)`. С process death
он не помогал: scope умирал вместе с процессом, кэш терялся, после возврата лента грузилась заново.

В Paginator кэш — обычная сериализуемая структура. Сохраняем в `SavedStateHandle`:

```kotlin
class FeedViewModel(
    private val api: Api,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val paginator = mutablePaginator<Item> {
        load { page -> LoadResult(api.fetch(page)) }
    }

    init {
        viewModelScope.launch {
            savedStateHandle.get<String>("paginator")
                ?.let { paginator.restoreStateFromJson(it, Item.serializer()) }
                ?: paginator.restart()
        }

        // Сохраняем последний валидный снапшот в SavedStateHandle при каждом изменении.
        viewModelScope.launch {
            paginator.uiState.collect {
                savedStateHandle["paginator"] = paginator.saveStateToJson(Item.serializer())
            }
        }
    }
}
```

Условие — `Item` помечен `@Serializable`. После убийства процесса при возврате на экран пользователь
увидит ровно те же страницы в той же позиции, без сетевого запроса.

Если важна память, передайте `contextOnly = true` — сохранится только видимая часть, без
бесконечно отъезжающего хвоста кэша.

---

## Шаг 6. `RemoteMediator` + Room → `persistentCache` (L2)

Если ваш экран делал классический Paging 3 + Room + `RemoteMediator` — то есть хранил данные в БД,
а Paging 3 рисовал то, что лежит в БД, — переход в Paginator делается так:

1. Делаем реализацию `PersistentPagingCache<Item>` поверх вашего DAO.
2. Передаём её в DSL как `persistentCache = …`.
3. `RemoteMediator` выкидываем — `load` Paginator теперь и есть «сходи в сеть и положи в БД»,
   потому что L2 пишется библиотекой автоматически.

Скелет:

```kotlin
class RoomFeedCache(private val dao: ItemDao) : PersistentPagingCache<Item> {
    override suspend fun loadPage(page: Int): List<Item>? = dao.pageOrNull(page)
    override suspend fun savePage(page: Int, items: List<Item>) = dao.upsertPage(page, items)
    override suspend fun removePage(page: Int) = dao.removePage(page)
    override suspend fun clear() = dao.clearAll()
}

private val paginator = mutablePaginator<Item>(capacity = 20) {
    load { page -> LoadResult(api.fetch(page)) }
    cache = MostRecentPagingCache(maxSize = 50)
    persistentCache = RoomFeedCache(dao)
}
```

Что важно:

- L2 и L1 разделены. L1 (in-memory) — горячий кэш, его стратегии (`MostRecent`, `TimeLimited`, …)
  не зависят от L2. Можно держать в БД 10 000 страниц, а в памяти — последние 50.
- L1 «прогревается» из L2 на старте автоматически — не нужно писать руками
  «если БД не пуста — покажи её, иначе сходи в сеть».
- При оптимистичных правках (`setElement` / `removeElement` / `addElement`) Paginator сам помечает
  затронутую страницу как ожидающую синхронизации с L2. Полная картина — в
  [docs/6. caching.md → Persistent Cache](https://github.com/jamal-wia/Paginator/blob/master/docs/6.%20caching.md#persistent-cache-l2).

Если у вас Room появился в проекте *только ради* того, чтобы Paging 3 поверх него работал — после
миграции его можно убрать. Базовая «лента с подгрузкой» в Paginator не требует БД.

---

## Шаг 7. Трансформации — `map`, `filter`, `insertSeparators`

В Paging 3 эти операции применялись к `Flow<PagingData<T>>`:

```kotlin
viewModel.pagingData
    .map { pd -> pd.map { it.copy(formatted = format(it)) } }
    .map { pd ->
        pd.insertSeparators { before, after ->
            if (before?.dateDay != after?.dateDay) DaySeparator(after?.dateDay) else null
        }
    }
```

В Paginator есть два разных инструмента в зависимости от того, что вы хотите.

**Если правка идёт в данные** — это домен, и делается через `MutablePaginator`:

```kotlin
paginator.updateAll { it.copy(formatted = format(it)) }
paginator.removeAll { it.banned }
paginator.updateWhere(predicate = { it.id == 42 }) { it.copy(liked = true) }
```

Это меняет элементы в кэше страниц, кэш консистентен, snapshot переиспускается, UI рисует
обновлённое — без `notifyItemChanged` и без `invalidate()`.

**Если правка чисто визуальная** (вставить разделители по дате, плашку «Новые», sticky-заголовки) —
это presentation, и делается через `interweave`:

```kotlin
val displayState: Flow<PaginatorUiState<FeedRow>> =
    paginator.uiState.interweave(weaver = DateSeparatorsWeaver())
```

`interweave` — opt-in оператор над `Flow<PaginatorUiState<T>>`, который вставляет meta-строки
между элементами данных, не трогая кэш и `MutablePaginator`-CRUD. Подробнее —
[docs/12. interweaving.md](https://github.com/jamal-wia/Paginator/blob/master/docs/12.%20interweaving.md).

Разделение на «данные → `MutablePaginator`» и «визуальная вставка → `interweave`» убирает
типичную для Paging 3 проблему: когда `insertSeparators` начинает зависеть от полей, которые
загружаются асинхронно, и приходится комбинировать `combine(pagingData, settings)` с риском
потерять `cachedIn`.

---

## Шаг 8. Курсорная пагинация — `CursorPaginator` вместо `PagingSource<String, T>`

Если ваш `PagingSource` на самом деле курсорный (ключ — это `String` от GraphQL connections,
Slack/Reddit/Instagram-style API, чат с `next_cursor`), переезд логичнее делать не на `Paginator`,
а на `CursorPaginator`. Он живёт рядом, имеет тот же `uiState`, тот же `transaction { }`, ту же
сериализацию.

```kotlin
val messages = mutableCursorPaginator<Message>(capacity = 50) {
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

В отличие от `Paginator`, у курсорного варианта нет «прыжка на страницу 47» — потому что у курсора
нет номеров. Зато есть `jump(CursorBookmark(self = "msg_817"))` — что и требуется на deeplink в
конкретное сообщение. Полная карта различий —
[docs/14. paginator-vs-cursor.md](https://github.com/jamal-wia/Paginator/blob/master/docs/14.%20paginator-vs-cursor.md).

В одном проекте оба варианта могут существовать одновременно: лента поста на `Paginator`, чат на
`CursorPaginator`.

---

## Шаг 9. Что меняется с prefetch и refresh

- `pagingDataAdapter.refresh()` (рефреш всего, как pull-to-refresh) → `paginator.restart()`.
  Очищает кэш, ставит контекст на стартовую страницу, перегружает первую.
- Точечный рефреш одной или нескольких страниц без сброса контекста (в Paging 3 для этого нужен
  был `RemoteMediator` или `invalidate()` источника) → `paginator.refresh(pages = listOf(3, 4))`.
- «Помети страницу как протухшую, но не дёргай прямо сейчас» (бэкенд-событие, что список изменился,
  но пользователь сейчас на другой странице) → `paginator.core.markDirty(page)`. Когда пользователь
  следующим скроллом доберётся до окна, эта страница тихо обновится в фоне без спиннера.
- Prefetch-distance, который раньше шёл в `PagingConfig`, теперь живёт в `PrefetchOptions`,
  передаваемых в биндинг. Менять можно в рантайме.

`endOfPaginationReached` (флаг, что дальше грузить нечего) в Paging 3 выставлялся через
`prevKey/nextKey = null`. В Paginator то же делается через `finalPage` (если он известен) или
через возврат пустого списка — пагинатор сам перестанет идти вперёд.

---

## Шаг 10. Грабли, на которые обычно наступают

**1. `pageSize` в Paging 3 ≠ `capacity` в Paginator.**
В Paging 3 `pageSize` — это «сколько просим у источника»; источник может вернуть больше или меньше.
В Paginator `capacity` — это «сколько ждём от страницы». Если бэкенд вернул меньше, страница
пометится как незаполненная и переспросится при следующем `goNextPage` (см. «Incomplete pages» в
[docs/1. core-concepts.md](https://github.com/jamal-wia/Paginator/blob/master/docs/1.%20core-concepts.md)).
Не пытайтесь подобрать `capacity` под фактический размер ответа — поставьте ожидаемый.

**2. Не нужно `combine(pagingData, settings)`.**
Если на ленту накладывается фильтр или режим отображения — менять данные в `MutablePaginator`
(через `updateAll`/`updateWhere`) и/или мерджить визуально через `interweave`. Не комбинируйте
`paginator.uiState` с настройками вручную в одном `combine` — у вас в этой комбинации потеряются
гарантии порядка `uiState`.

**3. Не делайте `paginator` локальным.**
Paginator — это объект состояния, у него есть кэш, локи, dirty-страницы. Хранить его в
функции/композабле/фрагменте бессмысленно — переживёт ровно одну композицию и умрёт. Место —
ViewModel, репозиторий или use-case.

**4. Не забывайте `release()`.**
`Pager` не имел явного освобождения, потому что всё держалось на scope. У `Paginator` есть
`release()` — вызывайте в `onCleared`/`onDispose`. Иначе в логах могут оставаться отписки
снапшотов после смерти UI.

**5. Не пытайтесь переиспользовать `LoadStateAdapter`.**
Технически можно завернуть `appendState`/`prependState` в `LoadStateAdapter`-обёртку. Не надо —
получится двойной маппинг. Сделайте простой адаптер, который читает `PageLoadState` напрямую.

**6. После `restart()` UI скроллит наверх — это by design.**
В Paging 3 после `refresh()` поведение зависело от `RemoteMediator`/`PagingSource`. В Paginator
`restart()` обнуляет контекстное окно, и список начинается с первой страницы. Если нужен «обнови
данные, оставь меня на текущей странице» — используйте `paginator.refresh(pages = …)`.

---

## Чек-лист миграции одного экрана

1. Заменить `PagingSource.load` → `load { page -> LoadResult(api.fetch(page)) }`.
2. Удалить `getRefreshKey`, `prevKey`/`nextKey`, ручной `try/catch`.
3. Заменить `Pager(PagingConfig(...))` → `mutablePaginator<T>(capacity = …) { load { … } }`.
4. Маппинг `maxSize` → `cache = MostRecentPagingCache(maxSize = …)`. Если был TTL/окно —
   композировать через `+`.
5. `pagingData.cachedIn(scope)` → удалить. Поле `paginator` живёт само.
6. Добавить `paginator.release()` в `onCleared` (или `DisposableEffect` в Compose).
7. UI: `PagingDataAdapter` → `ListAdapter` + `ConcatAdapter` с header/footer-адаптерами.
8. Заменить `withLoadStateFooter`/`LoadStateAdapter` на адаптер, читающий
   `state.appendState`/`prependState`.
9. Ручной `OnScrollListener` → `bindPaginated` (View) или `paginator.rememberPaginated()` +
   `paginated { }` (Compose).
10. `LazyPagingItems`-обращения по индексу → `state.items` из `PaginatorUiState.Content`.
11. `pagingData.map` / `filter` → `paginator.updateAll` / `removeAll`. `insertSeparators` →
    `interweave`.
12. `RemoteMediator` + Room → `persistentCache = RoomFeedCache(dao)`. `RemoteMediator` удалить.
13. Если был курсорный API (`PagingSource<String, T>`) — мигрировать на `mutableCursorPaginator`,
    а не на `mutablePaginator`.
14. Добавить сериализацию через `SavedStateHandle`, если нужен process-death recovery.
15. Прогнать экран руками. Удалить старый `PagingSource`/`Pager`/`PagingDataAdapter`.

Когда последний экран переехал — удалить зависимость `androidx.paging:*` целиком.

---

## Ссылки

- Репозиторий: [github.com/jamal-wia/Paginator](https://github.com/jamal-wia/Paginator)
- Maven Central: `io.github.jamal-wia:paginator:8.7.1` (или через `paginator-bom`)
- Документация: [docs/](https://github.com/jamal-wia/Paginator/tree/master/docs)
- Сравнение по фичам:
  [Paging 3 хорош. Пока вам не понадобится что-то ещё](Paging%203%20хорош.%20Пока%20вам%20не%20понадобится%20что-то%20ещё.md)
- Боевой пример (мессенджер):
  [Мессенджер на Paginator. Боевые задачи](Мессенджер%20на%20Paginator.%20Боевые%20задачи.md)
- Telegram-сообщество: [t.me/+0eeAM-EJpqgwNGZi](https://t.me/+0eeAM-EJpqgwNGZi)
