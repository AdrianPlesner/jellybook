package dk.azp.jellybook.data.bookmarks

import android.util.Log
import dk.azp.jellybook.data.Connectivity
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.local.Bookmark
import dk.azp.jellybook.data.local.BookmarkStore
import dk.azp.jellybook.data.local.SessionStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Named bookmarks per book. The device copy is authoritative for offline use; when online the list is merged with the copy
 * kept in the user's Jellyfin display preferences so bookmarks follow the user across devices.
 */
class BookmarkRepository(
    private val client: JellyfinClient,
    private val sessionStore: SessionStore,
    private val store: BookmarkStore,
    private val connectivity: Connectivity,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    fun bookmarks(itemId: String): Flow<List<Bookmark>> = store.bookmarks(itemId)

    suspend fun add(itemId: String, name: String, positionMs: Long): Bookmark {
        val bookmark = Bookmark(UUID.randomUUID().toString(), itemId, name.trim().ifBlank { "Bookmark" }, positionMs, System.currentTimeMillis())
        store.replaceAll(itemId, store.list(itemId) + bookmark)
        store.markDirty(itemId)
        sync(itemId)
        return bookmark
    }

    suspend fun rename(bookmark: Bookmark, newName: String) {
        val renamed = bookmark.copy(name = newName.trim().ifBlank { bookmark.name })
        store.replaceAll(bookmark.itemId, store.list(bookmark.itemId).map { if (it.id == bookmark.id) renamed else it })
        store.markDirty(bookmark.itemId)
        sync(bookmark.itemId)
    }

    suspend fun remove(bookmark: Bookmark) {
        store.replaceAll(bookmark.itemId, store.list(bookmark.itemId).filterNot { it.id == bookmark.id })
        store.addTombstone(bookmark.id)
        store.markDirty(bookmark.itemId)
        sync(bookmark.itemId)
    }

    suspend fun sync(itemId: String) = syncMutex.withLock { syncLocked(itemId) }

    suspend fun flushPending() = syncMutex.withLock { store.dirtyItemIds().forEach { syncLocked(it) } }

    private suspend fun syncLocked(itemId: String) {
        val session = sessionStore.currentSession() ?: return
        if (!connectivity.isOnline()) return
        try {
            val preferences = client.displayPreferences(session.serverUrl, session.userId)
            val customPrefs = preferences[CUSTOM_PREFS] as? JsonObject ?: JsonObject(emptyMap())
            val key = "$BOOKMARK_KEY_PREFIX$itemId"
            val remote = customPrefs[key]?.jsonPrimitive?.contentOrNull?.let(::decode) ?: emptyList()
            val local = store.list(itemId)
            val tombstones = store.tombstoneIds()
            val merged = (local + remote.filter { candidate -> local.none { it.id == candidate.id } })
                .filterNot { it.id in tombstones }
                .sortedBy { it.positionMs }
            if (merged != local) store.replaceAll(itemId, merged)
            if (merged.toSet() != remote.toSet()) {
                val updatedCustomPrefs = if (merged.isEmpty()) customPrefs - key else customPrefs + (key to JsonPrimitive(json.encodeToString(merged)))
                client.updateDisplayPreferences(session.serverUrl, session.userId, JsonObject(preferences + (CUSTOM_PREFS to JsonObject(updatedCustomPrefs))))
            }
            store.clearDirty(itemId)
        } catch (e: IOException) {
            Log.w(TAG, "Bookmark sync deferred for $itemId", e)
            store.markDirty(itemId)
        }
    }

    private fun decode(raw: String): List<Bookmark> = runCatching { json.decodeFromString<List<Bookmark>>(raw) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "BookmarkRepository"
        const val CUSTOM_PREFS = "CustomPrefs"
        const val BOOKMARK_KEY_PREFIX = "bookmarks."
    }
}
