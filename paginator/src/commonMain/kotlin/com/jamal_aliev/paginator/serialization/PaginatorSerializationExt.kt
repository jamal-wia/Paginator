package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.Paginator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Saves the current [Paginator] state to a JSON string.
 *
 * This is a convenience wrapper that calls [Paginator.saveState] and serializes
 * the resulting [PaginatorSnapshot] to JSON.
 *
 * Thread-safe: acquires the navigation mutex internally.
 *
 * Usage:
 * ```kotlin
 * val json = paginator.saveStateToJson(MyItem.serializer())
 * ```
 *
 * @param elementSerializer The [KSerializer] for the element type `T`.
 * @param json The [Json] instance to use for encoding. Defaults to [PagingCoreJson].
 * @param contextOnly If `true`, only pages within the context window are included.
 * @return A JSON string representation of the [PaginatorSnapshot].
 */
suspend fun <T> Paginator<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    json: Json = PagingCoreJson,
    contextOnly: Boolean = false,
): String {
    val snapshot: PaginatorSnapshot<T> = saveState(contextOnly)
    val snapshotSerializer: KSerializer<PaginatorSnapshot<T>> =
        PaginatorSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/**
 * Restores [Paginator] state from a JSON string previously produced by [saveStateToJson].
 *
 * Thread-safe: acquires the navigation mutex internally.
 *
 * Usage:
 * ```kotlin
 * paginator.restoreStateFromJson(jsonString, MyItem.serializer())
 * ```
 *
 * @param jsonString The JSON string to decode.
 * @param elementSerializer The [KSerializer] for the element type `T`.
 * @param json The [Json] instance to use for decoding. Defaults to [PagingCoreJson].
 * @param silently If `true`, no snapshot is emitted after restoration.
 */
suspend fun <T> Paginator<T>.restoreStateFromJson(
    jsonString: String,
    elementSerializer: KSerializer<T>,
    json: Json = PagingCoreJson,
    silently: Boolean = false,
) {
    val snapshotSerializer: KSerializer<PaginatorSnapshot<T>> = PaginatorSnapshot.serializer(elementSerializer)
    val snapshot: PaginatorSnapshot<T> = json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, silently)
}
