package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.PagingCore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Default [Json] instance used for PagingCore serialization.
 * Uses `ignoreUnknownKeys = true` for forward compatibility.
 */
val PagingCoreJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Saves the current [PagingCore] state to a JSON string.
 *
 * Usage:
 * ```kotlin
 * val json = paginator.core.saveStateToJson(MyItem.serializer())
 * ```
 *
 * @param elementSerializer The [KSerializer] for the element type `T`.
 *   Typically obtained via `MyItem.serializer()` on a `@Serializable` data class.
 * @param json The [Json] instance to use for encoding. Defaults to [PagingCoreJson].
 * @return A JSON string representation of the [PagingCoreSnapshot].
 */
fun <T> PagingCore<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    json: Json = PagingCoreJson,
): String {
    val snapshot = saveState()
    val snapshotSerializer = PagingCoreSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/**
 * Restores [PagingCore] state from a JSON string previously produced by [saveStateToJson].
 *
 * Usage:
 * ```kotlin
 * paginator.core.restoreStateFromJson(jsonString, MyItem.serializer())
 * ```
 *
 * @param jsonString The JSON string to decode.
 * @param elementSerializer The [KSerializer] for the element type `T`.
 * @param json The [Json] instance to use for decoding. Defaults to [PagingCoreJson].
 * @param silently If `true`, no snapshot is emitted after restoration.
 */
fun <T> PagingCore<T>.restoreStateFromJson(
    jsonString: String,
    elementSerializer: KSerializer<T>,
    json: Json = PagingCoreJson,
    silently: Boolean = false,
) {
    val snapshotSerializer = PagingCoreSnapshot.serializer(elementSerializer)
    val snapshot = json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, silently)
}
