# Обновление Paginator с 3.x до 8.x

> Если вы сидели на **Paginator 3.3.0** и решили сразу прыгнуть на актуальную **8.6.2** —
> это не совсем «обновление», а скорее миграция на, по сути, другую библиотеку. Концепции
> те же (page state, закладки, прыжки, snapshot/cacheFlow), но между этими версиями —
> 200+ коммитов, **пять мажорных релизов** (4.x → 8.x), переезд с JitPack на Maven Central,
> переход с Android-only на **Kotlin Multiplatform**, разделение на несколько артефактов
> и довольно агрессивная серия переименований публичного API.

Эта статья — про то, что реально изменилось, и в каком порядке я бы рекомендовал это
трогать. Основано на диффе между (3.3.0) и (8.6.2).

---

## 1. Координаты артефакта и репозиторий

Это первое и обязательное изменение.

**3.3.0 — JitPack:**

```kotlin
repositories {
    maven { setUrl("https://jitpack.io") }
}
dependencies {
    implementation("com.github.jamal-wia:Paginator:3.3.0")
}
```

**8.6.2 — Maven Central + BOM + многомодульная структура:**

```kotlin
repositories {
    mavenCentral() // JitPack больше не нужен
}
dependencies {
    implementation(platform("io.github.jamal-wia:paginator-bom:8.6.2"))
    implementation("io.github.jamal-wia:paginator")           // ядро, обязательно
    implementation("io.github.jamal-wia:paginator-compose")   // опционально — Compose / CMP
    implementation("io.github.jamal-wia:paginator-view")      // опционально — Android Views
}
```

`groupId` сменился (`com.github.jamal-wia` → `io.github.jamal-wia`), артефакт разделился
на `paginator` / `paginator-compose` / `paginator-view`, и появился `paginator-bom`, чтобы
все три артефакта на classpath не разъезжались по версиям.

## 2. Платформы

3.3.0 живёт в `paginator/src/main/java/...` — это **Android-only** библиотека.

8.6.2 — **Kotlin Multiplatform**: исходники переехали в `commonMain`, опубликованные
таргеты — **Android, JVM, iosX64, iosArm64, iosSimulatorArm64**. Если у вас KMP-проект,
теперь можно класть `paginator` прямо в `commonMain.dependencies`, и Gradle сам подтянет
нужный платформенный артефакт из KMP-метаданных.

## 3. Номер страницы: `UInt` → `Int`

В 3.3.0 номер страницы был `UInt` (`1u`, `BookmarkUInt(page = 5u)`, `page: UInt`). В 8.6.2
это **`Int`**. Все суффиксы `u` и любые упоминания `UInt`/`BookmarkUInt` придётся переписать.

```kotlin
// 3.3.0
paginator.setPageState(page = 1u, ...)
paginator.bookmarks += BookmarkUInt(page = 5u)

// 8.6.2
paginator.setState(page = 1, ...)
paginator.bookmarks += BookmarkInt(page = 5)
```

Контракт теперь такой: `page >= 1`, а `0` для контекстных страниц означает «ещё не начали».

## 4. Лямбда `load`

Это, пожалуй, первое breaking-изменение, которое вы увидите, как только начнёте писать код.

**3.3.0:**

```kotlin
val paginator = Paginator<Item> { page: UInt ->
    repo.loadPage(page.toInt()) // возвращает List<Item>
}
```

**8.6.2:**

```kotlin
val paginator = Paginator<Item> { page: Int ->
    LoadResult(repo.loadPage(page))
    // или с метаданными API:
    // LoadResult(items, MyMetadata(totalCount = response.total))
}
```

Поменялось три вещи сразу: свойство называется `load`, а не `source`; параметр стал `Int`;
лямбда теперь обязана возвращать `LoadResult<T>` (`data` + опциональный `Metadata`).
`LoadResult` — официальная точка расширения для всего, что вы хотите прокинуть в
`PageState.metadata`: total count, курсор, версия страницы и т.д.

## 5. `Paginator` VS `MutablePaginator`

В 3.3.0 один класс `Paginator` умел всё — навигацию, `addElement`, `removeElement`, `resize`,
`setPageState`. Начиная с 6.x это разделено:

- `Paginator<T>` — read-only, только навигация (`jump*`, `goNextPage`, `goPreviousPage`,
  `restart`, `refresh`).
- `MutablePaginator<T>` — наследник, в нём CRUD по элементам, `resize`, `setState`.

Если вы где-то вызываете `addElement` / `removeElement` / `setPageState` / `resize` /
`setState`, тип переменной нужно сменить на `MutablePaginator<T>`. Для чисто-читающих
сценариев можно оставить `Paginator<T>` и получить более узкий API.

## 6. Список переименований из 5.0.0

Бо́льшая часть ошибок компиляции после апгрейда придёт именно отсюда:

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
| `walkForwardWhile/...` (методы)  | extension-функции        |
| `removeElement(...)` (метод)     | extension-функция        |
| `refreshAll`, часть `addElement` | extension-функции        |

Плюс: cache больше не торчит наружу как `sortedMap`. Он живёт за `core: PagingCore` /
`cache: PagingCache`. Если в 3.3.0 вы лазили во внутреннюю мапу — теперь только через API
`core`/`cache`.

## 7. Перепаковка типов

В 3.3.0 почти всё было вложено в сам `Paginator` (`Paginator.PageState.SuccessPage`,
`Paginator.Bookmark.BookmarkUInt`, `Paginator.LockedException...`). В 8.6.2 эти типы
разнесены по нормальным под-пакетам:

- `com.jamal_aliev.paginator.page.PageState` (+ `PlaceholderPageState`, `PaginatorUiState`)
- `com.jamal_aliev.paginator.bookmark.BookmarkInt`
- `com.jamal_aliev.paginator.exception.LockedException.*`, `FinalPageExceededException`,
  `LoadGuardedException`
