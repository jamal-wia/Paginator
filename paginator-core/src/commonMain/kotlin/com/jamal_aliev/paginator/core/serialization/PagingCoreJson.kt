package com.jamal_aliev.paginator.core.serialization

import kotlinx.serialization.json.Json

/**
 * Default [Json] instance shared by `PagingCore` / `CursorPagingCore` snapshot
 * save / restore extensions across the suite.
 *
 * Configured with `ignoreUnknownKeys = true` for forward compatibility — older
 * code reading a newer snapshot won't fail on schema additions.
 */
val PagingCoreJson: Json = Json {
    ignoreUnknownKeys = true
}
