package dk.azp.jellybook.data.model

import android.os.Bundle
import androidx.media3.common.MediaItem

/** The subset of a book that playback and progress reporting need; small enough to travel inside a MediaItem's extras. */
data class PlaybackTarget(
    val itemId: String,
    val title: String,
    val author: String?,
    val imageTag: String?,
    val mediaSourceId: String?,
    val durationMs: Long,
    val coverPath: String?,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_ITEM_ID, itemId)
        putString(KEY_TITLE, title)
        putString(KEY_AUTHOR, author)
        putString(KEY_IMAGE_TAG, imageTag)
        putString(KEY_MEDIA_SOURCE_ID, mediaSourceId)
        putLong(KEY_DURATION_MS, durationMs)
        putString(KEY_COVER_PATH, coverPath)
    }

    companion object {
        private const val KEY_ITEM_ID = "item_id"
        private const val KEY_TITLE = "title"
        private const val KEY_AUTHOR = "author"
        private const val KEY_IMAGE_TAG = "image_tag"
        private const val KEY_MEDIA_SOURCE_ID = "media_source_id"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_COVER_PATH = "cover_path"

        fun fromBundle(bundle: Bundle?): PlaybackTarget? {
            val itemId = bundle?.getString(KEY_ITEM_ID) ?: return null
            return PlaybackTarget(
                itemId = itemId,
                title = bundle.getString(KEY_TITLE) ?: "",
                author = bundle.getString(KEY_AUTHOR),
                imageTag = bundle.getString(KEY_IMAGE_TAG),
                mediaSourceId = bundle.getString(KEY_MEDIA_SOURCE_ID),
                durationMs = bundle.getLong(KEY_DURATION_MS),
                coverPath = bundle.getString(KEY_COVER_PATH),
            )
        }

        fun fromMediaItem(mediaItem: MediaItem): PlaybackTarget? = fromBundle(mediaItem.mediaMetadata.extras)
    }
}

fun Book.toPlaybackTarget(coverPath: String?): PlaybackTarget = PlaybackTarget(
    itemId = id,
    title = title,
    author = author,
    imageTag = imageTag,
    mediaSourceId = mediaSourceId,
    durationMs = durationMs,
    coverPath = coverPath,
)
