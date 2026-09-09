package dk.azp.jellybook.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val hasEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val sleepTimer: SleepTimerState = SleepTimerState.OFF,
    val errorMessage: String? = null,
)

/** The UI's handle on the playback service: a MediaController plus a StateFlow mirror of what the player is doing. */
class PlaybackConnection(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateFlow = MutableStateFlow(PlayerUiState())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    val state: StateFlow<PlayerUiState> = stateFlow

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            stateFlow.update { it.copy(errorMessage = error.localizedMessage ?: "Playback failed") }
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            stateFlow.update { it.copy(sleepTimer = PlayerCommands.sleepTimerState(extras)) }
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture === future && !future.isCancelled) {
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = connected
                connected.addListener(playerListener)
                publish(connected)
                stateFlow.update { it.copy(sleepTimer = PlayerCommands.sleepTimerState(connected.sessionExtras)) }
                startTicker()
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    suspend fun play(mediaItem: MediaItem, startPositionMs: Long) {
        val active = awaitController()
        active.setMediaItem(mediaItem, startPositionMs)
        active.prepare()
        active.play()
    }

    fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPlaying) {
            active.pause()
        } else {
            if (active.playbackState == Player.STATE_IDLE) active.prepare()
            if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
            active.play()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) {
        val active = controller ?: return
        val duration = active.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        active.seekTo((active.currentPosition + deltaMs).coerceIn(0L, duration))
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    fun setSleepTimer(timer: SleepTimerState) {
        controller?.sendCustomCommand(PlayerCommands.SET_SLEEP_TIMER, PlayerCommands.sleepTimerExtras(timer))
    }

    fun clearError() {
        stateFlow.update { it.copy(errorMessage = null) }
    }

    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        if (controllerFuture == null) connect()
        return checkNotNull(controllerFuture).await()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.let { if (it.isPlaying) publish(it) }
                delay(POSITION_REFRESH_MS)
            }
        }
    }

    private fun publish(player: Player) {
        stateFlow.update { current ->
            current.copy(
                mediaId = player.currentMediaItem?.mediaId,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                hasEnded = player.playbackState == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current.durationMs,
                speed = player.playbackParameters.speed,
            )
        }
    }

    private companion object {
        const val POSITION_REFRESH_MS = 500L
    }
}
