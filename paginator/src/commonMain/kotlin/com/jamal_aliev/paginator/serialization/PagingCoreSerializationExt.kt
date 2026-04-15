package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.PagingCore
import com.jamal_aliev.paginator.load.Metadata
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Default [Json] instance used for PagingCore serialization.
 * Uses `ignoreUnknownKeys = true` for forward compatibility.
 */
internal val PagingCoreJson: Json = Json {
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
 * @param json The [Json] instance to use for encoding. Defaults to `Json { ignoreUnknownKeys = true }`.
 * @return A JSON string representation of the [PagingCoreSnapshot].
 */
fun <T> PagingCore<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    json: Json = PagingCoreJson,
    contextOnly: Boolean = false,
): String {
    val snapshot = saveState(contextOnly)
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
 * @param json The [Json] instance to use for decoding. Defaults to `Json { ignoreUnknownKeys = true }`.
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

/**
 * Saves the current [PagingCore] state to a JSON string, including per-page metadata.
 *
 * Usage:
 * ```kotlin
 * val json = paginator.core.saveStateToJson(MyItem.serializer(), MyMetadata.serializer())
 * ```
 *
 * @param elementSerializer The [KSerializer] for the element type `T`.
 * @param metadataSerializer The [KSerializer] for the metadata type `M`. All pages are
 *   expected to carry the same metadata subtype; a `ClassCastException` will be thrown
 *   at runtime if a page contains a different [Metadata] subtype.
 * @param json The [Json] instance to use for encoding. Defaults to `Json { ignoreUnknownKeys = true }`.
 * @param contextOnly If `true`, only pages within the context window are included.
 * @return A JSON string representation of the [PagingCoreSnapshot] with metadata.
 */
fun <T, M : Metadata> PagingCore<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    metadataSerializer: KSerializer<M>,
    json: Json = PagingCoreJson,
    contextOnly: Boolean = false,
): String {
    @Suppress("UNCHECKED_CAST")
    val snapshot = saveState(contextOnly) { metadata ->
        metadata?.let { json.encodeToJsonElement(metadataSerializer, it as M) }
    }
    val snapshotSerializer = PagingCoreSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/**
 * Restores [PagingCore] state from a JSON string previously produced by
 * [saveStateToJson] with a metadata serializer, restoring per-page metadata.
 *
 * Usage:
 * ```kotlin
 * paginator.core.restoreStateFromJson(jsonString, MyItem.serializer(), MyMetadata.serializer())
 * ```
 *
 * @param jsonString The JSON string to decode.
 * @param elementSerializer The [KSerializer] for the element type `T`.
 * @param metadataSerializer The [KSerializer] for the metadata type `M`.
 * @param json The [Json] instance to use for decoding. Defaults to `Json { ignoreUnknownKeys = true }`.
 * @param silently If `true`, no snapshot is emitted after restoration.
 */
fun <T, M : Metadata> PagingCore<T>.restoreStateFromJson(
    jsonString: String,
    elementSerializer: KSerializer<T>,
    metadataSerializer: KSerializer<M>,
    json: Json = PagingCoreJson,
    silently: Boolean = false,
) {
    val snapshotSerializer = PagingCoreSnapshot.serializer(elementSerializer)
    val snapshot = json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, silently) { jsonElement ->
        jsonElement?.let { json.decodeFromJsonElement(metadataSerializer, it) }
    }
}
