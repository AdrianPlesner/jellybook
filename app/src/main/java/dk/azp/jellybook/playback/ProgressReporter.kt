package dk.azp.jellybook.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dk.azp.jellybook.data.model.PlaybackTarget
import dk.azp.jellybook.data.progress.ProgressRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Translates player events into progress reports. Reports are per part, because that is what Jellyfin can store; the
 * repository puts them back on the book timeline. A part the player advances past on its own is reported as finished, so
 * the server knows how far into a multi-file book the listener is.
 */
class ProgressReporter(
    private val player: Player,
    private val repository: ProgressRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var current: PlaybackTarget? = null
    private var playSessionId: String = UUID.randomUUID().toString()
    private var started = false
    private var lastPositionMs = 0L
    private var ticker: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        finishCurrent(partCompleted = completed)
        current = mediaItem?.let { PlaybackTarget.fromMediaItem(it) }
        // Parts of one book share a play session so the server sees continuous listening rather than a new session per file.
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) playSessionId = UUID.randomUUID().toString()
        started = false
        lastPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (completed && player.isPlaying) {
            val target = current ?: return
            started = true
            val sessionId = playSessionId
            scope.launch { repository.onPlaybackStarted(target, sessionId, 0L) }
            startTicker(target, sessionId)
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
        lastPositionMs = if (sameItem) newPosition.positionMs else oldPosition.positionMs
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val target = current ?: return
        val sessionId = playSessionId
        lastPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (isPlaying) {
            if (!started) {
                started = true
                val position = lastPositionMs
                scope.launch { repository.onPlaybackStarted(target, sessionId, position) }
            }
            startTicker(target, sessionId)
        } else {
            ticker?.cancel()
            ticker = null
            if (started && player.playbackState != Player.STATE_ENDED) {
                val position = lastPositionMs
                scope.launch { repository.onPlaybackProgress(target, sessionId, position, paused = true) }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        finishCurrent(partCompleted = true)
    }

    fun release() = finishCurrent(partCompleted = false)

    private fun startTicker(target: PlaybackTarget, sessionId: String) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                lastPositionMs = player.currentPosition.coerceAtLeast(0L)
                repository.onPlaybackProgress(target, sessionId, lastPositionMs, paused = false)
            }
        }
    }

    private fun finishCurrent(partCompleted: Boolean) {
        ticker?.cancel()
        ticker = null
        val target = current ?: return
        if (!started) return
        started = false
        val sessionId = playSessionId
        val position = lastPositionMs
        scope.launch {
            if (partCompleted) {
                repository.onPartFinished(target, sessionId)
            } else {
                repository.onPlaybackStopped(target, sessionId, position)
            }
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 10_000L
    }
}
