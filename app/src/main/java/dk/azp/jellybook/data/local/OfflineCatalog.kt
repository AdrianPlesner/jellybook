package dk.azp.jellybook.data.local

import android.content.Context
import dk.azp.jellybook.data.chapters.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Everything the app needs to show and play a book while the server is unreachable. */
@Serializable
data class DownloadedBook(
    val itemId: String,
    val title: String,
    val author: String? = null,
    val overview: String? = null,
    val durationMs: Long,
    val imageTag: String? = null,
    val mediaSourceId: String? = null,
    val container: String? = null,
    val productionYear: Int? = null,
    val chapters: List<Chapter> = emptyList(),
    val coverPath: String? = null,
    val downloadedAtEpochMs: Long,
)

@Serializable
data class CachedChapters(val itemId: String, val chapters: List<Chapter>, val cachedAtEpochMs: Long)

class OfflineCatalog(context: Context) {

    private val books = JsonListPreference(context.jellybookDataStore, "downloaded_books_json", DownloadedBook.serializer())
    private val chapters = JsonListPreference(context.jellybookDataStore, "chapter_cache_json", CachedChapters.serializer())

    val downloadedBooks: Flow<List<DownloadedBook>> = books.flow

    suspend fun downloadedBook(itemId: String): DownloadedBook? = books.read().firstOrNull { it.itemId == itemId }

    suspend fun put(book: DownloadedBook) {
        books.update { list -> list.filterNot { it.itemId == book.itemId } + book }
    }

    suspend fun remove(itemId: String) {
        books.update { list -> list.filterNot { it.itemId == itemId } }
    }

    suspend fun cachedChapters(itemId: String): List<Chapter>? = chapters.read().firstOrNull { it.itemId == itemId }?.chapters

    suspend fun cacheChapters(itemId: String, chapterList: List<Chapter>) {
        chapters.update { list ->
            (list.filterNot { it.itemId == itemId } + CachedChapters(itemId, chapterList, System.currentTimeMillis()))
                .sortedByDescending { it.cachedAtEpochMs }
                .take(MAX_CACHED_BOOKS)
        }
    }

    private companion object {
        const val MAX_CACHED_BOOKS = 100
    }
}
