package dk.azp.jellybook.ui.library

import dk.azp.jellybook.data.model.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryArrangementTest {

    @Test
    fun titleSortReadsNumbersAsNumbers() {
        val entries = listOf(entry("Book 10"), entry("Book 2"), entry("Book 1"))

        val titles = arrange(entries, LibrarySort.TITLE, LibraryGrouping.NONE).single().entries.map { it.book.title }

        assertEquals(listOf("Book 1", "Book 2", "Book 10"), titles)
    }

    @Test
    fun releaseDateSortsBothWays() {
        val entries = listOf(
            entry("Middle", releaseDateEpochMs = 2_000),
            entry("Oldest", releaseDateEpochMs = 1_000),
            entry("Newest", releaseDateEpochMs = 3_000),
        )

        val newest = arrange(entries, LibrarySort.RELEASE_NEWEST, LibraryGrouping.NONE).single().entries
        val oldest = arrange(entries, LibrarySort.RELEASE_OLDEST, LibraryGrouping.NONE).single().entries

        assertEquals(listOf("Newest", "Middle", "Oldest"), newest.map { it.book.title })
        assertEquals(listOf("Oldest", "Middle", "Newest"), oldest.map { it.book.title })
    }

    @Test
    fun booksWithoutAReleaseDateSortLastEitherWay() {
        val entries = listOf(entry("Undated"), entry("Dated", releaseDateEpochMs = 1_000))

        val newest = arrange(entries, LibrarySort.RELEASE_NEWEST, LibraryGrouping.NONE).single().entries
        val oldest = arrange(entries, LibrarySort.RELEASE_OLDEST, LibraryGrouping.NONE).single().entries

        assertEquals(listOf("Dated", "Undated"), newest.map { it.book.title })
        assertEquals(listOf("Dated", "Undated"), oldest.map { it.book.title })
    }

    @Test
    fun groupingByAuthorMakesOneSectionPerAuthor() {
        val entries = listOf(
            entry("Second by Ada", author = "Ada Lovelace"),
            entry("Book by Zoe", author = "Zoe Quinn"),
            entry("First by Ada", author = "Ada Lovelace"),
        )

        val sections = arrange(entries, LibrarySort.TITLE, LibraryGrouping.AUTHOR)

        assertEquals(listOf("Ada Lovelace", "Zoe Quinn"), sections.map { it.title })
        assertEquals(listOf("First by Ada", "Second by Ada"), sections[0].entries.map { it.book.title })
        assertEquals(listOf("Book by Zoe"), sections[1].entries.map { it.book.title })
    }

    @Test
    fun booksWithNoAuthorAreGroupedLast() {
        val entries = listOf(entry("Anonymous", author = null), entry("Known", author = "Ada Lovelace"))

        val sections = arrange(entries, LibrarySort.TITLE, LibraryGrouping.AUTHOR)

        assertEquals(listOf("Ada Lovelace", UNKNOWN_AUTHOR), sections.map { it.title })
    }

    @Test
    fun blankAuthorCountsAsUnknown() {
        val sections = arrange(listOf(entry("Blank", author = "   ")), LibrarySort.TITLE, LibraryGrouping.AUTHOR)

        assertEquals(listOf(UNKNOWN_AUTHOR), sections.map { it.title })
    }

    @Test
    fun authorSortOrdersByAuthorThenTitle() {
        val entries = listOf(
            entry("B", author = "Ada Lovelace"),
            entry("A", author = "Zoe Quinn"),
            entry("A", author = "Ada Lovelace"),
        )

        val ordered = arrange(entries, LibrarySort.AUTHOR, LibraryGrouping.NONE).single().entries

        assertEquals(listOf("Ada Lovelace", "Ada Lovelace", "Zoe Quinn"), ordered.map { it.book.author })
        assertEquals(listOf("A", "B", "A"), ordered.map { it.book.title })
    }

    @Test
    fun recentlyPlayedPutsInProgressFirstAndFinishedLast() {
        val entries = listOf(
            entry("Finished", isFinished = true, lastPlayedEpochMs = 900),
            entry("Untouched"),
            entry("Listening", positionMs = 500, lastPlayedEpochMs = 100),
            entry("Listening later", positionMs = 500, lastPlayedEpochMs = 800),
        )

        val ordered = arrange(entries, LibrarySort.RECENT, LibraryGrouping.NONE).single().entries

        assertEquals(listOf("Listening later", "Listening", "Untouched", "Finished"), ordered.map { it.book.title })
    }

    @Test
    fun anEmptyLibraryHasNoSections() {
        assertEquals(emptyList<LibrarySection>(), arrange(emptyList(), LibrarySort.TITLE, LibraryGrouping.NONE))
        assertEquals(emptyList<LibrarySection>(), arrange(emptyList(), LibrarySort.TITLE, LibraryGrouping.AUTHOR))
    }

    @Test
    fun groupingKeepsTheChosenOrderInsideEachSection() {
        val entries = listOf(
            entry("Old one", author = "Ada Lovelace", releaseDateEpochMs = 1_000),
            entry("New one", author = "Ada Lovelace", releaseDateEpochMs = 5_000),
        )

        val sections = arrange(entries, LibrarySort.RELEASE_NEWEST, LibraryGrouping.AUTHOR)

        assertEquals(listOf("New one", "Old one"), sections.single().entries.map { it.book.title })
    }

    private fun entry(
        title: String,
        author: String? = "Ada Lovelace",
        releaseDateEpochMs: Long? = null,
        positionMs: Long = 0,
        isFinished: Boolean = false,
        lastPlayedEpochMs: Long? = null,
    ) = LibraryEntry(
        book = Book(
            id = "$title-$author",
            title = title,
            author = author,
            overview = null,
            imageItemId = title,
            imageTag = null,
            productionYear = null,
            releaseDateEpochMs = releaseDateEpochMs,
            parts = emptyList(),
            serverChapters = emptyList(),
            isOfflineCopy = false,
        ),
        positionMs = positionMs,
        progressFraction = 0f,
        isFinished = isFinished,
        lastPlayedEpochMs = lastPlayedEpochMs,
        download = null,
    )
}
