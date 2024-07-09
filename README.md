# **Paginator**

[![Release](https://jitpack.io/v/jamal-wia/Paginator.svg)](https://jitpack.io/#jamal-wia/Paginator) [![license](https://img.shields.io/badge/license-MIT-blue.svg)](https://opensource.org/licenses/MIT)

**Paginator** - это современная библеотека для реализации пагинации в вашем Android приложении,
в которой реализованно моножество сложных кейсов таких как: "прыжки", загрузка следующих и
предыдущих страниц,
множественные источники данных, предварительная загрузка данных и удобное API для взаимодействия
[Telegram Paginator_Library](https://t.me/+0eeAM-EJpqgwNGZi)
[YouTube Tutorial RU](https://www.youtube.com/watch?v=YsUX7-FgKgA)

## **Подключение**

``` Gradle
repositories {
    ....
    maven { setUrl("https://jitpack.io") }
}
```

``` Gradle
implementation("com.github.jamal-wia:Paginator:3.2.0")
```

## **Быстрый старт в 3 шага**

### Шаг 1

Вам необходимо создать объект Paginator в вашем Presenter or ViewModel. И указать основной источник
данных (REST API, Database)

#### Пример кода

``` Kotlin
class MainViewModel : ViewModel() {

    private val paginator = Paginator { SampleRepository.loadPage(it.toInt()) }

}
```

### Шаг 2

Далее вам необходимо "прыгнуть" на определенную "закладку" (по умолчанию это первая страница)

#### Пример кода

``` Kotlin
class MainViewModel : ViewModel() {
    
    private val paginator = Paginator { SampleRepository.loadPage(it.toInt()) }
    
    init {
        viewModelScope.launch {
            // асинхронная загрузка сразу нескольких страничек (зарание)
            val async1 = async { paginator.loadPageState(1u) } // опционально
            val async2 = async { paginator.loadPageState(2u) } // опционально
            val async3 = async { paginator.loadPageState(3u) } // опционально
            paginator.setPageState(page = 1u, async1.await()) // опционально
            paginator.setPageState(page = 2u, async2.await()) // опционально
            paginator.setPageState(page = 3u, async3.await()) // опционально
            paginator.jumpForward() // "прыжок" на "закладку" (первую страницу)
        }
    }
}
```


### Шаг 3

После того как вам больше не нужен инстанс Paginator'a вы должны вызывать метод очистки

#### Пример кода

``` Kotlin
class MainViewModel : ViewModel() {
    
    private val paginator = Paginator { SampleRepository.loadPage(it.toInt()) }
    
    override fun onCleared() {
        paginator.release()
        super.onCleared()
    }
}
```

## License

```
The MIT License (MIT)

Copyright (c) 2023 Jamal Aliev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
