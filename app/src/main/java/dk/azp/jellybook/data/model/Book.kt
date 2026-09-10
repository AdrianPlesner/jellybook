package dk.azp.jellybook.data.model

import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.data.jellyfin.BaseItemDto
import dk.azp.jellybook.data.jellyfin.UserItemDataDto
import dk.azp.jellybook.data.jellyfin.ticksToMs
import dk.azp.jellybook.data.local.DownloadedBook
import dk.azp.jellybook.data.local.DownloadedPart
import java.time.Instant

data class RemoteProgress(
    val positionMs: Long,
    val played: Boolean,
    val lastPlayedEpochMs: Long?,
) {
    /** Jellyfin resets the position to zero once it marks an item played; treat that as "at the end". */
    fun effectivePositionMs(durationMs: Long): Long = if (played && positionMs == 0L) durationMs else positionMs
}

/**
 * One audio file of a book. A single-file book has exactly one part; a folder of mp3s has one per file, laid end to end on
 * a single timeline so the rest of the app can work in book-relative milliseconds.
 */
data class BookPart(
    val itemId: String,
    val title: String,
    val index: Int,
    val startOffsetMs: Long,
    val durationMs: Long,
    val mediaSourceId: String?,
    val container: String?,
    val imageTag: String?,
    val remoteProgress: RemoteProgress?,
) {
    val endOffsetMs: Long get() = startOffsetMs + durationMs
}

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val overview: String?,
    val imageItemId: String,
    val imageTag: String?,
    val productionYear: Int?,
    val parts: List<BookPart>,
    /** Chapter markers Jellyfin extracted from the files, already mapped onto the book timeline. */
    val serverChapters: List<Chapter>,
    val isOfflineCopy: Boolean,
) {
    val durationMs: Long = parts.sumOf { it.durationMs }

    val isMultiPart: Boolean get() = parts.size > 1

    val container: String? get() = parts.firstOrNull()?.container

    /** The part covering a book-relative position, clamped to the ends. */
    fun partAt(positionMs: Long): BookPart =
        parts.lastOrNull { it.startOffsetMs <= positionMs } ?: parts.first()

    /** Splits a book-relative position into the part that holds it and the offset inside that part. */
    fun toPartPosition(positionMs: Long): PartPosition {
        val clamped = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val part = partAt(clamped)
        val offset = (clamped - part.startOffsetMs).coerceIn(0L, part.durationMs.coerceAtLeast(0L))
        return PartPosition(part.index, offset)
    }

    fun toBookPosition(partIndex: Int, offsetInPartMs: Long): Long {
        val part = parts.getOrNull(partIndex) ?: return offsetInPartMs
        return part.startOffsetMs + offsetInPartMs.coerceAtLeast(0L)
    }

    /**
     * The book-relative position the server holds, derived from the parts' own user data: the part that was left
     * mid-listen, else the first part not yet played, else the end when every part is played.
     */
    fun remoteProgress(): RemoteProgress? {
        val known = parts.filter { it.remoteProgress != null }
        if (known.isEmpty()) return null
        val lastPlayed = known.mapNotNull { it.remoteProgress?.lastPlayedEpochMs }.maxOrNull()
        // Most recently touched, not the furthest in: seeking back to an earlier part must win over one left in progress.
        val inProgress = known
            .filter { part ->
                val progress = part.remoteProgress
                progress != null && !progress.played && progress.positionMs > 0
            }
            .maxWithOrNull(compareBy({ it.remoteProgress?.lastPlayedEpochMs ?: 0L }, { it.index }))
        if (inProgress != null) {
            val position = inProgress.startOffsetMs + (inProgress.remoteProgress?.positionMs ?: 0L)
            return RemoteProgress(position, played = false, lastPlayedEpochMs = lastPlayed)
        }
        val firstUnplayed = known.firstOrNull { it.remoteProgress?.played == false }
        return if (firstUnplayed == null) {
            RemoteProgress(durationMs, played = true, lastPlayedEpochMs = lastPlayed)
        } else {
            RemoteProgress(firstUnplayed.startOffsetMs, played = false, lastPlayedEpochMs = lastPlayed)
        }
    }
}

data class PartPosition(val partIndex: Int, val offsetMs: Long)

fun UserItemDataDto.toRemoteProgress(): RemoteProgress = RemoteProgress(
    positionMs = playbackPositionTicks.ticksToMs(),
    played = played,
    lastPlayedEpochMs = lastPlayedDate?.let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() },
)

