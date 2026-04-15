# Logger

[← Back to README](../README.md)

## Table of Contents

- [PaginatorLogger Interface](#paginatorlogger-interface)
- [Usage](#usage)
- [Logged Operations](#logged-operations)

---

The paginator supports pluggable logging via the `PaginatorLogger` interface. By default, no logging
is
performed (logger is `null`). Implement the interface and assign it to `paginator.logger` to receive
logs about navigation, state changes, and element-level operations.

## PaginatorLogger Interface

```kotlin
interface PaginatorLogger {
    companion object {
        var global: PaginatorLogger? = null
    }

    val minLevel: LogLevel get() = LogLevel.DEBUG
    fun log(level: LogLevel, component: LogComponent, message: String)
    fun isEnabled(level: LogLevel, component: LogComponent): Boolean = level >= minLevel
}
```

## Usage

The library ships `PrintPaginatorLogger` — a ready-to-use implementation that prints to stdout:

```kotlin
import com.jamal_aliev.paginator.logger.PrintPaginatorLogger
import com.jamal_aliev.paginator.logger.LogLevel
import com.jamal_aliev.paginator.logger.LogComponent

// Global logger — applies to all paginator instances
PaginatorLogger.global = PrintPaginatorLogger()

// Per-instance with level/component filtering
paginator.logger = PrintPaginatorLogger(
    minLevel = LogLevel.WARN,
    enabledComponents = setOf(LogComponent.NAVIGATION, LogComponent.CACHE)
)
```

For a custom implementation:

```kotlin
// Platform-agnostic (works on all KMP targets)
object ConsoleLogger : PaginatorLogger {
    override fun log(level: LogLevel, component: LogComponent, message: String) {
        println("[${level.name}] [${component.name}] $message")
    }
}

// Android-specific
object AndroidLogger : PaginatorLogger {
    override fun log(level: LogLevel, component: LogComponent, message: String) {
        android.util.Log.d(component.name, message)
    }
}

val paginator = MutablePaginator<String>(load = { page ->
    LoadResult(api.fetchItems(page))
}).apply {
    logger = ConsoleLogger // or AndroidLogger on Android
}
```

## Logged Operations

| Operation                    | Example message                                              |
|------------------------------|--------------------------------------------------------------|
| `jump`                       | `jump: page=5`                                               |
| `jumpForward`                | `jumpForward: recycling=true`                                |
| `jumpBack`                   | `jumpBack: recycling=false`                                  |
| `goNextPage`                 | `goNextPage: page=3 result=SuccessPage`                      |
| `goPreviousPage`             | `goPreviousPage: page=1 result=SuccessPage`                  |
| `restart`                    | `restart`                                                    |
| `refresh`                    | `refresh: pages=[1, 2, 3]`                                   |
| `setState`                   | `setState: page=2`                                           |
| `setElement`                 | `setElement: page=1 index=0 isDirty=false`                   |
| `removeElement`              | `removeElement: page=2 index=3 isDirty=false`                |
| `addAllElements`             | `addAllElements: targetPage=1 index=0 count=5 isDirty=false` |
| `removeState`                | `removeState: page=3`                                        |
| `resize`                     | `resize: capacity=10 resize=true`                            |
| `release`                    | `release`                                                    |
| `markDirty`                  | `markDirty: page=3`                                          |
| `clearDirty`                 | `clearDirty: page=3`                                         |
| `clearAllDirty`              | `clearAllDirty`                                              |
| `refreshDirtyPagesInContext` | `refreshDirtyPagesInContext: pages=[2, 3]`                   |
