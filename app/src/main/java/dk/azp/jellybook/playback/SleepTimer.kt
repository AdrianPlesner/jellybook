package dk.azp.jellybook.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Pauses playback after a wall-clock delay or once the position reaches a chapter boundary. Runs inside the service. */
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
                    finish(seekTo = null)
                }
            }
            SleepTimerMode.CHAPTER_END -> {
                val stopAt = newState.stopAtPositionMs ?: return
                job = scope.launch {
                    var reached = false
                    while (isActive && !reached) {
                        reached = player.isPlaying && player.currentPosition >= stopAt
                        if (!reached) delay(POLL_INTERVAL_MS)
                    }
                    if (reached) finish(seekTo = stopAt)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun finish(seekTo: Long?) {
        player.pause()
        if (seekTo != null) player.seekTo(seekTo)
        job = null
        state = SleepTimerState.OFF
        onStateChanged(state)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
