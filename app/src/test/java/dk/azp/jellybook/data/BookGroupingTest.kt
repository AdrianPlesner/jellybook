package dk.azp.jellybook.data

import dk.azp.jellybook.data.jellyfin.BaseItemDto
import dk.azp.jellybook.data.jellyfin.ChapterInfo
import dk.azp.jellybook.data.jellyfin.msToTicks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGroupingTest {

    @Test
    fun severalCompleteBooksInOneFolderStaySeparate() {
        val folder = listOf(
            file("The Quiet Coast", album = "The Quiet Coast", chapters = 3, durationMs = hours(6)),
            file("Winter Lanterns", album = "Winter Lanterns", chapters = 3, durationMs = hours(9)),
        )

        assertFalse(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun aChapterPerFileRipIsOneBook() {
        val folder = (1..5).map { index ->
            file("0$index - Chapter $index", album = "The Long Journey", index = index, durationMs = minutes(20))
        }

        assertTrue(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun untaggedFilesWithoutChaptersAreOneBook() {
        val folder = (1..8).map { index -> file("track$index", durationMs = minutes(15)) }

        assertTrue(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun untaggedWholeBooksAreSeparatedByTheirOwnChapters() {
        val folder = listOf(
            file("first", chapters = 12, durationMs = hours(7)),
            file("second", chapters = 9, durationMs = hours(5)),
        )

        assertFalse(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun aBookSplitIntoNumberedPartsThatKeepTheirOwnChaptersIsOneBook() {
        val folder = (1..3).map { index ->
            file("Part $index", album = "The Long Crossing", index = index, chapters = 6, durationMs = hours(4))
        }

        assertTrue(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun booksSharingASeriesAlbumTagAreStillSeparatedWhenEachHasItsOwnChapters() {
        val folder = listOf(
            file("Tower of Glass", album = "The Ashen Cycle", chapters = 20, durationMs = hours(10)),
            file("Salt and Cinder", album = "The Ashen Cycle", chapters = 18, durationMs = hours(11)),
        )

        assertFalse(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun longUntaggedFilesWithoutChaptersAreTreatedAsWholeBooks() {
        val folder = listOf(file("one", durationMs = hours(8)), file("two", durationMs = hours(6)))

        assertFalse(BookGrouping.looksLikeOneBook(folder))
    }

    @Test
    fun aSingleFileIsNeverAMultiPartBook() {
        assertFalse(BookGrouping.looksLikeOneBook(listOf(file("only", album = "Book", chapters = 5))))
        assertFalse(BookGrouping.looksLikeOneBook(emptyList()))
    }

    @Test
    fun numberedPartsWinOverDifferingAlbumsOnlyWhenAlbumsAgree() {
        val differingAlbums = listOf(
            file("01 - one", album = "Book A", index = 1),
            file("02 - two", album = "Book B", index = 2),
        )

        assertFalse(BookGrouping.looksLikeOneBook(differingAlbums))
    }

    private fun minutes(count: Long) = count * 60_000L

    private fun hours(count: Long) = count * 60 * 60_000L

    private fun file(
        name: String,
        album: String? = null,
        index: Int? = null,
        chapters: Int = 0,
        durationMs: Long = 600_000L,
    ) = BaseItemDto(
        id = name,
        name = name,
        type = BaseItemDto.TYPE_AUDIO_BOOK,
        album = album,
        indexNumber = index,
        runTimeTicks = durationMs.msToTicks(),
        chapters = (0 until chapters).map { ChapterInfo((it * 60_000L).msToTicks(), "Chapter ${it + 1}") },
    )
}
