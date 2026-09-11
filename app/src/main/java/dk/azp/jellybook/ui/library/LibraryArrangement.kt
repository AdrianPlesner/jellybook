package dk.azp.jellybook.ui.library

import dk.azp.jellybook.data.NaturalOrder
import dk.azp.jellybook.data.downloads.DownloadInfo
import dk.azp.jellybook.data.model.Book

/** One row of the library: a book plus what the app knows about listening to it. */
data class LibraryEntry(
    val book: Book,
    val positionMs: Long,
    val progressFraction: Float,
    val isFinished: Boolean,
    val lastPlayedEpochMs: Long?,
    val download: DownloadInfo?,
)

/** A run of rows under an optional heading. A null heading means the list is not grouped. */
data class LibrarySection(val title: String?, val entries: List<LibraryEntry>)

enum class LibrarySort(val label: String) {
    RECENT("Recently played"),
    TITLE("Title"),
    AUTHOR("Author"),
    RELEASE_NEWEST("Release date, newest"),
    RELEASE_OLDEST("Release date, oldest"),
}

enum class LibraryGrouping(val label: String) {
    NONE("Nothing"),
    AUTHOR("Author"),
}

const val UNKNOWN_AUTHOR = "Unknown author"

/** Orders the library and, when asked, breaks it into sections. Pure, so the ordering can be tested on its own. */
fun arrange(entries: List<LibraryEntry>, sort: LibrarySort, grouping: LibraryGrouping): List<LibrarySection> {
    val ordered = entries.sortedWith(comparatorFor(sort))
    return when (grouping) {
        LibraryGrouping.NONE -> if (ordered.isEmpty()) emptyList() else listOf(LibrarySection(null, ordered))
        LibraryGrouping.AUTHOR -> ordered
            .groupBy(::authorOf)
            .toList()
            .sortedWith { left, right -> authorOrder.compare(left.first, right.first) }
            .map { (author, group) -> LibrarySection(author, group) }
    }
}

private fun authorOf(entry: LibraryEntry): String =
    entry.book.author?.takeIf { it.isNotBlank() } ?: UNKNOWN_AUTHOR

/** Unknown authors sit at the end rather than wherever the letter U falls. */
private val authorOrder = Comparator<String> { left, right ->
    when {
        left == right -> 0
        left == UNKNOWN_AUTHOR -> 1
        right == UNKNOWN_AUTHOR -> -1
        else -> NaturalOrder.compare(left, right)
    }
}

private fun comparatorFor(sort: LibrarySort): Comparator<LibraryEntry> = when (sort) {
    LibrarySort.RECENT -> recentFirst
    LibrarySort.TITLE -> byTitle
    LibrarySort.AUTHOR -> Comparator<LibraryEntry> { left, right ->
        authorOrder.compare(authorOf(left), authorOf(right))
    }.then(byTitle)
    LibrarySort.RELEASE_NEWEST -> compareByDescending<LibraryEntry> { it.book.releaseDateEpochMs ?: Long.MIN_VALUE }.then(byTitle)
    LibrarySort.RELEASE_OLDEST -> compareBy<LibraryEntry> { it.book.releaseDateEpochMs ?: Long.MAX_VALUE }.then(byTitle)
}

private val byTitle: Comparator<LibraryEntry> = Comparator { left, right ->
    NaturalOrder.compare(left.book.title, right.book.title)
}

/**
 * What is being listened to now, then what has not been started, then what is finished. Within each, most recent first.
 */
private val recentFirst: Comparator<LibraryEntry> = compareBy<LibraryEntry> { entry ->
    when {
        entry.isFinished -> 2
        entry.positionMs > 0 -> 0
        else -> 1
    }
}.thenByDescending { it.lastPlayedEpochMs ?: 0L }.then(byTitle)
