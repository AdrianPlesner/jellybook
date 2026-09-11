package dk.azp.jellybook.data.chapters

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import dk.azp.jellybook.data.local.OfflineCatalog
import dk.azp.jellybook.data.model.Book

/**
 * Chapters, plus why there are none. [note] is meant to be read by anyone; [trace] is the parser's own account of what it
 * found and is only shown when debug mode is on.
 */
data class ChapterResult(val chapters: List<Chapter>, val note: String? = null, val trace: String? = null)

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

    suspend fun chaptersFor(book: Book, mediaUri: Uri): ChapterResult {
        if (book.serverChapters.size >= MIN_USEFUL_CHAPTER_COUNT) return ChapterResult(book.serverChapters)
        catalog.cachedChapters(book.id)?.takeIf { it.isNotEmpty() }?.let { return ChapterResult(it) }
        return parseFromFile(book, mediaUri)
    }

    private suspend fun parseFromFile(book: Book, mediaUri: Uri): ChapterResult {
        val container = book.container?.lowercase()
        if (container != null && container !in MP4_CONTAINERS) {
            // An mp3 can hold chapters in ID3 frames, but Jellyfin extracts those itself, so if the server reported none
            // the usual reason is that the file has none. Say that, rather than implying the app is the limitation.
            val note = "No chapter markers. In a $container file these come from the server, which reported none."
            Log.d(TAG, "No chapters for ${book.title}: $note")
            return ChapterResult(emptyList(), note)
        }
        return try {
            val parsed = Mp4ChapterParser(DataSourceRandomAccessSource(dataSourceFactory, mediaUri, book.id)).parse()
            val chapters = parsed?.chapters ?: emptyList()
            when {
                chapters.isNotEmpty() -> {
                    catalog.cacheChapters(book.id, chapters)
                    Log.d(TAG, "Read ${chapters.size} chapters from ${book.title}")
                    ChapterResult(chapters)
                }
                parsed == null -> ChapterResult(emptyList(), "The file does not begin like an MP4 container.")
                // No chapter structure was found at all. Saying "none the app can read" would imply the app is the
                // limitation; the trace behind debug mode is there for the times when that doubt is warranted.
                else -> ChapterResult(emptyList(), "This file has no chapter markers.", parsed.trace)
            }.also { if (it.chapters.isEmpty()) Log.d(TAG, "No chapters in ${book.title}: ${it.note}") }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read chapters for ${book.title}", e)
            ChapterResult(emptyList(), "Could not read the file.", "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim())
        }
    }

    private companion object {
        const val TAG = "ChapterRepository"
        const val MIN_USEFUL_CHAPTER_COUNT = 2
        val MP4_CONTAINERS = setOf("m4b", "m4a", "mp4", "mov", "aac", "m4b,mp4", "mp4,m4b")
    }
}
