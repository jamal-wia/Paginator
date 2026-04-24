package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.CursorPagingCore
import com.jamal_aliev.paginator.load.Metadata
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Saves the current [CursorPagingCore] state to a JSON string.
 *
 * @param K The cursor-key type.
 * @param elementSerializer The [KSerializer] for the element type `T`.
 * @param keySerializer The [KSerializer] used to encode/decode every
 *   [com.jamal_aliev.paginator.bookmark.CursorBookmark.self] key.
 */
fun <T, K : Any> CursorPagingCore<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    keySerializer: KSerializer<K>,
    json: Json = PagingCoreJson,
    contextOnly: Boolean = false,
): String {
    @Suppress("UNCHECKED_CAST")
    val encoder: (Any) -> JsonElement = { raw ->
        json.encodeToJsonElement(keySerializer, raw as K)
    }
    val snapshot = saveState(contextOnly, encoder)
    val snapshotSerializer = CursorPagingCoreSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/**
 * Restores [CursorPagingCore] state from a JSON string previously produced by
 * [saveStateToJson].
 */
fun <T, K : Any> CursorPagingCore<T>.restoreStateFromJson(
    jsonString: String,
    elementSerializer: KSerializer<T>,
    keySerializer: KSerializer<K>,
    json: Json = PagingCoreJson,
    silently: Boolean = false,
) {
    val decoder: (JsonElement) -> Any = { element ->
        json.decodeFromJsonElement(keySerializer, element) as Any
    }
    val snapshotSerializer = CursorPagingCoreSnapshot.serializer(elementSerializer)
    val snapshot = json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, silently, decoder)
}

/** Metadata-aware counterpart of the plain [saveStateToJson] above. */
fun <T, K : Any, M : Metadata> CursorPagingCore<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    keySerializer: KSerializer<K>,
    metadataSerializer: KSerializer<M>,
    json: Json = PagingCoreJson,
    contextOnly: Boolean = false,
): String {
    @Suppress("UNCHECKED_CAST")
    val encoder: (Any) -> JsonElement = { raw ->
        json.encodeToJsonElement(keySerializer, raw as K)
    }

    @Suppress("UNCHECKED_CAST")
    val snapshot = saveState(contextOnly, encoder) { metadata ->
        metadata?.let { json.encodeToJsonElement(metadataSerializer, it as M) }
    }
    val snapshotSerializer = CursorPagingCoreSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/** Metadata-aware counterpart of the plain [restoreStateFromJson] above. */
fun <T, K : Any, M : Metadata> CursorPagingCore<T>.restoreStateFromJson(
    jsonString: String,
    elementSerializer: KSerializer<T>,
    keySerializer: KSerializer<K>,
    metadataSerializer: KSerializer<M>,
    json: Json = PagingCoreJson,
    silently: Boolean = false,
) {
    val decoder: (JsonElement) -> Any = { element ->
        json.decodeFromJsonElement(keySerializer, element) as Any
    }
    val snapshotSerializer = CursorPagingCoreSnapshot.serializer(elementSerializer)
    val snapshot = json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, silently, decoder) { el ->
        el?.let { json.decodeFromJsonElement(metadataSerializer, it) }
    }
}