/**
 * Positional labels for parts whose own titles say nothing. A rip that tags every file with the book's name gives every
 * part the same title, which is useless as a chapter list.
 */
private fun List<BookPart>.withUsefulTitles(): List<BookPart> {
    val distinct = map { it.title.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    return if (distinct.size > 1) this else mapIndexed { index, part -> part.copy(title = "Part ${index + 1}") }
}

/** Lays audio items end to end, taking each part's title from the file and its length from the server. */
fun List<BaseItemDto>.toParts(): List<BookPart> {
    var offset = 0L
    return mapIndexed { index, item ->
        val duration = item.runTimeMs
        BookPart(
            itemId = item.id,
            title = item.displayTitle,
            index = index,
            startOffsetMs = offset,
            durationMs = duration,
            mediaSourceId = item.mediaSources.firstOrNull()?.id,
            container = item.container ?: item.mediaSources.firstOrNull()?.container,
            imageTag = item.primaryImageTag,
            remoteProgress = item.userData?.toRemoteProgress(),
        ).also { offset += duration }
    }.withUsefulTitles()
}

/** Chapter markers of the parts, shifted onto the book timeline. A part without markers contributes itself. */
fun chaptersOf(parts: List<BookPart>, items: List<BaseItemDto>, multiPart: Boolean): List<Chapter> {
    val byId = items.associateBy { it.id }
    val starts = mutableListOf<Pair<Long, String>>()
    for (part in parts) {
        val markers = byId[part.itemId]?.chapters.orEmpty().map { it.startPositionTicks.ticksToMs() to (it.name ?: "") }
        if (markers.size >= MIN_MARKERS_PER_PART) {
            markers.sortedBy { it.first }.forEach { (start, name) -> starts += (part.startOffsetMs + start) to name }
        } else if (multiPart) {
            starts += part.startOffsetMs to part.title
        }
    }
    val total = parts.sumOf { it.durationMs }
    val sorted = starts.sortedBy { it.first }
    return sorted.mapIndexed { index, (start, name) ->
        val end = sorted.getOrNull(index + 1)?.first ?: maxOf(total, start)
        Chapter(index, name.ifBlank { "Chapter ${index + 1}" }, start, end)
    }
}

private const val MIN_MARKERS_PER_PART = 2

/** A single audio item that is a whole book by itself. */
fun BaseItemDto.toSingleFileBook(): Book {
    val parts = listOf(this).toParts()
    return Book(
        id = id,
        title = displayTitle,
        author = displayAuthor,
        overview = overview,
        imageItemId = id,
        imageTag = primaryImageTag,
        productionYear = productionYear,
        parts = parts,
        serverChapters = chaptersOf(parts, listOf(this), multiPart = false),
        isOfflineCopy = false,
    )
}

/** A folder of audio items that together form one book. */
fun multiPartBook(folder: BaseItemDto, items: List<BaseItemDto>): Book {
    val parts = items.toParts()
    val first = items.firstOrNull()
    return Book(
        id = folder.id,
        title = folder.name?.takeIf { it.isNotBlank() } ?: first?.album ?: "Untitled",
        author = first?.displayAuthor,
        overview = folder.overview ?: first?.overview,
        imageItemId = if (folder.primaryImageTag != null) folder.id else first?.id ?: folder.id,
        imageTag = folder.primaryImageTag ?: first?.primaryImageTag,
        productionYear = folder.productionYear ?: first?.productionYear,
        parts = parts,
        serverChapters = chaptersOf(parts, items, multiPart = parts.size > 1),
        isOfflineCopy = false,
    )
}

fun DownloadedBook.toBook(): Book {
    val parts = partList.map { part ->
        BookPart(
            itemId = part.itemId,
            title = part.title,
            index = part.index,
            startOffsetMs = part.startOffsetMs,
            durationMs = part.durationMs,
            mediaSourceId = part.mediaSourceId,
            container = part.container,
            imageTag = null,
            remoteProgress = null,
        )
    }
    return Book(
        id = itemId,
        title = title,
        author = author,
        overview = overview,
        imageItemId = imageItemId ?: itemId,
        imageTag = imageTag,
        productionYear = productionYear,
        parts = parts,
        serverChapters = chapters,
        isOfflineCopy = true,
    )
}

fun BookPart.toDownloadedPart(): DownloadedPart = DownloadedPart(
    itemId = itemId,
    title = title,
    index = index,
    startOffsetMs = startOffsetMs,
    durationMs = durationMs,
    mediaSourceId = mediaSourceId,
    container = container,
)
