package com.jamal_aliev.paginator

import com.jamal_aliev.paginator.Paginator.PageState.Empty
import com.jamal_aliev.paginator.Paginator.PageState.Error
import com.jamal_aliev.paginator.Paginator.PageState.Progress
import com.jamal_aliev.paginator.Paginator.PageState.Success
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

class Paginator<T>(val source: suspend (page: UInt) -> List<T>) {

    // TODO Настроить нормальную логику ресайзинга
    var capacity: Int = 20
        private set

    private var currentPage = 0u
    private val pages = hashMapOf<UInt, PageState<T>>()

    val bookmarks: MutableList<Bookmark> = mutableListOf(BookmarkUInt(page = 1u))
    var recyclingBookmark = false
    private var bookmarkIterator = bookmarks.listIterator()

    private val _snapshot = MutableStateFlow(emptyList<PageState<T>>())
    val snapshot = _snapshot.asStateFlow()

    var initProgressState: (() -> Progress<T>)? = null
    var initEmptyState: (() -> Empty<T>)? = null
    var initSuccessState: ((List<T>) -> Success<T>)? = null
    var initErrorState: ((Exception) -> Error<T>)? = null

    /**
     * Функция `jumpForward` предназначена для перехода к странице, указанной в следующей закладке, в коллекции `pages`.
     *
     * Входные параметры:
     * - `initProgressState`: функция, возвращающая состояние прогресса страницы, вызывается перед загрузкой состояния страницы. По умолчанию `null`.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, вызывается, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, вызывается, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, вызывается, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Функция обновляет `currentPage` на страницу, указанную в следующей закладке, если она существует, и ее состояние является состоянием данных.
     * - Если следующей закладки не существует (т.е., все закладки уже были просмотрены), функция не делает ничего.
     * - Если состояние страницы, указанной в закладке, не кэшировано, функция загружает состояние страницы из источника данных.
     * - Если источник данных вернул пустой список для страницы, функция устанавливает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список для страницы, функция устанавливает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция устанавливает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun jumpForward(
        recycling: Boolean = this.recyclingBookmark,
        initProgressState: (() -> Progress<T>)? = this.initProgressState,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ): Bookmark? {
        var bookmark = bookmarkIterator
            .takeIf { it.hasNext() }
            ?.next()

        if (bookmark == null && recycling || bookmark != null) {
            if (bookmark == null) {
                bookmarkIterator = bookmarks.listIterator()
                bookmark = bookmarkIterator
                    .takeIf { it.hasNext() }
                    ?.next() ?: return null
            }

            return jump(
                bookmark = bookmark,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )

        } else {
            return null
        }
    }

    /**
     * Функция `jumpBack` предназначена для перехода к странице, указанной в предыдущей закладке, в коллекции `pages`.
     *
     * Входные параметры:
     * - `initProgressState`: функция, возвращающая состояние прогресса страницы, вызывается перед загрузкой состояния страницы. По умолчанию `null`.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, вызывается, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, вызывается, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, вызывается, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Функция обновляет `currentPage` на страницу, указанную в предыдущей закладке, если она существует, и ее состояние является состоянием данных.
     * - Если предыдущей закладки не существует (т.е., все закладки уже были просмотрены), функция не делает ничего.
     * - Если состояние страницы, указанной в закладке, не кэшировано, функция загружает состояние страницы из источника данных.
     * - Если источник данных вернул пустой список для страницы, функция устанавливает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список для страницы, функция устанавливает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция устанавливает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun jumpBack(
        recycling: Boolean = this.recyclingBookmark,
        initProgressState: (() -> Progress<T>)? = this.initProgressState,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ): Bookmark? {
        var bookmark = bookmarkIterator
            .takeIf { it.hasPrevious() }
            ?.previous()
        if (bookmark == null && recycling || bookmark != null) {
            if (bookmark == null) {
                bookmarkIterator = bookmarks.listIterator(bookmarks.lastIndex)
                bookmark = bookmarkIterator
                    .takeIf { it.hasPrevious() }
                    ?.previous() ?: return null
            }

            return jump(
                bookmark = bookmark,
                initProgressState = initProgressState,
                initEmptyState = initEmptyState,
                initSuccessState = initSuccessState,
                initErrorState = initErrorState,
            )

        } else {
            return null
        }
    }

    /**
     * Функция `jump` предназначена для перехода к странице, указанной в закладке, в коллекции `pages`.
     *
     * Входные параметры:
     * - `bookmark`: закладка типа `Bookmark`, указывающая на страницу, к которой нужно перейти.
     * - `initProgressState`: функция, возвращающая состояние прогресса страницы, вызывается перед загрузкой состояния страницы. По умолчанию `null`.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, вызывается, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, вызывается, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, вызывается, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Функция обновляет `currentPage` на страницу, указанную в закладке, если она существует, и ее состояние является состоянием данных.
     * - Если страницы, указанной в закладке, не существует (т.е., значение закладки меньше 1), функция не делает ничего.
     * - Если состояние страницы, указанной в закладке, не кэшировано, функция загружает состояние страницы из источника данных.
     * - Если источник данных вернул пустой список для страницы, функция устанавливает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список для страницы, функция устанавливает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция устанавливает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun jump(
        bookmark: Bookmark,
        initProgressState: (() -> Progress<T>)? = this.initProgressState,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ): Bookmark {
        check(bookmark.page > 0u)
        currentPage = bookmark.page

        if (pages[bookmark.page].isValidSuccessState()) {
            _snapshot.update { scan() }
            return bookmark
        }

        loadPageState(
            page = bookmark.page,
            forceLoading = true,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState(page = page)
                pages[page] = progressState
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            pages[bookmark.page] = finalPageState
            _snapshot.update { scan() }
        }

        return bookmark
    }

    /**
     * Функция `nextPage` предназначена для перехода к следующей странице в коллекции `pages`.
     *
     * Входные параметры:
     * - `initProgressState`: функция, возвращающая состояние прогресса страницы, вызывается перед загрузкой состояния страницы. По умолчанию `null`.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, вызывается, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, вызывается, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, вызывается, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Функция обновляет `currentPage` на следующую страницу, если она существует, и ее состояние является состоянием данных.
     * - Если следующей страницы не существует (т.е., `currentPage` равно последней странице), функция не делает ничего.
     * - Если состояние следующей страницы не кэшировано, функция загружает состояние страницы из источника данных.
     * - Если источник данных вернул пустой список для страницы, функция устанавливает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список для страницы, функция устанавливает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция устанавливает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun nextPage(
        initProgressState: (() -> Progress<T>)? = this.initProgressState,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ): UInt {
        val nextPage = searchPageAfter(currentPage) { it.isValidSuccessState() } + 1u
        check(nextPage > 0u)
        if (pages[nextPage].isProgressState())
            return nextPage

        loadPageState(
            page = nextPage,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState(page = page)
                pages[page] = progressState
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            if (finalPageState.isSuccessState()) currentPage = nextPage
            pages[nextPage] = finalPageState
            _snapshot.update { scan() }
        }

        return nextPage
    }

    /**
     * Функция `previousPage` предназначена для перехода к предыдущей странице в коллекции `pages`.
     *
     * Входные параметры:
     * - `initProgressState`: функция, возвращающая состояние прогресса страницы, вызывается перед загрузкой состояния страницы. По умолчанию `null`.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, вызывается, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, вызывается, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, вызывается, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Функция обновляет `currentPage` на предыдущую страницу, если она существует, и ее состояние является состоянием данных.
     * - Если предыдущей страницы не существует (т.е., `currentPage` равно 1), функция не делает ничего.
     * - Если состояние предыдущей страницы не кэшировано, функция загружает состояние страницы из источника данных.
     * - Если источник данных вернул пустой список для страницы, функция устанавливает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список для страницы, функция устанавливает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция устанавливает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun previousPage(
        initProgressState: (() -> Progress<T>)? = this.initProgressState,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ): UInt {
        val previousPage = searchPageBefore(currentPage) { it.isValidSuccessState() } - 1u
        check(previousPage > 0u)
        if (pages[previousPage].isProgressState())
            return previousPage

        loadPageState(
            page = previousPage,
            loading = { page ->
                val progressState = initProgressState?.invoke() ?: ProgressState(page = page)
                pages[page] = progressState
                _snapshot.update { scan() }
            },
            initEmptyState = initEmptyState,
            initSuccessState = initSuccessState,
            initErrorState = initErrorState
        ).also { finalPageState ->
            if (finalPageState.isSuccessState()) currentPage = previousPage
            pages[previousPage] = finalPageState
            _snapshot.update { scan() }
        }

        return previousPage
    }

    /**
     * Функция `refresh` предназначена для обновления состояний всех страниц в коллекции `pages`.
     *
     * Входные параметры:
     * - `initProgressState`: функция, возвращающая состояние прогресса страницы, вызывается перед загрузкой состояния страницы. По умолчанию `null`.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, вызывается, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, вызывается, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, вызывается, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Функция обновляет состояния всех страниц в `pages`, загружая их из источника данных.
     * - Если источник данных вернул пустой список для страницы, функция устанавливает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список для страницы, функция устанавливает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция устанавливает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun refresh(
        initProgressState: ((data: List<T>) -> Progress<T>)? = null,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ) {
        coroutineScope {
            pages.forEach { (k, v) ->
                pages[k] = initProgressState?.invoke(v.data)
                    ?: ProgressState(page = k, data = v.data)
            }
            _snapshot.update {
                it.map { pageState ->
                    initProgressState?.invoke(pageState.data)
                        ?: ProgressState(page = pageState.page, data = pageState.data)
                }
            }
            pages.keys.toList()
                .map { page ->
                    page to async {
                        loadPageState(
                            page = page,
                            forceLoading = true,
                            initEmptyState = initEmptyState,
                            initSuccessState = initSuccessState,
                            initErrorState = initErrorState
                        )
                    }
                }
                .forEach { (page, async) ->
                    pages[page] = async.await()
                    _snapshot.update { scan() }
                }
        }
    }

    /**
     * Функция `loadPageState` предназначена для загрузки состояния страницы из источника данных или из кэша.
     *
     * Входные параметры:
     * - `page`: ключ страницы типа `UInt`, состояние которой нужно загрузить.
     * - `forceLoading`: флаг типа `Boolean`, указывающий, следует ли принудительно загружать состояние страницы из источника данных, даже если оно уже кэшировано. По умолчанию `false`.
     * - `loading`: функция, которая вызывается перед загрузкой состояния страницы из источника данных. По умолчанию `null`.
     * - `source`: функция, возвращающая список элементов типа `T` для указанной страницы. По умолчанию используется источник данных этого объекта.
     * - `initEmptyState`: функция, возвращающая пустое состояние страницы, если источник данных вернул пустой список. По умолчанию `null`.
     * - `initSuccessState`: функция, возвращающая состояние данных страницы, если источник данных вернул непустой список. По умолчанию `null`.
     * - `initErrorState`: функция, возвращающая состояние ошибки, если при загрузке состояния страницы произошла ошибка. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Возвращает состояние страницы типа `PageState<T>`. Если состояние страницы успешно загружено из источника данных или кэша, возвращается соответствующее состояние данных. Если источник данных вернул пустой список, возвращается пустое состояние. Если при загрузке состояния страницы произошла ошибка, возвращается состояние ошибки.
     *
     * Особенности и ограничения:
     * - Если `forceLoading` равно `false` и состояние страницы уже кэшировано, функция возвращает кэшированное состояние.
     * - Если `forceLoading` равно `true` или состояние страницы не кэшировано, функция загружает состояние страницы из источника данных.
     * - Если источник данных вернул пустой список, функция возвращает пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если источник данных вернул непустой список, функция возвращает состояние данных, созданное с помощью `initSuccessState`, или `DataState`, если `initSuccessState` равно `null`.
     * - Если при загрузке состояния страницы произошла ошибка, функция возвращает состояние ошибки, созданное с помощью `initErrorState`, или `ErrorState`, если `initErrorState` равно `null`.
     */
    suspend fun loadPageState(
        page: UInt,
        forceLoading: Boolean = false,
        loading: ((page: UInt) -> Unit)? = null,
        source: suspend (page: UInt) -> List<T> = this.source,
        initEmptyState: (() -> Empty<T>)? = this.initEmptyState,
        initSuccessState: ((List<T>) -> Success<T>)? = this.initSuccessState,
        initErrorState: ((Exception) -> Error<T>)? = this.initErrorState
    ): PageState<T> {
        return try {
            val cachedState = if (forceLoading) null else pages[page]
            if (cachedState is Success<*> && cachedState.data.size == capacity)
                return cachedState
            loading?.invoke(page)
            val data = source.invoke(page)
            if (data.isEmpty()) initEmptyState?.invoke() ?: EmptyState(page = page)
            else initSuccessState?.invoke(data) ?: SuccessState(page = page, data = data)
        } catch (e: Exception) {
            initErrorState?.invoke(e) ?: ErrorState(e, page = page)
        }
    }

