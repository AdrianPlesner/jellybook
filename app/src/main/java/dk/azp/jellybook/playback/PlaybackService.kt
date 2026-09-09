package dk.azp.jellybook.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.JellybookApp
import dk.azp.jellybook.MainActivity
import dk.azp.jellybook.data.model.PlaybackTarget
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private lateinit var container: AppContainer
    private lateinit var player: ExoPlayer
    private lateinit var progressReporter: ProgressReporter
    private lateinit var sleepTimer: SleepTimer
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = (application as JellybookApp).container
        player = buildPlayer()
        val session = buildSession()
        mediaSession = session
        sleepTimer = SleepTimer(player, serviceScope) { state -> session.setSessionExtras(PlayerCommands.sleepTimerExtras(state)) }
        progressReporter = ProgressReporter(player, container.progressRepository, container.appScope)
        player.addListener(progressReporter)
        serviceScope.launch { player.setPlaybackSpeed(container.sessionStore.playbackSpeed.first()) }
        serviceScope.launch {
            container.progressRepository.checkpointApplied.collect { applied ->
                if (player.currentMediaItem?.mediaId == applied.itemId) player.seekTo(applied.positionMs)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        progressReporter.release()
        sleepTimer.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.playbackDataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
    }

    private fun buildSession(): MediaSession {
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bitmapLoader = DataSourceBitmapLoader(
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            container.upstreamDataSourceFactory,
            null,
        )
        return MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(SessionCallback())
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(CacheBitmapLoader(bitmapLoader))
            .setMediaButtonPreferences(mediaButtons())
            .build()
    }

    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
            .setDisplayName("Back 30 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(PlayerCommands.SET_SLEEP_TIMER)
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return if (customCommand.customAction == PlayerCommands.ACTION_SET_SLEEP_TIMER) {
                sleepTimer.apply(PlayerCommands.sleepTimerState(args))
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else {
                Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future {
            mediaItems.map { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    PlaybackTarget.fromMediaItem(item)?.let { container.mediaItemFactory.create(it) } ?: item
                }
            }.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            val recent = container.progressRepository.mostRecentlyPlayed() ?: throw UnsupportedOperationException("Nothing to resume")
            val target = PlaybackTarget(
                itemId = recent.itemId,
                title = recent.title,
                author = recent.author,
                imageTag = recent.imageTag,
                mediaSourceId = recent.mediaSourceId,
                durationMs = recent.durationMs,
                coverPath = container.downloadRepository.coverFile(recent.itemId)?.absolutePath,
            )
            val item = container.mediaItemFactory.create(target) ?: throw UnsupportedOperationException("Not signed in")
            MediaSession.MediaItemsWithStartPosition(listOf(item), 0, recent.positionMs)
        }
    }

    private companion object {
        const val SESSION_ID = "jellybook"
        const val SEEK_INCREMENT_MS = 30_000L
    }
}
