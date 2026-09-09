package dk.azp.jellybook.data.local

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * The listening position known on this device, on the book's own timeline and keyed by book id, so a multi-file book has one
 * entry rather than one per file. `serverPositionMs` is the server position as of the last successful sync and is what lets
 * us tell "the server moved because another device listened" apart from "the server still has what we wrote".
 */
@Serializable
data class LocalProgress(
    val itemId: String,
    val title: String,
    val author: String? = null,
    val imageTag: String? = null,
    val imageItemId: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
    val finished: Boolean = false,
    val pendingSync: Boolean = false,
    val serverPositionMs: Long? = null,
)

@Serializable
data class ProgressConflict(
    val itemId: String,
    val title: String,
    val durationMs: Long,
    val localPositionMs: Long,
    val localUpdatedAtEpochMs: Long,
    val serverPositionMs: Long,
    val serverUpdatedAtEpochMs: Long? = null,
)

class ProgressStore(context: Context) {

    private val progress = JsonListPreference(context.jellybookDataStore, "progress_json", LocalProgress.serializer())
    private val conflicts = JsonListPreference(context.jellybookDataStore, "progress_conflicts_json", ProgressConflict.serializer())

    val all: Flow<Map<String, LocalProgress>> = progress.flow.map { list -> list.associateBy { it.itemId } }

    val pendingConflicts: Flow<List<ProgressConflict>> = conflicts.flow

    suspend fun get(itemId: String): LocalProgress? = progress.read().firstOrNull { it.itemId == itemId }

    suspend fun mostRecent(): LocalProgress? = progress.read().maxByOrNull { it.updatedAtEpochMs }

    suspend fun pending(): List<LocalProgress> = progress.read().filter { it.pendingSync }

    suspend fun save(entry: LocalProgress) {
        progress.update { list ->
            (list.filterNot { it.itemId == entry.itemId } + entry).sortedByDescending { it.updatedAtEpochMs }.take(MAX_ENTRIES)
        }
    }

    suspend fun update(itemId: String, transform: (LocalProgress) -> LocalProgress) {
        progress.update { list -> list.map { if (it.itemId == itemId) transform(it) else it } }
    }

    suspend fun conflict(itemId: String): ProgressConflict? = conflicts.read().firstOrNull { it.itemId == itemId }

    suspend fun addConflict(conflict: ProgressConflict) {
        conflicts.update { list -> list.filterNot { it.itemId == conflict.itemId } + conflict }
    }

    suspend fun removeConflict(itemId: String) {
        conflicts.update { list -> list.filterNot { it.itemId == itemId } }
    }

    suspend fun clear() {
        progress.clear()
        conflicts.clear()
    }

    private companion object {
        const val MAX_ENTRIES = 300
    }
}