    /**
     * Функция `removePageState` предназначена для удаления состояния страницы из коллекции `pages`.
     *
     * Входные параметры:
     * - `page`: ключ страницы типа `UInt`, состояние которой нужно удалить.
     * - `initEmptyState`: функция, возвращающая `PageState<T>`, используется для инициализации нового пустого состояния страницы после удаления. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Возвращает удаленное состояние страницы типа `PageState<T>`, если такое состояние было найдено и удалено.
     * - Если состояния страницы не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Если состояние страницы было успешно удалено, на его место устанавливается новое пустое состояние, созданное с помощью `initEmptyState`, или `EmptyState`, если `initEmptyState` равно `null`.
     * - Если страницы, состояние которой нужно удалить, не существует, функция возвращает `null`.
     */
    fun removePageState(
        page: UInt,
        initEmptyState: (() -> PageState<T>)? = null
    ): PageState<T>? {
        val removed = pages.remove(page)
        if (removed != null) {
            pages[page] = initEmptyState?.invoke()
                ?: EmptyState(page = page)
        }
        return removed
    }

    fun setPageState(
        state: PageState<T>,
        silently: Boolean = false
    ) {
        pages[state.page] = state
        if (!silently) _snapshot.update { scan() }
    }

    fun removeBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.remove(bookmark)
    }

    fun addBookmark(bookmark: Bookmark): Boolean {
        return bookmarks.add(bookmark)
    }

    /**
     * Функция `removeElement` предназначена для удаления первого элемента, который удовлетворяет определенному условию в коллекции `pages`.
     *
     * Входные параметры:
     * - `predicate`: функция-предикат, которая принимает элемент типа `T` и возвращает `Boolean`. Эта функция определяет условие, которому должен удовлетворять удаляемый элемент.
     *
     * Выходные параметры:
     * - Возвращает удаленный элемент типа `T`, если такой элемент был найден и удален.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит по всем страницам в `pages` и по всем элементам на каждой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, удовлетворяющего условию `predicate`. Если есть несколько таких элементов, функция вернет только первый найденный.
     */
    fun removeElement(predicate: (T) -> Boolean): T? {
        for (k in pages.keys.toList()) {
            val v = pages.getValue(k)
            for ((i, e) in v.data.withIndex()) {
                if (predicate(e)) {
                    return removeElement(page = k, index = i)
                }
            }
        }
        return null
    }

    /**
     * Функция `removeElement` предназначена для удаления первого элемента, который удовлетворяет определенному условию на указанной странице в коллекции `pages`.
     *
     * Входные параметры:
     * - `predicate`: функция-предикат, которая принимает элемент типа `T` и возвращает `Boolean`. Эта функция определяет условие, которому должен удовлетворять удаляемый элемент.
     * - `page`: ключ страницы типа `UInt`, на которой нужно удалить элемент.
     *
     * Выходные параметры:
     * - Возвращает удаленный элемент типа `T`, если такой элемент был найден и удален на указанной странице.
     * - Если такого элемента не найдено на указанной странице, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит только по указанной странице в `pages` и по всем элементам на этой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, удовлетворяющего условию `predicate`. Если есть несколько таких элементов на странице, функция вернет только первый найденный.
     */
    fun removeElement(page: UInt, predicate: (T) -> Boolean): T? {
        val v = pages.getValue(page)
        for ((i, e) in v.data.withIndex()) {
            if (predicate(e)) {
                return removeElement(page = page, index = i)
            }
        }
        return null
    }


    /**
     * Функция `removeElement` предназначена для удаления элемента с указанной страницы и позиции в коллекции `pages`.
     *
     * Входные параметры:
     * - `page`: ключ страницы типа `UInt`, с которой нужно удалить элемент.
     * - `index`: позиция типа `Int`, из которой нужно удалить элемент на указанной странице.
     * - `silently`: флаг типа `Boolean`, указывающий, следует ли обновлять `_snapshot` после удаления элемента. По умолчанию `false`.
     *
     * Выходные параметры:
     * - Возвращает удаленный элемент типа `T`.
     *
     * Особенности и ограничения:
     * - Если размер обновленных данных меньше `pageCapacity`, первый элемент со следующей страницы перемещается на текущую страницу.
     * - Если `silently` равно `false`, `_snapshot` обновляется после удаления элемента.
     * - Если страницы, с которой нужно удалить элемент, не существует, будет выброшено исключение `NoSuchElementException`.
     */
    fun removeElement(
        page: UInt,
        index: Int,
        silently: Boolean = false,
    ): T {
        val pageState = pages.getValue(page)
        val removed: T

        val updatedData = pageState.data.toMutableList()
            .also { removed = it.removeAt(index) }

        if (updatedData.size < capacity) {
            val nextPageState = pages[page + 1u]
            if (nextPageState != null
                && nextPageState::class == pageState::class
            ) {
                while (updatedData.size < capacity
                    && nextPageState.data.isNotEmpty()
                ) {
                    updatedData.add(
                        removeElement(
                            page = page + 1u,
                            index = 0,
                            silently = true
                        )
                    )
                }
            }
        }

        pages[page] = pageState.copy(data = updatedData)

        if (!silently) {
            val rangeSnapshot = searchPageBefore(currentPage)..searchPageAfter(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }

        return removed
    }

    /**
     * Функция `addElement` предназначена для добавления элемента в конец последней страницы в коллекции `pages`.
     *
     * Входные параметры:
     * - `element`: элемент типа `T`, который нужно добавить.
     * - `silently`: флаг типа `Boolean`, указывающий, следует ли обновлять `_snapshot` после добавления элемента. По умолчанию `false`.
     * - `initPageState`: функция, возвращающая `PageState<T>`, используется для инициализации новой страницы, если она еще не создана. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Возвращает `true`, если элемент был успешно добавлен, и `false`, если не удалось добавить элемент (например, если нет страниц в `pages`).
     *
     * Особенности и ограничения:
     * - Функция добавляет элемент в конец последней страницы в `pages`.
     * - Если `silently` равно `false`, `_snapshot` обновляется после добавления элемента.
     * - Если нет страниц в `pages`, функция возвращает `false`.
     */
    fun addElement(
        element: T,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ): Boolean {
        val lastPage = pages.keys.maxOrNull() ?: return false
        val lastIndex = pages.getValue(lastPage).data.lastIndex
        addElement(element, lastPage, lastIndex, silently, initPageState)
        return true
    }

    /**
     * Функция `addElement` предназначена для добавления элемента на указанную страницу в определенной позиции в коллекции `pages`.
     *
     * Входные параметры:
     * - `element`: элемент типа `T`, который нужно добавить.
     * - `page`: ключ страницы типа `UInt`, на которую нужно добавить элемент.
     * - `index`: позиция типа `Int`, в которую нужно добавить элемент на указанной странице.
     * - `silently`: флаг типа `Boolean`, указывающий, следует ли обновлять `_snapshot` после добавления элемента. По умолчанию `false`.
     * - `initPageState`: функция, возвращающая `PageState<T>`, используется для инициализации новой страницы, если она еще не создана. По умолчанию `null`.
     *
     * Выходные параметры:
     * - Функция не возвращает результатов, но изменяет состояние объекта, в котором вызывается.
     *
     * Особенности и ограничения:
     * - Если размер обновленных данных превышает `pageCapacity`, последний элемент удаляется и добавляется на следующую страницу.
     * - Если `silently` равно `false`, `_snapshot` обновляется после добавления элемента.
     * - Если страница, на которую нужно добавить элемент, не существует, и `initPageState` равно `null`, будет выброшено исключение `IndexOutOfBoundsException`.
     */
    fun addElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ) {
        return addAllElements(
            elements = listOf(element),
            page = page,
            index = index,
            silently = silently,
            initPageState = initPageState
        )
    }

    fun addAllElements(
        elements: List<T>,
        page: UInt,
        index: Int,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ) {
        val pageState = (initPageState?.invoke() ?: pages[page])
            ?: throw IndexOutOfBoundsException(
                "page-$page was not created"
            )

        val updatedData = pageState.data.toMutableList()
            .also { it.addAll(index, elements) }
        val extraElements =
            if (updatedData.size > capacity) {
                val initialCapacity = updatedData.size - capacity
                ArrayList<T>(initialCapacity)
                    .apply {
                        repeat(initialCapacity) { add(updatedData.removeLast()) }
                        reverse()
                    }
            } else null

        pages[page] = pageState.copy(data = updatedData)

        if (!extraElements.isNullOrEmpty()) {
            val nextPageState = pages[page + 1u]
            if ((nextPageState != null && nextPageState::class == pageState::class)
                || (nextPageState == null && initPageState != null)
            ) {
                addAllElements(
                    elements = extraElements,
                    page = page + 1u,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )
            } else {
                val rangePageInvalidated = (page + 1u)..pages.keys.last()
                for (invalid in rangePageInvalidated) pages.remove(invalid)
                currentPage = page
            }
        }

        if (!silently) {
            val rangeSnapshot = searchPageBefore(currentPage)..searchPageAfter(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }
    }


    fun setElement(
        element: T,
        silently: Boolean = false,
        predicate: (T) -> Boolean
    ) {
        for (page in pages.keys.toList()) {
            val pageState = pages.getValue(page)
            for ((index, e) in pageState.data.withIndex()) {
                if (predicate(e)) {
                    setElement(element, page, index, silently)
                }
            }
        }
    }

    fun setElement(
        element: T,
        page: UInt,
        index: Int,
        silently: Boolean = false
    ) {
        val pageState = pages.getValue(page)
        pages[page] = pageState.copy(
            data = pageState.data.toMutableList()
                .also { it[index] = element }
        )

        if (!silently) {
            val rangeSnapshot = searchPageBefore(currentPage)..searchPageAfter(currentPage)
            if (page in rangeSnapshot) {
                _snapshot.update { scan(rangeSnapshot) }
            }
        }
    }

    /**
     * Функция `indexOfFirst` предназначена для поиска первого элемента, который удовлетворяет определенному условию в коллекции `pages`.
     *
     * Входные параметры:
     * - `predicate`: функция-предикат, которая принимает элемент типа `T` и возвращает `Boolean`. Эта функция определяет условие, которому должен удовлетворять искомый элемент.
     *
     * Выходные параметры:
     * - Возвращает пару `Pair<UInt, Int>`, где первый элемент - это ключ страницы, а второй элемент - это индекс элемента на странице, который удовлетворяет условию `predicate`.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит по всем страницам в `pages` и по всем элементам на каждой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, удовлетворяющего условию `predicate`. Если есть несколько таких элементов, функция вернет только первый найденный.
     */
    fun indexOfFirst(predicate: (T) -> Boolean): Pair<UInt, Int>? {
        for (k in pages.keys.toList()) {
            val v = pages.getValue(k)
            for ((i, e) in v.data.withIndex()) {
                if (predicate(e)) {
                    return k to i
                }
            }
        }
        return null
    }

    /**
     * Функция `indexOfFirst` предназначена для поиска первого элемента, который удовлетворяет определенному условию на указанной странице в коллекции `pages`.
     *
     * Входные параметры:
     * - `predicate`: функция-предикат, которая принимает элемент типа `T` и возвращает `Boolean`. Эта функция определяет условие, которому должен удовлетворять искомый элемент.
     * - `page`: ключ страницы типа `UInt`, на которой нужно искать элемент.
     *
     * Выходные параметры:
     * - Возвращает пару `Pair<UInt, Int>`, где первый элемент - это ключ страницы, а второй элемент - это индекс элемента на странице, который удовлетворяет условию `predicate`.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит только по указанной странице в `pages` и по всем элементам на этой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, удовлетворяющего условию `predicate`. Если есть несколько таких элементов, функция вернет только первый найденный.
     */
    fun indexOfFirst(predicate: (T) -> Boolean, page: UInt): Pair<UInt, Int>? {
        val pageState = pages.getValue(page)
        for ((i, e) in pageState.data.withIndex()) {
            if (predicate(e)) {
                return page to i
            }
        }
        return null
    }

    /**
     * Функция `indexOfFirst` предназначена для поиска первого вхождения указанного элемента в коллекции `pages`.
     *
     * Входные параметры:
     * - `element`: элемент типа `T`, который нужно найти.
     *
     * Выходные параметры:
     * - Возвращает пару `Pair<UInt, Int>`, где первый элемент - это ключ страницы, а второй элемент - это индекс элемента на странице, который равен `element`.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит по всем страницам в `pages` и по всем элементам на каждой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, равного `element`. Если есть несколько таких элементов, функция вернет только первый найденный.
     */
    fun indexOfFirst(element: T): Pair<UInt, Int>? {
        return indexOfFirst { it == element }
    }

    /**
     * Функция `indexOfLast` предназначена для поиска первого элемента, который удовлетворяет определенному условию в коллекции `pages`.
     *
     * Входные параметры:
     * - `predicate`: функция-предикат, которая принимает элемент типа `T` и возвращает `Boolean`. Эта функция определяет условие, которому должен удовлетворять искомый элемент.
     *
     * Выходные параметры:
     * - Возвращает пару `Pair<UInt, Int>`, где первый элемент - это ключ страницы, а второй элемент - это индекс элемента на странице, который удовлетворяет условию `predicate`.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит по всем страницам в `pages` и по всем элементам на каждой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, удовлетворяющего условию `predicate`. Если есть несколько таких элементов, функция вернет только последний найденный.
     */
    fun indexOfLast(predicate: (T) -> Boolean): Pair<UInt, Int>? {
        for (k in pages.keys.toList().reversed()) {
            val v = pages.getValue(k)
            for ((i, e) in v.data.reversed().withIndex()) {
                if (predicate(e)) {
                    return k to i
                }
            }
        }
        return null
    }

    /**
     * Функция `indexOfLast` предназначена для поиска первого элемента, который удовлетворяет определенному условию на указанной странице в коллекции `pages`.
     *
     * Входные параметры:
     * - `predicate`: функция-предикат, которая принимает элемент типа `T` и возвращает `Boolean`. Эта функция определяет условие, которому должен удовлетворять искомый элемент.
     * - `page`: ключ страницы типа `UInt`, на которой нужно искать элемент.
     *
     * Выходные параметры:
     * - Возвращает пару `Pair<UInt, Int>`, где первый элемент - это ключ страницы, а второй элемент - это индекс элемента на странице, который удовлетворяет условию `predicate`.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит только по указанной странице в `pages` и по всем элементам на этой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, удовлетворяющего условию `predicate`. Если есть несколько таких элементов, функция вернет только последний найденный.
     */
    fun indexOfLast(predicate: (T) -> Boolean, page: UInt): Pair<UInt, Int>? {
        val pageState = pages.getValue(page)
        for ((i, e) in pageState.data.reversed().withIndex()) {
            if (predicate(e)) {
                return page to i
            }
        }
        return null
    }

    /**
     * Функция `indexOfLast` предназначена для поиска первого вхождения указанного элемента в коллекции `pages`.
     *
     * Входные параметры:
     * - `element`: элемент типа `T`, который нужно найти.
     *
     * Выходные параметры:
     * - Возвращает пару `Pair<UInt, Int>`, где первый элемент - это ключ страницы, а второй элемент - это индекс элемента на странице, который равен `element`.
     * - Если такого элемента не найдено, функция возвращает `null`.
     *
     * Особенности и ограничения:
     * - Функция проходит по всем страницам в `pages` и по всем элементам на каждой странице. Это может быть ресурсоемкой операцией для больших коллекций.
     * - Функция возвращает при первом обнаружении элемента, равного `element`. Если есть несколько таких элементов, функция вернет только последний найденный.
     */
    fun indexOfLast(element: T): Pair<UInt, Int>? {
        return indexOfLast { it == element }
    }

    fun PageState<T>?.isValidSuccessState(): Boolean {
        return (this as? Success)?.data?.size == capacity
    }

    /**
     * Функция `scan` производит сканирование страниц в заданном диапазоне.
     *
     * @param range Диапазон для сканирования типа `UIntRange`. По умолчанию, диапазон определяется функциями `getMinPageFrom` и `getMaxPageFrom`, применяемыми к текущей странице.
     * @return Список состояний страниц `List<PageState<T>>`. Если страница отсутствует в `pages`, цикл прерывается.
     *
     * Особенности использования:
     * - Функция работает с коллекцией `pages`, которая должна быть определена вне этой функции.
     * - Функция может вернуть пустой список, если в `pages` нет страниц из заданного диапазона.
     */
    fun scan(
        range: UIntRange = kotlin.run {
            val min = searchPageBefore(currentPage)
            val max = searchPageAfter(currentPage)
            return@run min..max
        }
    ): List<PageState<T>> {
        val capacity = max(range.last - range.first, 1u)
        val result = ArrayList<PageState<T>>(capacity.toInt())
        for (item in range) {
            val page = pages[item] ?: break
            result.add(page)
        }
        return result
    }

    /**
     * Функция `getMaxPageFrom` определяет максимальную страницу, удовлетворяющую заданному предикату.
     *
     * @param page Начальная страница для поиска типа `UInt`.
     * @param predicate Предикат, применяемый к состоянию страницы. По умолчанию, предикат возвращает `true` для любого состояния страницы.
     * @return Максимальная страница, удовлетворяющая предикату, типа `UInt`.
     *
     * Особенности использования:
     * - Функция может вернуть ту же самую страницу, если нет других страниц, удовлетворяющих предикату.
     */
    fun searchPageAfter(
        page: UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): UInt {
        var max = page
        while (true) {
            val pageState = pages[max + 1u]
            if (pageState != null && predicate(pageState)) max++
            else break
        }
        return max
    }

    /**
     * Функция `getMinPageFrom` определяет минимальную страницу, удовлетворяющую заданному предикату.
     *
     * @param page Начальная страница для поиска типа `UInt`.
     * @param predicate Предикат, применяемый к состоянию страницы. По умолчанию, предикат возвращает `true` для любого состояния страницы.
     * @return Максимальная страница, удовлетворяющая предикату, типа `UInt`.
     *
     * Особенности использования:
     * - Функция может вернуть ту же самую страницу, если нет других страниц, удовлетворяющих предикату.
     */
    fun searchPageBefore(
        page: UInt,
        predicate: (PageState<T>) -> Boolean = { true }
    ): UInt {
        var min = page
        while (true) {
            val pageState = pages[min - 1u]
            if (pageState != null && predicate(pageState)) min--
            else break
        }
        return min
    }

    fun resize(
        capacity: Int,
        resize: Boolean = true,
        silently: Boolean = false,
        initPageState: (() -> PageState<T>)? = null
    ) {
        if (this.capacity == capacity) return
        check(capacity > 0)
        this.capacity = capacity

        if (resize) {
            val startSuccessChain = pages.keys.toList()
                .find { pages[it].isSuccessState() }
            if (startSuccessChain != null) {
                addElement(
                    element = removeElement(
                        page = startSuccessChain,
                        index = 0,
                        silently = true
                    ),
                    page = startSuccessChain,
                    index = 0,
                    silently = true,
                    initPageState = initPageState
                )

                currentPage = startSuccessChain
            }
        }

        if (!silently) {
            _snapshot.update { scan() }
        }
    }

    /**
     * Функция `release` предназначена для сброса состояния объекта, в котором она вызывается. Эта функция не принимает входных параметров и не возвращает результатов.
     *
     * Основное предназначение:
     * Очистка коллекций `pages` и `bookmarks`, сброс текущей страницы `currentPage` до 0 и обновление `_snapshot` до пустого списка.
     *
     * Особенности и ограничения:
     * - Функция должна вызываться только когда необходимо полностью сбросить состояние объекта.
     * - После вызова этой функции все данные, связанные со страницами, закладками и текущей страницей, будут утеряны.
     */
    fun release() {
        pages.clear()
        bookmarks.clear()
        bookmarks.add(BookmarkUInt(page = 1u))
        bookmarkIterator = bookmarks.listIterator()
        currentPage = 0u
        _snapshot.update { emptyList() }
    }

    fun ProgressState(page: UInt, data: List<T> = emptyList()) =
        Progress(page, data)

    fun SuccessState(page: UInt, data: List<T> = emptyList()) =
        if (data.isEmpty()) EmptyState(page = page) else Success(page, data)

    fun EmptyState(page: UInt, data: List<T> = emptyList()) =
        Empty(page, data)

    fun ErrorState(e: Exception, page: UInt, data: List<T> = emptyList()) =
        Error(e, page, data)

    override fun toString() = "Paginator(pages=$pages, bookmarks=$bookmarks)"

    override fun hashCode() = pages.hashCode()

    override fun equals(other: Any?) = (other as? Paginator<*>)?.pages === this.pages

    companion object {
        fun PageState<*>?.isProgressState() = this is Progress<*>
        fun PageState<*>?.isEmptyState() = this is Empty<*>
        fun PageState<*>?.isSuccessState() = this is Success<*>
        fun PageState<*>?.isErrorState() = this is Error<*>
    }

    sealed class PageState<E>(
        open val page: UInt,
        open val data: List<E>
    ) {

        open class Progress<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        open class Success<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        open class Empty<T>(
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        open class Error<T>(
            val e: Exception,
            override val page: UInt,
            override val data: List<T>,
        ) : PageState<T>(page, data)

        fun copy(
            page: UInt = this.page,
            data: List<E> = this.data
        ): PageState<E> = when (this) {
            is Progress -> Progress(page, data)
            is Success -> if (data.isEmpty()) Empty(page, data) else Success(page, data)
            is Empty -> Empty(page, data)
            is Error -> Error(e, page, data)
        }

        override fun toString() = "${this::class.simpleName}(data=${this.data})"

        override fun hashCode(): Int = this.data.hashCode()

        override fun equals(other: Any?): Boolean {
            if (other !is PageState<*>) return false
            return other::class == this::class
                    && other.data === this.data
        }

    }

    @JvmInline
    value class BookmarkUInt(
        override val page: UInt
    ) : Bookmark

    interface Bookmark {
        val page: UInt
    }
}