- `com.jamal_aliev.paginator.load.LoadResult`, `Metadata`
- `com.jamal_aliev.paginator.cache.PagingCache` и стратегии кэша
- `com.jamal_aliev.paginator.logger.PaginatorLogger`

Все импорты придётся переписать. Хорошая новость — после правок из §6 IDE-автоимпорт
закроет почти всё это сам.

## 8. Что нового стоит знать, пока вы там

Это не breaking, но это меняет облик «идиоматичного» кода в 8.x. Раз уж всё равно правим —
имеет смысл сразу подцепить:

- **`finalPage: Int`** — верхняя граница пагинации, кидает `FinalPageExceededException`
  (5.1.0). Полезно, когда API сообщает общее число страниц.
- **`loadGuard`** — колбэк, который может «зарубить» загрузку *до* сетевого запроса,
  кидая `LoadGuardedException`. Заменяет самописные флаги «можно ли сейчас грузить».
- **Стратегии кэша** — `LRU` / `FIFO` / `TTL` / `SlidingWindow` / `Composite` через
  `PagingCache`. В 3.3.0 единственной ручкой был `capacity`.
- **Сериализация состояния** — `paginator.saveState(...)` / `restoreState(...)` поверх
  `kotlinx.serialization`. Заменяет ручной танец «сохрани последние номера страниц в
  `SavedStateHandle`» при процесс-смерти.
- **`PaginatorUiState`** — единый Flow для UI-слоя (loading / data / error / empty),
  рекомендуется в новом Quick Start вместо ручной сборки `snapshot` + флагов загрузки.
- **`PlaceholderProgressPage`** — встроенная поддержка skeleton/shimmer-строк.
- **`PaginatorLogger`** — pluggable логирование (`AndroidLogger`, `NoOpLogger`).
- **DSL-builder** — `paginator { … }` вместо позиционного конструктора (8.1+).
- **`paginator-compose`** — автоматический prefetch для `LazyColumn` / `LazyRow` /
  `LazyVerticalGrid` / `LazyVerticalStaggeredGrid`. Никаких больше `LaunchedEffect` +
  `snapshotFlow`.
- **`paginator-view`** — `bindPaginated` / `bindPrefetchToRecyclerView` для Android Views.
- **`CursorPaginator` / `MutableCursorPaginator`** — параллельный курсорный режим
  (`prev` / `self` / `next`), под GraphQL connections, чаты и любые API, которые вместо
  номеров страниц возвращают курсоры (8.3+). Если ваш бэкенд уже умеет курсоры —
  это строго лучше, чем эмулировать их поверх offset-пагинации.
- **Dirty pages** — трекинг «грязных» страниц для частичной инвалидации вместо сноса
  всего кэша.

## 9. Наглядно

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

## 10. Порядок миграции, который я бы рекомендовал

1. **Сначала Gradle.** Убрать JitPack, добавить `mavenCentral()`, перейти на BOM и нужные
   артефакты.
2. **Импорты.** `Paginator.PageState.*` → `com.jamal_aliev.paginator.page.PageState.*` и
   аналогично для остальных переехавших типов (§7).
3. **`UInt` → `Int`** везде — страницы, закладки, параметры, возвращаемые типы
   (`BookmarkUInt` → `BookmarkInt`).
4. **Обернуть источник данных.** `source = { … }` с `List<T>` → `load = { … }` с
   `LoadResult(...)`.
5. **Сменить тип переменной на `MutablePaginator`,** если вы вызываете какой-нибудь CRUD /
   `setState` / `resize`.
6. **Прогнать переименования** из §6 (`setPageState→setState`, `getPageState→getStateOf`,
   `ignoreCapacity→isCapacityUnlimited`, `enforceCapacity→coerceToCapacity`, …).
7. **Скомпилировать.** Компилятор подсветит остаток (в основном extension-функции, которые
   раньше были методами).
8. **Проверить runtime-поведение** на репрезентативных данных. Контракт страницы теперь
   `page >= 1`, `0` означает «ещё не начали»; lock-флаги и исключения живут в пакете
   `exception`.
9. **Опционально, но имеет смысл:** перейти на `uiState`, выбрать стратегию `PagingCache`,
   подцепить `saveState` / `restoreState`, DSL-builder и — если API курсорный —
   `CursorPaginator`.

## 11. Куда смотреть за деталями

Вся новая документация лежит в [docs/](../../docs). Самое полезное при миграции:

- [docs/1. core-concepts.md](../../docs/1.%20core-concepts.md)
- [docs/3. state.md](../../docs/3.%20state.md) — новый `PaginatorUiState`
- [docs/5. serialization.md](../../docs/5.%20serialization.md) — `saveState` / `restoreState`
- [docs/6. caching.md](../../docs/6.%20caching.md) — стратегии кэша
- [docs/11. dsl-builder.md](../../docs/11.%20dsl-builder.md)
- [docs/13. cursor-pagination.md](../../docs/13.%20cursor-pagination.md) и
  [docs/14. paginator-vs-cursor.md](../../docs/14.%20paginator-vs-cursor.md)

---

## Итого

Концепции остались, написание — нет. Реально пожирающие время breaking-изменения:

1. координаты артефакта и переезд на Maven Central + BOM,
2. `UInt` → `Int` для всех номеров страниц,
3. `source: () -> List<T>` → `load: () -> LoadResult<T>`,
4. разделение на `Paginator` / `MutablePaginator`,
5. партия переименований из 5.0.0,
6. перепаковка `PageState`, `Bookmark`, исключений и кэша.

Закройте эти шесть пунктов — остальное будет либо новые опциональные фичи, либо косметика.
