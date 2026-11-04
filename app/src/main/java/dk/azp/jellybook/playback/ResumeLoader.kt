package dk.azp.jellybook.playback

import androidx.media3.common.MediaItem
import dk.azp.jellybook.data.BookRepository
import dk.azp.jellybook.data.downloads.DownloadRepository

/** A book's playlist and where in it to start. */
data class ResumePoint(val items: List<MediaItem>, val partIndex: Int, val offsetInPartMs: Long)

/**
 * Rebuilds the playlist of a book without its book screen. A downloaded book comes from the offline catalog, so it starts
 * without waiting for (or reaching) the server; any other book is fetched from the server.
 */
class ResumeLoader(
    private val downloadRepository: DownloadRepository,
    private val bookRepository: BookRepository,
    private val mediaItemFactory: MediaItemFactory,
) {

    /** Null when the book is neither downloaded nor reachable on the server, or nobody is signed in. */
    suspend fun load(bookId: String, positionMs: Long): ResumePoint? {
        val book = downloadRepository.downloadedBook(bookId)
            ?: runCatching { bookRepository.book(bookId) }.getOrNull()
            ?: return null
        val coverPath = downloadRepository.coverFile(book.id)?.absolutePath
        val items = mediaItemFactory.create(book, coverPath)
        if (items.isEmpty()) return null
        val split = book.toPartPosition(positionMs)
        return ResumePoint(items, split.partIndex, split.offsetMs)
    }
}
