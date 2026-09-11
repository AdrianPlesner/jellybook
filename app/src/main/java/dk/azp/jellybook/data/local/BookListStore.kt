package dk.azp.jellybook.data.local

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A list of books the user put together by hand. Holds book ids, so a multi-file book counts once. */
@Serializable
data class BookList(
    val id: String,
    val name: String,
    val bookIds: List<String> = emptyList(),
    val updatedAtEpochMs: Long,
)

/** Ids of lists deleted on this device, kept so a server copy cannot bring one back during a merge. */
@Serializable
data class BookListTombstone(val listId: String, val deletedAtEpochMs: Long)

class BookListStore(context: Context) {

    private val lists = JsonListPreference(context.jellybookDataStore, "book_lists_json", BookList.serializer())
    private val tombstones = JsonListPreference(context.jellybookDataStore, "book_list_tombstones_json", BookListTombstone.serializer())
    private val dirty = JsonListPreference(context.jellybookDataStore, "book_lists_dirty_json", DirtyFlag.serializer())

    @Serializable
    data class DirtyFlag(val pending: Boolean)

    val all: Flow<List<BookList>> = lists.flow

    suspend fun read(): List<BookList> = lists.read()

    suspend fun replaceAll(replacement: List<BookList>) {
        lists.update { replacement.sortedBy { list -> list.name.lowercase() } }
    }

    suspend fun upsert(list: BookList) {
        lists.update { current -> (current.filterNot { it.id == list.id } + list).sortedBy { it.name.lowercase() } }
        markDirty()
    }

    suspend fun remove(listId: String) {
        lists.update { current -> current.filterNot { it.id == listId } }
        tombstones.update { current -> (current + BookListTombstone(listId, System.currentTimeMillis())).takeLast(MAX_TOMBSTONES) }
        markDirty()
    }

    suspend fun tombstoneIds(): Set<String> = tombstones.read().map { it.listId }.toSet()

    suspend fun isDirty(): Boolean = dirty.read().any { it.pending }

    suspend fun markDirty() {
        dirty.update { listOf(DirtyFlag(true)) }
    }

    suspend fun clearDirty() {
        dirty.update { listOf(DirtyFlag(false)) }
    }

    private companion object {
        const val MAX_TOMBSTONES = 200
    }
}
