package dk.azp.jellybook.data

import dk.azp.jellybook.data.jellyfin.BaseItemDto

/**
 * Decides whether the audio files in one folder are a single book split across files, or several complete books that
 * happen to share a folder.
 *
 * Jellyfin cannot answer this: it makes one item per file either way. So the files' own metadata has to. Three signals
 * separate the two layouts in practice:
 *
 *  - **Album.** Ripped parts of one book carry the book's title on every file; complete books each carry their own.
 *  - **Chapter markers.** A file with its own chapters is a whole book; a file that *is* a chapter has none.
 *  - **Track numbers.** Parts of one book are numbered in sequence.
 *
 * Where the signals disagree, album and numbering win over chapter markers, because a long book is sometimes split into
 * several files that each keep their own chapters.
 */
internal object BookGrouping {

    /** A file this long is unlikely to be one chapter of something else. */
    private const val WHOLE_BOOK_MS = 70 * 60_000L

    private const val MIN_OWN_CHAPTERS = 2

    fun looksLikeOneBook(items: List<BaseItemDto>): Boolean {
        if (items.size < 2) return false
        val albums = items.mapNotNull { it.album?.takeIf(String::isNotBlank) }.distinct()
        val sharesOneAlbum = albums.size == 1 && items.all { !it.album.isNullOrBlank() }
        val numbers = items.mapNotNull { it.indexNumber }
        val numberedInSequence = numbers.size == items.size && numbers.distinct().size == items.size
        val everyFileHasOwnChapters = items.all { it.chapters.size >= MIN_OWN_CHAPTERS }
        return when {
            albums.size > 1 -> false
            everyFileHasOwnChapters && !numberedInSequence -> false
            sharesOneAlbum || numberedInSequence -> true
            everyFileHasOwnChapters -> false
            items.all { it.runTimeMs >= WHOLE_BOOK_MS } -> false
            else -> true
        }
    }
}
