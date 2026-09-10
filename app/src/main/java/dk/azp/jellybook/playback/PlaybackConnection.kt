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
import dk.azp.jellybook.data.model.PlaybackTarget
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

/**
 * What the UI knows about playback. Positions are book-relative: a multi-file book plays as a playlist, and the part
 * offsets travel with each item, so the whole app can stay in one timeline.
 */
data class PlayerUiState(
    val bookId: String? = null,
    val partIndex: Int = 0,
    val partTitle: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val hasEnded: Boolean = false,
    val bookPositionMs: Long = 0L,
    val bookDurationMs: Long = 0L,
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

    suspend fun play(items: List<MediaItem>, startPartIndex: Int, startOffsetInPartMs: Long) {
        if (items.isEmpty()) return
        val active = awaitController()
        active.setMediaItems(items, startPartIndex.coerceIn(items.indices), startOffsetInPartMs.coerceAtLeast(0L))
        active.prepare()
        active.play()
    }

    fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPlaying) {
            active.pause()
        } else {
            if (active.playbackState == Player.STATE_IDLE) active.prepare()
            if (active.playbackState == Player.STATE_ENDED) active.seekTo(0, 0L)
            active.play()
        }
    }

    fun seekToPart(partIndex: Int, offsetInPartMs: Long) {
        val active = controller ?: return
        val index = partIndex.coerceIn(0, (active.mediaItemCount - 1).coerceAtLeast(0))
        active.seekTo(index, offsetInPartMs.coerceAtLeast(0L))
    }

    /** Book-relative position of the player right now, or null when it is not playing this book. */
    fun bookPosition(bookId: String): Long? {
        val active = controller ?: return null
        val target = active.currentMediaItem?.let { PlaybackTarget.fromMediaItem(it) } ?: return null
        return if (target.bookId == bookId) target.bookPositionMs(active.currentPosition) else null
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
        val target = player.currentMediaItem?.let { PlaybackTarget.fromMediaItem(it) }
        stateFlow.update { current ->
            current.copy(
                bookId = target?.bookId,
                partIndex = target?.partIndex ?: 0,
                partTitle = target?.partTitle,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                hasEnded = player.playbackState == Player.STATE_ENDED,
                bookPositionMs = target?.bookPositionMs(player.currentPosition.coerceAtLeast(0L)) ?: 0L,
                bookDurationMs = target?.bookDurationMs?.takeIf { it > 0 }
                    ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    ?: current.bookDurationMs,
                speed = player.playbackParameters.speed,
            )
        }
    }

    private companion object {
        const val POSITION_REFRESH_MS = 500L
    }
}
