package dk.azp.jellybook.playback

import androidx.media3.common.Player
import dk.azp.jellybook.data.model.PlaybackTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pauses playback after a wall-clock delay or once the book position reaches a chapter boundary. Chapter positions are
 * book-relative, which for a multi-file book means they can fall in a later part than the one playing now.
 */
class SleepTimer(
    private val player: Player,
    private val scope: CoroutineScope,
    private val onStateChanged: (SleepTimerState) -> Unit,
) {

    private var job: Job? = null

    var state: SleepTimerState = SleepTimerState.OFF
        private set

    fun apply(newState: SleepTimerState) {
        job?.cancel()
        job = null
        state = newState
        onStateChanged(state)
        when (newState.mode) {
            SleepTimerMode.OFF -> Unit
            SleepTimerMode.DURATION -> {
                val delayMs = ((newState.endsAtEpochMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
                job = scope.launch {
                    delay(delayMs)
                    finish()
                }
            }
            SleepTimerMode.CHAPTER_END -> {
                val stopAt = newState.stopAtPositionMs ?: return
                job = scope.launch {
                    var reached = false
                    while (isActive && !reached) {
                        val position = bookPosition()
                        reached = player.isPlaying && position != null && position >= stopAt
                        if (!reached) delay(POLL_INTERVAL_MS)
                    }
                    if (reached) finish()
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun bookPosition(): Long? {
        val target = player.currentMediaItem?.let { PlaybackTarget.fromMediaItem(it) } ?: return null
        return target.bookPositionMs(player.currentPosition)
    }

    private fun finish() {
        player.pause()
        job = null
        state = SleepTimerState.OFF
        onStateChanged(state)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
