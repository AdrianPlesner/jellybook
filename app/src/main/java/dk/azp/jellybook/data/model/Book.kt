package dk.azp.jellybook.data.model

import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.data.jellyfin.BaseItemDto
import dk.azp.jellybook.data.jellyfin.UserItemDataDto
import dk.azp.jellybook.data.jellyfin.ticksToMs
import dk.azp.jellybook.data.local.DownloadedBook
import java.time.Instant

data class RemoteProgress(
    val positionMs: Long,
    val played: Boolean,
    val lastPlayedEpochMs: Long?,
) {
    /** Jellyfin resets the position to zero once it marks a book played; treat that as "at the end". */
    fun effectivePositionMs(durationMs: Long): Long = if (played && positionMs == 0L) durationMs else positionMs
}

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val overview: String?,
    val durationMs: Long,
    val imageTag: String?,
    val mediaSourceId: String?,
    val container: String?,
    val productionYear: Int?,
    val remoteProgress: RemoteProgress?,
    val serverChapters: List<Chapter>,
    val isOfflineCopy: Boolean,
)

fun UserItemDataDto.toRemoteProgress(): RemoteProgress = RemoteProgress(
    positionMs = playbackPositionTicks.ticksToMs(),
    played = played,
    lastPlayedEpochMs = lastPlayedDate?.let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() },
)

fun BaseItemDto.toBook(): Book {
    val duration = runTimeMs
    val chapterStarts = chapters.map { it.startPositionTicks.ticksToMs() to (it.name ?: "") }.sortedBy { it.first }
    val serverChapters = chapterStarts.mapIndexed { index, (start, name) ->
        val end = chapterStarts.getOrNull(index + 1)?.first ?: maxOf(duration, start)
        Chapter(index, name.ifBlank { "Chapter ${index + 1}" }, start, end)
    }
    return Book(
        id = id,
        title = displayTitle,
        author = displayAuthor,
        overview = overview,
        durationMs = duration,
        imageTag = primaryImageTag,
        mediaSourceId = mediaSources.firstOrNull()?.id,
        container = container ?: mediaSources.firstOrNull()?.container,
        productionYear = productionYear,
        remoteProgress = userData?.toRemoteProgress(),
        serverChapters = serverChapters,
        isOfflineCopy = false,
    )
}

fun DownloadedBook.toBook(): Book = Book(
    id = itemId,
    title = title,
    author = author,
    overview = overview,
    durationMs = durationMs,
    imageTag = imageTag,
    mediaSourceId = mediaSourceId,
    container = container,
    productionYear = productionYear,
    remoteProgress = null,
    serverChapters = chapters,
    isOfflineCopy = true,
)
