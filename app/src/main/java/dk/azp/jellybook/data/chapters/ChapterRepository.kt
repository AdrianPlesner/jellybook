package dk.azp.jellybook.data.chapters

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import dk.azp.jellybook.data.local.OfflineCatalog
import dk.azp.jellybook.data.model.Book

/**
 * Chapter markers for a book: Jellyfin's own chapter list when the server extracted one, otherwise the markers embedded in
 * the m4b file, parsed once and cached on the device.
 */
class ChapterRepository(
    private val dataSourceFactory: DataSource.Factory,
    private val catalog: OfflineCatalog,
) {

    suspend fun chaptersFor(book: Book, mediaUri: Uri): List<Chapter> {
        if (book.serverChapters.size >= MIN_USEFUL_CHAPTER_COUNT) return book.serverChapters
        val cached = catalog.cachedChapters(book.id)
        if (cached != null) return cached
        return parseFromFile(book, mediaUri)
    }

    private suspend fun parseFromFile(book: Book, mediaUri: Uri): List<Chapter> {
        val container = book.container?.lowercase()
        if (container != null && container !in MP4_CONTAINERS) return emptyList()
        return try {
            val parsed = Mp4ChapterParser(DataSourceRandomAccessSource(dataSourceFactory, mediaUri, book.id)).parse()
            val chapters = parsed?.chapters ?: emptyList()
            catalog.cacheChapters(book.id, chapters)
            chapters
        } catch (e: Exception) {
            Log.w(TAG, "Could not read chapters for ${book.title}", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ChapterRepository"
        const val MIN_USEFUL_CHAPTER_COUNT = 2
        val MP4_CONTAINERS = setOf("m4b", "m4a", "mp4", "mov", "aac", "m4b,mp4", "mp4,m4b")
    }
}
