## Вступление, которое можно пропустить, если вы уже делали пагинацию

Если коротко: пагинация — это когда вы не грузите 100 000 товаров из каталога одним запросом, а
показываете их страницами по 20–50 штук и подгружаете следующую порцию, когда пользователь домотал
до конца.

Звучит как задача на полдня. На практике — по-разному.

Я пишу мобильные приложения уже давно, и каждый раз, когда в новом проекте появлялась пагинация,
рядом с ней через месяц-другой появлялся один и тот же набор багов и ad-hoc-решений. Флаги
`isLoadingNextPage`, `isLoadingPrevious`, `isRefreshing`, `isEmpty`, `hasError`, `hasNextPage`.
Попытки «просто заменить элемент без перезагрузки страницы». Восстановление позиции после убийства
процесса. Прыжок на конкретную страницу по deeplink.

На Android есть Jetpack Paging 3, и его берут по умолчанию. Но как только вы выходите за рамки
«загрузи следующие 20 элементов на скролле вниз» — начинается интересное. А если ваш проект — Kotlin
Multiplatform, то Paging 3 — кандидат сомнительный: в апстрим-исходниках KMP-таргеты появились,
но публикуемые артефакты и вся обвязка (Room, RecyclerView, Compose-адаптеры) остаются
Android-центричными, так что на iOS вы фактически остаётесь один на один со своим кодом.

