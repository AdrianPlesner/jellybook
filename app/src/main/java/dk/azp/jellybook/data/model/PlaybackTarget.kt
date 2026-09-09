package dk.azp.jellybook.data.model

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * One playable part, carrying the little the player and the progress reporter need: which Jellyfin item to report against,
 * and where that part sits on the book timeline. Travels inside the MediaItem's metadata extras, so the playback service
 * can work without loading the book again.
 */
data class PlaybackTarget(
    val bookId: String,
    val partItemId: String,
    val partIndex: Int,
    val partStartOffsetMs: Long,
    val partDurationMs: Long,
    val partTitle: String,
    val bookTitle: String,
    val bookDurationMs: Long,
    val author: String?,
    val imageItemId: String,
    val imageTag: String?,
    val coverPath: String?,
) {
    val isLastPart: Boolean get() = partStartOffsetMs + partDurationMs >= bookDurationMs

    fun bookPositionMs(positionInPartMs: Long): Long = partStartOffsetMs + positionInPartMs.coerceAtLeast(0L)

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_BOOK_ID, bookId)
        putString(KEY_PART_ITEM_ID, partItemId)
        putInt(KEY_PART_INDEX, partIndex)
        putLong(KEY_PART_START, partStartOffsetMs)
        putLong(KEY_PART_DURATION, partDurationMs)
        putString(KEY_PART_TITLE, partTitle)
        putString(KEY_BOOK_TITLE, bookTitle)
        putLong(KEY_BOOK_DURATION, bookDurationMs)
        putString(KEY_AUTHOR, author)
        putString(KEY_IMAGE_ITEM_ID, imageItemId)
        putString(KEY_IMAGE_TAG, imageTag)
        putString(KEY_COVER_PATH, coverPath)
    }

    companion object {
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_PART_ITEM_ID = "part_item_id"
        private const val KEY_PART_INDEX = "part_index"
        private const val KEY_PART_START = "part_start_ms"
        private const val KEY_PART_DURATION = "part_duration_ms"
        private const val KEY_PART_TITLE = "part_title"
        private const val KEY_BOOK_TITLE = "book_title"
        private const val KEY_BOOK_DURATION = "book_duration_ms"
        private const val KEY_AUTHOR = "author"
        private const val KEY_IMAGE_ITEM_ID = "image_item_id"
        private const val KEY_IMAGE_TAG = "image_tag"
        private const val KEY_COVER_PATH = "cover_path"

        fun fromBundle(bundle: Bundle?): PlaybackTarget? {
            val bookId = bundle?.getString(KEY_BOOK_ID) ?: return null
            val partItemId = bundle.getString(KEY_PART_ITEM_ID) ?: return null
            return PlaybackTarget(
                bookId = bookId,
                partItemId = partItemId,
                partIndex = bundle.getInt(KEY_PART_INDEX),
                partStartOffsetMs = bundle.getLong(KEY_PART_START),
                partDurationMs = bundle.getLong(KEY_PART_DURATION),
                partTitle = bundle.getString(KEY_PART_TITLE) ?: "",
                bookTitle = bundle.getString(KEY_BOOK_TITLE) ?: "",
                bookDurationMs = bundle.getLong(KEY_BOOK_DURATION),
                author = bundle.getString(KEY_AUTHOR),
                imageItemId = bundle.getString(KEY_IMAGE_ITEM_ID) ?: bookId,
                imageTag = bundle.getString(KEY_IMAGE_TAG),
                coverPath = bundle.getString(KEY_COVER_PATH),
            )
        }

        fun fromMediaItem(mediaItem: MediaItem): PlaybackTarget? = fromBundle(mediaItem.mediaMetadata.extras)
    }
}

fun Book.playbackTargets(coverPath: String?): List<PlaybackTarget> = parts.map { part ->
    PlaybackTarget(
        bookId = id,
        partItemId = part.itemId,
        partIndex = part.index,
        partStartOffsetMs = part.startOffsetMs,
        partDurationMs = part.durationMs,
        partTitle = part.title,
        bookTitle = title,
        bookDurationMs = durationMs,
        author = author,
        imageItemId = imageItemId,
        imageTag = imageTag,
        coverPath = coverPath,
    )
}
