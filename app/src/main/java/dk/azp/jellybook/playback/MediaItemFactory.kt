package dk.azp.jellybook.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.local.SessionStore
import dk.azp.jellybook.data.model.PlaybackTarget
import java.io.File

/** Builds the MediaItem for a book; the item id doubles as the offline cache key so cached bytes are found offline. */
class MediaItemFactory(private val client: JellyfinClient, private val sessionStore: SessionStore) {

    suspend fun mediaUri(itemId: String): Uri? = sessionStore.currentSession()?.let { Uri.parse(client.fileUrl(it.serverUrl, itemId)) }

    suspend fun create(target: PlaybackTarget): MediaItem? {
        val session = sessionStore.currentSession() ?: return null
        val artworkUri = target.coverPath?.let { Uri.fromFile(File(it)) }
            ?: target.imageTag?.let { Uri.parse(client.primaryImageUrl(session.serverUrl, target.itemId, it, ARTWORK_HEIGHT_PX)) }
        val metadata = MediaMetadata.Builder()
            .setTitle(target.title)
            .setArtist(target.author)
            .setAlbumTitle(target.title)
            .setArtworkUri(artworkUri)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(target.toBundle())
            .build()
        return MediaItem.Builder()
            .setMediaId(target.itemId)
            .setUri(client.fileUrl(session.serverUrl, target.itemId))
            .setCustomCacheKey(target.itemId)
            .setMediaMetadata(metadata)
            .build()
    }

    private companion object {
        const val ARTWORK_HEIGHT_PX = 512
    }
}