Я расскажу про опенсорсную библиотеку [Paginator](https://github.com/jamal-wia/Paginator), которую
делаю последние несколько лет. Она работает одинаково на Android, JVM и iOS из одного `commonMain`,
закрывает сложные сценарии из коробки — и даже на самой обычной ленте настраивается короче, чем
Paging 3. Это не поход против Paging 3 и не попытка что-то кому-то доказать. Это просто описание
того, что есть другой инструмент, и он делает то же самое компактнее.

---

## Простой случай — даже здесь Paging 3 требует лишнего

Представьте классическую ленту товаров. Вниз — подгружаем ещё. Всё.

На Paging 3 минимальная реализация выглядит примерно так:

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

Чтобы это заработало, нужно держать в голове: `PagingSource`, `LoadParams`, `LoadResult.Page`,
`prevKey`/`nextKey`, `getRefreshKey`, `Pager`, `PagingConfig`, и ещё `PagingDataAdapter` или
`LazyPagingItems` со стороны UI.

Тот же самый сценарий на Paginator:

```kotlin
val paginator = mutablePaginator<Item> {
    load { page -> LoadResult(api.fetch(page)) }
}
```

Это всё. Ни `PagingSource`, ни `Pager`, ни разбора ключей в обе стороны — потому что номер страницы
это просто `Int`, и двигаться от него вперёд-назад библиотека умеет сама.

Дальше любой из двух вариантов подписки:

```kotlin
// Готовый UI-state: Idle / Loading / Empty / Error / Content
paginator.uiState.collect { render(it) }
```

```kotlin
// Или сырые страницы, если хочется полный контроль
paginator.core.snapshot.collect { pages -> render(pages) }
```

И навигация:

```kotlin
viewModelScope.launch { paginator.goNextPage() }
```

Это не преувеличение для эффекта. На простой ленте Paginator действительно настраивается в три
строки, и эти три строки дают вам работающие состояния загрузки, ошибки, пустого результата, защиту
от дубликатов запросов и кэш страниц. Причём работают одни и те же три строки — хоть на Android,
хоть на iOS.

А теперь про то, чего в Paging 3 или нет, или приходится собирать руками.

---

## Что обычно просит продукт (и что с этим происходит)

### 1. «Открой по ссылке сразу страницу 47»

Классический deeplink: пользователю прислали нотификацию, по тапу нужно открыть элемент с ID`12345`,
который по данным бэкенда лежит на 47-й странице.

В Paging 3 такой навигации из коробки нет — библиотека построена вокруг идеи «подгрузить, куда
скроллит пользователь», а не «покажи мне страницу N». Можно прокрутить RecyclerView на нужную
позицию после загрузки первой страницы — но это не то же самое, что показать сразу страницу 47. Для
настоящего jump требуется переопределять `itemsBefore` / `itemsAfter` и включать placeholders, а
продуктовые требования редко вписываются в placeholders ровно.

В Paginator это встроенная операция:

```kotlin
paginator.jump(BookmarkInt(page = 47))
```

Если страница уже в кэше — возврат моментальный. Если нет — грузится. После этого пользователь может
листать вперёд **или назад** от текущей позиции.

### 2. «У нас чат / лента с якорем посередине»

Классический кейс: открыли обсуждение на конкретном сообщении, нужно грузить и старые сообщения (
вверх), и новые (вниз). Или каталог, открытый на середине алфавита.

Paging 3 умеет prepend/append, но реализация «начать с середины и ходить в обе стороны» в нём
довольно лобовая: вы должны договориться с бэкендом про ключи в обе стороны и грамотно вернуть
`prevKey`/`nextKey`.

В Paginator это тот же самый API:

```kotlin
paginator.jump(BookmarkInt(page = 47)) // точка входа
paginator.goNextPage()                 // листаем вниз
paginator.goPreviousPage()             // и вверх — из коробки
```

Никаких разных ключей для разных направлений. Одна страница — один номер.

### 3. «Дай отредактировать элемент без перезагрузки страницы»

Пользователь лайкнул пост, или поменял цвет на карточке, или удалил свой комментарий. Надо обновить
элемент. На месте. Без моргания.

У Paging 3 с
этим [исторически непросто](https://jermainedilao.medium.com/android-paging-3-library-how-to-update-an-item-52f00d9c99b2):
`PagingData` не позволяет напрямую поменять элемент. Типичные обходные пути:

- Сделать свойства элемента mutable и дёргать `notifyItemChanged` вручную.
- Дёрнуть `invalidate()` и перезагрузить весь источник.
- Хранить «оверлей» правок в отдельном `StateFlow` и мёржить его с `PagingData`.

Всё это работает, но ни один из вариантов нельзя назвать очевидным.

В Paginator редактирование элементов — штатный API:

```kotlin
paginator.setElement(updatedItem, page = 3, index = 1)
paginator.removeElement(page = 3, index = 1)
paginator.addAllElements(newItems, targetPage = 3, index = 0)

// По предикату, через все страницы сразу:
paginator.updateWhere(
    predicate = { it.id == updatedItem.id },
    transform = { updatedItem }
)
```

Никаких `notifyItemChanged`, никакого `invalidate()`. Кэш страниц изменился — `snapshot` Flow выдал
новый список — UI перерисовался.

### 4. «Бэкенд иногда возвращает меньше элементов, чем ожидали»

Очень жизненный случай: ожидаемый размер страницы 20, бэкенд из-за фильтра/персонализации/кривого
индекса вернул 13. Paging 3 честно отдаст эти 13 в `PagingData`, и если вы не напишете
дополнительную логику — пагинация «схлопнется»: `RecyclerView` увидит, что прокрутка не доходит до
триггера подгрузки, и следующий запрос не произойдёт.

Paginator такие страницы распознаёт как **незаполненные** (проверка `isFilledSuccessState`) и на
следующем `goNextPage` сам перезапрашивает ту же самую страницу, показывая пользователю уже
загруженные 13 элементов и индикатор дозагрузки сверху/снизу.

```kotlin
val paginator = mutablePaginator<Item>(capacity = 20) {
    load { page -> LoadResult(api.fetch(page)) }
}
// Пришло 13 вместо 20 — на следующем goNextPage страница переспросится.
```

### 5. «У нас KMP, нам нужен общий код»

Здесь короткий абзац. Paging 3 — часть AndroidX. На iOS он не едет. На KMP-проектах это означает,
что пагинационную логику для Android и iOS вы всё равно пишете отдельно, а общий `commonMain`
остаётся без неё.

Paginator написан на чистом Kotlin, без платформо-специфичных зависимостей, и доступен как
KMP-артефакт:

```kotlin
commonMain.dependencies {
    // Привязываем все артефакты Paginator к одной версии через BOM
    implementation(platform("io.github.jamal-wia:paginator-bom:8.6.1"))
    implementation("io.github.jamal-wia:paginator")
}
```

Targets: Android, JVM, `iosX64`, `iosArm64`, `iosSimulatorArm64`. В `commonMain` пишется один раз —
работает везде.

### 6. «Приложение убили — верни меня ровно туда, где я был»

Process death на Android — это реальность, а не экзотика. Пользователь зашёл на страницу 5, свернул
приложение, его процесс прибили ради фоновой карты, пользователь вернулся — и лента проскроллилась
обратно на первую страницу, потому что кэша больше нет.

В Paginator есть встроенная сериализация всего состояния через `kotlinx.serialization`:

```kotlin
// Сохраняем (suspend; переживает process death через SavedStateHandle)
savedStateHandle["paginator"] = paginator.saveStateToJson(Item.serializer())

// Восстанавливаем
savedStateHandle.get<String>("paginator")
    ?.let { paginator.restoreStateFromJson(it, Item.serializer()) }
```

---

## Встречайте Paginator

Базовое знакомство уже было выше — те самые три строки сетапа. Повторю, чтобы положить в одном
месте:

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

Дальше у библиотеки два уровня глубины. Вы можете не спускаться ниже этого сетапа — и получить
полноценную пагинацию со всеми состояниями. А можете, когда понадобится, подключить дополнительное:

- Двухуровневый кэш (L1 в памяти + ваш L2, обычно Room).
- Стратегии вытеснения L1 — LRU, FIFO, TTL, sliding window, композируемые через оператор `+`.
- Транзакции с rollback через `transaction { }`.
- Dirty pages — тихое фоновое обновление страницы при следующей навигации.
- Bookmarks и `jumpForward` / `jumpBack` с зацикливанием.
- Scroll-based prefetch controller.
- Сериализация состояния через `kotlinx.serialization`.
- Подключаемый логгер.

Всё это — opt-in. Для простой ленты ничего из списка не нужно.

Как устроено внутри — зачем `PageState` sealed-тип, как работает контекстное окно страниц, зачем три
слоя `PagingCore` / `Paginator` / `MutablePaginator`, как композируются стратегии кэша — разберём в
следующих статьях серии. Здесь цель была другая: показать, что есть инструмент, который покрывает и
простое, и сложное, и делает это короче.

---

## Итог

Paging 3 — нормальная библиотека. Она делает то, для чего её сделали. Но:

- Даже на простой бесконечной ленте её конфигурация требует больше кода и больше концепций, чем
  Paginator.
- На сценариях вроде прыжка на страницу, двунаправленной пагинации, редактирования элементов,
  неполных ответов от бэкенда и восстановления состояния после process death — Paging 3 либо требует
  значительных обходных путей, либо не закрывает кейс вовсе.
- KMP-проекты Paging 3 не поддерживает в принципе.

Paginator закрывает всё перечисленное из коробки и при этом не заставляет платить сложностью за
простые случаи.

Если пагинация — регулярная часть ваших задач, попробуйте. Репозиторий живой,
на [Maven Central](https://central.sonatype.com/artifact/io.github.jamal-wia/paginator), зрелый (
текущая версия 8.6.1), покрыт документацией. Обратная связь и звёзды — помогают.

- **GitHub:** [github.com/jamal-wia/Paginator](https://github.com/jamal-wia/Paginator)
- **Maven Central:** `io.github.jamal-wia:paginator:8.6.1` (или через `paginator-bom`)
- **Telegram-сообщество:** [t.me/+0eeAM-EJpqgwNGZi](https://t.me/+0eeAM-EJpqgwNGZi)
- **Документация:** по разделам в [docs/](https://github.com/jamal-wia/Paginator/tree/master/docs)

В следующей статье серии — разберём архитектуру: зачем три слоя, как устроен `PageState` и
контекстное окно, как композируются стратегии кэша и почему транзакции делают через savepoint вместо
event sourcing.
