package dk.azp.jellybook.data.chapters

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import dk.azp.jellybook.data.local.OfflineCatalog
import dk.azp.jellybook.data.model.Book

/**
 * Chapter markers for a book: Jellyfin's own chapter list when the server extracted one, otherwise the markers embedded in
 * the m4b file, parsed over byte ranges and cached on the device.
 *
 * Only a successful parse is cached. A file that turns out to have no markers is re-read next time, because a first
 * attempt can also come back empty for reasons that pass: an unreachable server, a range request the server refused, or a
 * file still being written.
 */
class ChapterRepository(
    private val dataSourceFactory: DataSource.Factory,
    private val catalog: OfflineCatalog,
) {

    suspend fun chaptersFor(book: Book, mediaUri: Uri): List<Chapter> {
        if (book.serverChapters.size >= MIN_USEFUL_CHAPTER_COUNT) return book.serverChapters
        catalog.cachedChapters(book.id)?.takeIf { it.isNotEmpty() }?.let { return it }
        return parseFromFile(book, mediaUri)
    }

    private suspend fun parseFromFile(book: Book, mediaUri: Uri): List<Chapter> {
        val container = book.container?.lowercase()
        if (container != null && container !in MP4_CONTAINERS) {
            Log.d(TAG, "No chapters for ${book.title}: $container is not a container that carries markers")
            return emptyList()
        }
        return try {
            val parsed = Mp4ChapterParser(DataSourceRandomAccessSource(dataSourceFactory, mediaUri, book.id)).parse()
            val chapters = parsed?.chapters ?: emptyList()
            if (chapters.isEmpty()) {
                val reason = if (parsed == null) "not an MP4 container" else "no chpl atom and no chapter track"
                Log.d(TAG, "No chapters found in ${book.title}: $reason")
            } else {
                catalog.cacheChapters(book.id, chapters)
                Log.d(TAG, "Read ${chapters.size} chapters from ${book.title}")
            }
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
