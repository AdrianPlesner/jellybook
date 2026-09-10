package dk.azp.jellybook.data

import android.util.Log
import dk.azp.jellybook.data.jellyfin.BaseItemDto
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.local.ServerSession
import dk.azp.jellybook.data.local.SessionStore
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.data.model.multiPartBook
import dk.azp.jellybook.data.model.toSingleFileBook
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Turns Jellyfin's item tree into books.
 *
 * Jellyfin has no notion of a multi-file audiobook: a folder of mp3s becomes one `AudioBook` item per file, sharing the
 * folder as their parent. Grouping the flat recursive listing by parent id would be wrong, because Jellyfin parents a
 * single-file book directly to the library root, which would then merge every such book into one. So the library is walked
 * top down instead.
 *
 * A folder of audio files is still ambiguous: it can be one book split into files, or several complete books side by side.
 * [BookGrouping] decides that from the files' metadata; a folder holding only folders is a level of organisation to
 * descend into.
 */
class BookRepository(
    private val client: JellyfinClient,
    private val sessionStore: SessionStore,
) {

    suspend fun library(): List<Book> {
        val session = sessionStore.currentSession() ?: throw IOException("Not signed in")
        val views = client.userViews(session.serverUrl, session.userId)
        val bookViews = views.filter { it.collectionType == COLLECTION_TYPE_BOOKS }.ifEmpty { views }
        return coroutineScope {
            bookViews.map { view -> async { booksUnder(session, view.id, depth = 0) } }.awaitAll().flatten()
        }.sortedBy { it.title.lowercase() }
    }

    /** Loads one book by the id the library handed out: an audio item for a single-file book, a folder for a multi-part one. */
    suspend fun book(bookId: String): Book {
        val session = sessionStore.currentSession() ?: throw IOException("Not signed in")
        val item = client.item(session.serverUrl, session.userId, bookId)
        if (!item.isFolder) return item.toSingleFileBook()
        val parts = audioParts(session, item.id)
        return when {
            parts.isEmpty() -> throw IOException("No audio files in ${item.displayTitle}")
            BookGrouping.looksLikeOneBook(parts) -> multiPartBook(item, parts)
            // The folder holds several complete books, so it is not a book itself.
            else -> throw IOException("${item.displayTitle} holds several books")
        }
    }

    private suspend fun booksUnder(session: ServerSession, parentId: String, depth: Int): List<Book> = coroutineScope {
        val children = client.children(session.serverUrl, session.userId, parentId)
        val singles = children.filter { it.isAudioItem }.map { it.toSingleFileBook() }
        val folders = children.filter { it.isFolder && it.childCount != 0 }
        val nested = folders.map { folder -> async { resolveFolder(session, folder, depth) } }.awaitAll().flatten()
        singles + nested
    }

    private suspend fun resolveFolder(session: ServerSession, folder: BaseItemDto, depth: Int): List<Book> {
        val audio = audioParts(session, folder.id)
        return when {
            audio.isEmpty() -> if (depth >= MAX_DEPTH) emptyList() else booksUnder(session, folder.id, depth + 1)
            BookGrouping.looksLikeOneBook(audio) -> listOf(multiPartBook(folder, audio))
            // A folder of complete books: each file is its own book, and any subfolders still hold books of their own.
            else -> audio.map { it.toSingleFileBook() } +
                if (depth >= MAX_DEPTH) emptyList() else nestedBooks(session, folder.id, depth + 1)
        }
    }

    private suspend fun nestedBooks(session: ServerSession, parentId: String, depth: Int): List<Book> = coroutineScope {
        val folders = client.children(session.serverUrl, session.userId, parentId).filter { it.isFolder && it.childCount != 0 }
        folders.map { folder -> async { resolveFolder(session, folder, depth) } }.awaitAll().flatten()
    }

    private suspend fun audioParts(session: ServerSession, folderId: String): List<BaseItemDto> {
        val audio = client.children(session.serverUrl, session.userId, folderId).filter { it.isAudioItem }
        val ordered = audio.sortedWith(partOrder)
        if (ordered.size > 1 && !ordersItself(ordered)) {
            Log.w(TAG, "Nothing orders the ${ordered.size} files in this folder: no track numbers, no distinct filenames")
        }
        return ordered
    }

    /** Whether the files carry anything the play order can be read from: disc or track tags, or distinct filenames. */
    private fun ordersItself(items: List<BaseItemDto>): Boolean {
        val tagged = items.all { it.indexNumber != null } && items.mapNotNull { it.indexNumber }.distinct().size == items.size
        val named = items.map { it.fileName }.filter { it.isNotBlank() }.distinct().size == items.size
        return tagged || named
    }

    private companion object {
        const val COLLECTION_TYPE_BOOKS = "books"
        const val MAX_DEPTH = 3

        const val TAG = "BookRepository"

        /**
         * Disc and track tags first, since those state the intent. Then the filename, read the way a person reads numbered
         * files, because that is the only thing left when a rip tags every file identically. The item name comes last and
         * is usually a tie by then.
         */
        val partOrder = compareBy<BaseItemDto>({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: Int.MAX_VALUE })
            .thenComparing({ it.fileName }, NaturalOrder)
            .thenComparing({ it.displayTitle }, NaturalOrder)
    }
}
