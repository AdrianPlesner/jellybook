package dk.azp.jellybook.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.local.SessionStore
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.data.model.PlaybackTarget
import dk.azp.jellybook.data.model.playbackTargets
import java.io.File

/**
 * Builds the player's playlist: one MediaItem per part. Each part's item id doubles as its offline cache key, so cached
 * bytes are found again without a server round trip.
 */
class MediaItemFactory(private val client: JellyfinClient, private val sessionStore: SessionStore) {

    suspend fun mediaUri(itemId: String): Uri? = sessionStore.currentSession()?.let { Uri.parse(client.fileUrl(it.serverUrl, itemId)) }

    suspend fun create(book: Book, coverPath: String?): List<MediaItem> =
        book.playbackTargets(coverPath).mapNotNull { create(it) }

    suspend fun create(target: PlaybackTarget): MediaItem? {
        val session = sessionStore.currentSession() ?: return null
        val artworkUri = target.coverPath?.let { Uri.fromFile(File(it)) }
            ?: target.imageTag?.let { Uri.parse(client.primaryImageUrl(session.serverUrl, target.imageItemId, it, ARTWORK_HEIGHT_PX)) }
        val metadata = MediaMetadata.Builder()
            .setTitle(target.bookTitle)
            .setSubtitle(target.partTitle.takeIf { it.isNotBlank() && it != target.bookTitle })
            .setArtist(target.author)
            .setAlbumTitle(target.bookTitle)
            .setArtworkUri(artworkUri)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(target.toBundle())
            .build()
        return MediaItem.Builder()
            .setMediaId(target.partItemId)
            .setUri(client.fileUrl(session.serverUrl, target.partItemId))
            .setCustomCacheKey(target.partItemId)
            .setMediaMetadata(metadata)
            .build()
    }

    private companion object {
        const val ARTWORK_HEIGHT_PX = 512
    }
}
