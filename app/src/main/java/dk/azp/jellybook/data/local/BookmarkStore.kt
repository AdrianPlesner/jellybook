package dk.azp.jellybook.data.local

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class Bookmark(
    val id: String,
    val itemId: String,
    val name: String,
    val positionMs: Long,
    val createdAtEpochMs: Long,
)

/** Ids of bookmarks deleted on this device, kept so a server copy cannot resurrect them during a merge. */
@Serializable
data class BookmarkTombstone(val bookmarkId: String, val deletedAtEpochMs: Long)

@Serializable
data class DirtyBookmarkItem(val itemId: String)

class BookmarkStore(context: Context) {

    private val bookmarks = JsonListPreference(context.jellybookDataStore, "bookmarks_json", Bookmark.serializer())
    private val tombstones = JsonListPreference(context.jellybookDataStore, "bookmark_tombstones_json", BookmarkTombstone.serializer())
    private val dirtyItems = JsonListPreference(context.jellybookDataStore, "bookmark_dirty_items_json", DirtyBookmarkItem.serializer())

    fun bookmarks(itemId: String): Flow<List<Bookmark>> =
        bookmarks.flow.map { list -> list.filter { it.itemId == itemId }.sortedBy { it.positionMs } }

    suspend fun list(itemId: String): List<Bookmark> = bookmarks.read().filter { it.itemId == itemId }.sortedBy { it.positionMs }

    suspend fun replaceAll(itemId: String, replacement: List<Bookmark>) {
        bookmarks.update { list -> list.filterNot { it.itemId == itemId } + replacement }
    }

    suspend fun tombstoneIds(): Set<String> = tombstones.read().map { it.bookmarkId }.toSet()

    suspend fun addTombstone(bookmarkId: String) {
        tombstones.update { list -> (list + BookmarkTombstone(bookmarkId, System.currentTimeMillis())).takeLast(MAX_TOMBSTONES) }
    }

    suspend fun dirtyItemIds(): Set<String> = dirtyItems.read().map { it.itemId }.toSet()

    suspend fun markDirty(itemId: String) {
        dirtyItems.update { list -> if (list.any { it.itemId == itemId }) list else list + DirtyBookmarkItem(itemId) }
    }

    suspend fun clearDirty(itemId: String) {
        dirtyItems.update { list -> list.filterNot { it.itemId == itemId } }
    }

    private companion object {
        const val MAX_TOMBSTONES = 500
    }
}
