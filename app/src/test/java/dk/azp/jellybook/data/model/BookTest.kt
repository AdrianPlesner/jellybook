package dk.azp.jellybook.data.model

import dk.azp.jellybook.data.jellyfin.BaseItemDto
import dk.azp.jellybook.data.jellyfin.ChapterInfo
import dk.azp.jellybook.data.jellyfin.UserItemDataDto
import dk.azp.jellybook.data.jellyfin.msToTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTest {

    @Test
    fun partsAreLaidEndToEnd() {
        val parts = listOf(item("a", 150_000), item("b", 180_000), item("c", 90_000)).toParts()

        assertEquals(listOf(0L, 150_000L, 330_000L), parts.map { it.startOffsetMs })
        assertEquals(listOf(150_000L, 330_000L, 420_000L), parts.map { it.endOffsetMs })
        assertEquals(listOf(0, 1, 2), parts.map { it.index })
    }

    @Test
    fun bookPositionSplitsIntoThePartHoldingIt() {
        val book = multiPart(150_000, 180_000, 90_000)

        assertEquals(PartPosition(0, 0), book.toPartPosition(0))
        assertEquals(PartPosition(0, 149_999), book.toPartPosition(149_999))
        assertEquals(PartPosition(1, 0), book.toPartPosition(150_000))
        assertEquals(PartPosition(1, 30_000), book.toPartPosition(180_000))
        assertEquals(PartPosition(2, 10_000), book.toPartPosition(340_000))
        assertEquals(420_000L, book.durationMs)
    }

    @Test
    fun bookPositionIsClampedToTheBook() {
        val book = multiPart(150_000, 180_000)

        assertEquals(PartPosition(0, 0), book.toPartPosition(-5_000))
        assertEquals(PartPosition(1, 180_000), book.toPartPosition(999_999))
    }

    @Test
    fun partPositionRoundTripsToBookPosition() {
        val book = multiPart(150_000, 180_000, 90_000)

        listOf(0L, 1L, 149_999L, 150_000L, 275_000L, 419_999L).forEach { position ->
            val split = book.toPartPosition(position)
            assertEquals(position, book.toBookPosition(split.partIndex, split.offsetMs))
        }
    }

    @Test
    fun remoteProgressUsesThePartLeftMidListen() {
        val book = multiPart(150_000, 180_000, 90_000).withRemote(
            played(0),
            RemoteProgress(45_000, played = false, lastPlayedEpochMs = 5_000),
            null,
        )

        val progress = book.remoteProgress()

        assertEquals(195_000L, progress?.positionMs)
        assertEquals(false, progress?.played)
        assertEquals(5_000L, progress?.lastPlayedEpochMs)
    }

    @Test
    fun remoteProgressPrefersTheMostRecentlyPlayedPartOverTheFurthestOne() {
        val book = multiPart(150_000, 180_000, 90_000).withRemote(
            RemoteProgress(20_000, played = false, lastPlayedEpochMs = 9_000),
            RemoteProgress(45_000, played = false, lastPlayedEpochMs = 1_000),
            null,
        )

        assertEquals(20_000L, book.remoteProgress()?.positionMs)
    }

    @Test
    fun remoteProgressFallsBackToTheFirstUnplayedPart() {
        val book = multiPart(150_000, 180_000, 90_000).withRemote(played(1_000), played(2_000), unplayed())

        assertEquals(330_000L, book.remoteProgress()?.positionMs)
        assertEquals(false, book.remoteProgress()?.played)
    }

    @Test
    fun remoteProgressReportsTheEndWhenEveryPartIsPlayed() {
        val book = multiPart(150_000, 180_000).withRemote(played(1_000), played(2_000))

        val progress = book.remoteProgress()

        assertEquals(330_000L, progress?.positionMs)
        assertTrue(progress!!.played)
    }

    @Test
    fun remoteProgressIsUnknownWithoutServerUserData() {
        assertNull(multiPart(150_000, 180_000).remoteProgress())
    }

    @Test
    fun multiPartChaptersAreOnePerFileWhenFilesHaveNoMarkers() {
        val items = listOf(item("a", 150_000, name = "01 - Arrival"), item("b", 180_000, name = "02 - The Harbour"))

        val chapters = chaptersOf(items.toParts(), items, multiPart = true)

        assertEquals(listOf("01 - Arrival", "02 - The Harbour"), chapters.map { it.title })
        assertEquals(listOf(0L, 150_000L), chapters.map { it.startMs })
        assertEquals(listOf(150_000L, 330_000L), chapters.map { it.endMs })
    }

    @Test
    fun markersInsideAPartAreShiftedOntoTheBookTimeline() {
        val items = listOf(
            item("a", 150_000, name = "Part one", chapters = listOf(0L to "Opening", 60_000L to "Storm")),
            item("b", 180_000, name = "Part two"),
        )

        val chapters = chaptersOf(items.toParts(), items, multiPart = true)

        assertEquals(listOf("Opening", "Storm", "Part two"), chapters.map { it.title })
        assertEquals(listOf(0L, 60_000L, 150_000L), chapters.map { it.startMs })
        assertEquals(listOf(60_000L, 150_000L, 330_000L), chapters.map { it.endMs })
    }

    @Test
    fun singleFileBookKeepsItsOwnMarkersAndNoSyntheticChapter() {
        val single = item("a", 900_000, name = "The Test Book", chapters = listOf(0L to "One", 180_000L to "Two"))

        val book = single.toSingleFileBook()

        assertEquals(1, book.parts.size)
        assertEquals(false, book.isMultiPart)
        assertEquals(listOf("One", "Two"), book.serverChapters.map { it.title })
    }

    @Test
    fun singleFileBookWithoutMarkersHasNoChapters() {
        assertTrue(item("a", 900_000).toSingleFileBook().serverChapters.isEmpty())
    }

    @Test
    fun partsWhoseFilesAllCarryTheSameNameGetPositionalTitles() {
        val items = (1..4).map { item("p$it", 120_000, name = "The Long Journey (Unabridged)") }

        val parts = items.toParts()

        assertEquals(listOf("Part 1", "Part 2", "Part 3", "Part 4"), parts.map { it.title })
    }

    @Test
    fun partsKeepTheirOwnNamesWhenTheyDiffer() {
        val items = listOf(item("a", 120_000, name = "01 - Arrival"), item("b", 120_000, name = "02 - The Harbour"))

        assertEquals(listOf("01 - Arrival", "02 - The Harbour"), items.toParts().map { it.title })
    }

    @Test
    fun chaptersOfIdenticallyNamedPartsAreNumbered() {
        val items = (1..3).map { item("p$it", 120_000, name = "The Long Journey (Unabridged)") }
        val parts = items.toParts()

        val chapters = chaptersOf(parts, items, multiPart = true)

        assertEquals(listOf("Part 1", "Part 2", "Part 3"), chapters.map { it.title })
    }

    @Test
    fun folderTitleWinsOverFileTitlesForAMultiPartBook() {
        val folder = BaseItemDto(id = "folder", name = "The Long Journey", type = BaseItemDto.TYPE_FOLDER, isFolder = true)
        val items = listOf(item("a", 150_000, name = "01 - Arrival"), item("b", 180_000, name = "02 - The Harbour"))

        val book = multiPartBook(folder, items)

        assertEquals("The Long Journey", book.title)
        assertEquals("folder", book.id)
        assertEquals(330_000L, book.durationMs)
        assertEquals("a", book.imageItemId)
    }

    private fun multiPart(vararg durations: Long): Book {
        val items = durations.mapIndexed { index, duration -> item("p$index", duration, name = "Part ${index + 1}") }
        return multiPartBook(BaseItemDto(id = "folder", name = "Book", type = BaseItemDto.TYPE_FOLDER, isFolder = true), items)
    }

    private fun Book.withRemote(vararg progress: RemoteProgress?): Book =
        copy(parts = parts.mapIndexed { index, part -> part.copy(remoteProgress = progress.getOrNull(index)) })

    private fun played(at: Long) = RemoteProgress(0, played = true, lastPlayedEpochMs = at)

    private fun unplayed() = RemoteProgress(0, played = false, lastPlayedEpochMs = null)

    private fun item(
        id: String,
        durationMs: Long,
        name: String = id,
        chapters: List<Pair<Long, String>> = emptyList(),
        userData: UserItemDataDto? = null,
    ) = BaseItemDto(
        id = id,
        name = name,
        type = BaseItemDto.TYPE_AUDIO_BOOK,
        runTimeTicks = durationMs.msToTicks(),
        chapters = chapters.map { (start, title) -> ChapterInfo(start.msToTicks(), title) },
        userData = userData,
    )
}
