package dk.azp.jellybook.data.lists

import android.util.Log
import dk.azp.jellybook.data.Connectivity
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.local.BookList
import dk.azp.jellybook.data.local.BookListStore
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
 * Lists of books the user made, kept on the device and mirrored into the user's Jellyfin display preferences so they follow
 * the user to another device.
 *
 * Jellyfin's own playlists are not used: adding a folder to one expands it into the files inside, so a multi-file book
 * would appear as a row per file instead of one book. Storing book ids keeps a book a book.
 */
class BookListRepository(
    private val client: JellyfinClient,
    private val sessionStore: SessionStore,
    private val store: BookListStore,
    private val connectivity: Connectivity,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    val lists: Flow<List<BookList>> = store.all

    suspend fun create(name: String, firstBookId: String? = null): BookList {
        val list = BookList(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Untitled list" },
            bookIds = listOfNotNull(firstBookId),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.upsert(list)
        sync()
        return list
    }

    suspend fun rename(listId: String, name: String) {
        val existing = store.read().firstOrNull { it.id == listId } ?: return
        store.upsert(existing.copy(name = name.trim().ifBlank { existing.name }, updatedAtEpochMs = System.currentTimeMillis()))
        sync()
    }

    suspend fun delete(listId: String) {
        store.remove(listId)
        sync()
    }

    suspend fun setMembership(listId: String, bookId: String, member: Boolean) {
        val existing = store.read().firstOrNull { it.id == listId } ?: return
        val bookIds = if (member) {
            if (bookId in existing.bookIds) existing.bookIds else existing.bookIds + bookId
        } else {
            existing.bookIds - bookId
        }
        if (bookIds == existing.bookIds) return
        store.upsert(existing.copy(bookIds = bookIds, updatedAtEpochMs = System.currentTimeMillis()))
        sync()
    }

    /** Drops a book that no longer exists from every list, so a removed book leaves no dead entry behind. */
    suspend fun forgetMissingBooks(knownBookIds: Set<String>) {
        val current = store.read()
        val cleaned = current.map { list ->
            val kept = list.bookIds.filter { it in knownBookIds }
            if (kept.size == list.bookIds.size) list else list.copy(bookIds = kept, updatedAtEpochMs = System.currentTimeMillis())
        }
        if (cleaned != current) {
            store.replaceAll(cleaned)
            store.markDirty()
            sync()
        }
    }

    suspend fun sync() = syncMutex.withLock { syncLocked() }

    suspend fun flushPending() {
        if (store.isDirty()) sync()
    }

    private suspend fun syncLocked() {
        val session = sessionStore.currentSession() ?: return
        if (!connectivity.isOnline()) return
        try {
            val preferences = client.displayPreferences(session.serverUrl, session.userId)
            val customPrefs = preferences[CUSTOM_PREFS] as? JsonObject ?: JsonObject(emptyMap())
            val remote = customPrefs[LISTS_KEY]?.jsonPrimitive?.contentOrNull?.let(::decode) ?: emptyList()
            val merged = merge(store.read(), remote, store.tombstoneIds())
            if (merged != store.read()) store.replaceAll(merged)
            if (merged.toSet() != remote.toSet()) {
                val updated = customPrefs + (LISTS_KEY to JsonPrimitive(json.encodeToString(merged)))
                client.updateDisplayPreferences(session.serverUrl, session.userId, JsonObject(preferences + (CUSTOM_PREFS to JsonObject(updated))))
            }
            store.clearDirty()
        } catch (e: IOException) {
            Log.w(TAG, "List sync deferred", e)
            store.markDirty()
        }
    }

    /** Newest edit of a list wins; a list deleted here stays deleted. */
    private fun merge(local: List<BookList>, remote: List<BookList>, tombstones: Set<String>): List<BookList> {
        val byId = local.associateBy { it.id }.toMutableMap()
        for (candidate in remote) {
            if (candidate.id in tombstones) continue
            val mine = byId[candidate.id]
            if (mine == null || candidate.updatedAtEpochMs > mine.updatedAtEpochMs) byId[candidate.id] = candidate
        }
        return byId.values.sortedBy { it.name.lowercase() }
    }

    private fun decode(raw: String): List<BookList> =
        runCatching { json.decodeFromString<List<BookList>>(raw) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "BookListRepository"
        const val CUSTOM_PREFS = "CustomPrefs"
        const val LISTS_KEY = "booklists"
    }
}
