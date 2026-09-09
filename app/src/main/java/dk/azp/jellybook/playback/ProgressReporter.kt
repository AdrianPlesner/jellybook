package dk.azp.jellybook.playback

import androidx.media3.common.C
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

/** Translates player events into progress reports: start, a heartbeat while playing, pause, stop and finished. */
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
        stopCurrent()
        current = mediaItem?.let { PlaybackTarget.fromMediaItem(it) }
        playSessionId = UUID.randomUUID().toString()
        started = false
        lastPositionMs = player.currentPosition.coerceAtLeast(0L)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val sameItem = oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId
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
                scope.launch { repository.onPlaybackStarted(withDuration(target), sessionId, position) }
            }
            startTicker(target, sessionId)
        } else {
            ticker?.cancel()
            ticker = null
            if (started && player.playbackState != Player.STATE_ENDED) {
                val position = lastPositionMs
                scope.launch { repository.onPlaybackProgress(withDuration(target), sessionId, position, paused = true) }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        val target = current ?: return
        ticker?.cancel()
        ticker = null
        val sessionId = playSessionId
        started = false
        scope.launch { repository.onPlaybackFinished(withDuration(target), sessionId) }
    }

    fun release() = stopCurrent()

    private fun startTicker(target: PlaybackTarget, sessionId: String) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                lastPositionMs = player.currentPosition.coerceAtLeast(0L)
                repository.onPlaybackProgress(withDuration(target), sessionId, lastPositionMs, paused = false)
            }
        }
    }

    private fun stopCurrent() {
        ticker?.cancel()
        ticker = null
        val target = current ?: return
        if (!started) return
        started = false
        val sessionId = playSessionId
        val position = lastPositionMs
        scope.launch { repository.onPlaybackStopped(withDuration(target), sessionId, position) }
    }

    private fun withDuration(target: PlaybackTarget): PlaybackTarget {
        val duration = player.duration
        return if (duration != C.TIME_UNSET && duration > 0 && player.currentMediaItem?.mediaId == target.itemId) target.copy(durationMs = duration) else target
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 10_000L
    }
}
