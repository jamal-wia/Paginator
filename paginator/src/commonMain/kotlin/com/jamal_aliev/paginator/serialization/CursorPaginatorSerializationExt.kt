package com.jamal_aliev.paginator.serialization

import com.jamal_aliev.paginator.CursorPaginator
import com.jamal_aliev.paginator.load.Metadata
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Saves the current [CursorPaginator] state to a JSON string.
 *
 * Thread-safe: acquires the navigation mutex internally.
 */
suspend fun <T, K : Any> CursorPaginator<T>.saveStateToJson(
    elementSerializer: KSerializer<T>,
    keySerializer: KSerializer<K>,
    json: Json = PagingCoreJson,
    contextOnly: Boolean = false,
): String {
    @Suppress("UNCHECKED_CAST")
    val encoder: (Any) -> JsonElement = { raw ->
        json.encodeToJsonElement(keySerializer, raw as K)
    }
    val snapshot: CursorPaginatorSnapshot<T> = saveState(encoder, contextOnly)
    val snapshotSerializer: KSerializer<CursorPaginatorSnapshot<T>> =
        CursorPaginatorSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/**
 * Restores [CursorPaginator] state from a JSON string previously produced by
 * [saveStateToJson].
 */
suspend fun <T, K : Any> CursorPaginator<T>.restoreStateFromJson(
    jsonString: String,
    elementSerializer: KSerializer<T>,
    keySerializer: KSerializer<K>,
    json: Json = PagingCoreJson,
    silently: Boolean = false,
) {
    val decoder: (JsonElement) -> Any = { element ->
        json.decodeFromJsonElement(keySerializer, element) as Any
    }
    val snapshotSerializer: KSerializer<CursorPaginatorSnapshot<T>> =
        CursorPaginatorSnapshot.serializer(elementSerializer)
    val snapshot: CursorPaginatorSnapshot<T> =
        json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, decoder, silently)
}

/** Metadata-aware counterpart of the plain [saveStateToJson]. */
suspend fun <T, K : Any, M : Metadata> CursorPaginator<T>.saveStateToJson(
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
    val snapshot: CursorPaginatorSnapshot<T> = saveState(encoder, contextOnly) { metadata ->
        metadata?.let { json.encodeToJsonElement(metadataSerializer, it as M) }
    }
    val snapshotSerializer = CursorPaginatorSnapshot.serializer(elementSerializer)
    return json.encodeToString(snapshotSerializer, snapshot)
}

/** Metadata-aware counterpart of the plain [restoreStateFromJson]. */
suspend fun <T, K : Any, M : Metadata> CursorPaginator<T>.restoreStateFromJson(
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
    val snapshotSerializer = CursorPaginatorSnapshot.serializer(elementSerializer)
    val snapshot = json.decodeFromString(snapshotSerializer, jsonString)
    restoreState(snapshot, decoder, silently) { el ->
        el?.let { json.decodeFromJsonElement(metadataSerializer, it) }
    }
}
