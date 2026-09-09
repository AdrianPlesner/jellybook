package dk.azp.jellybook.data.progress

import android.util.Log
import dk.azp.jellybook.data.Connectivity
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.jellyfin.JellyfinException
import dk.azp.jellybook.data.jellyfin.PlaybackStartInfo
import dk.azp.jellybook.data.jellyfin.PlaybackStopInfo
import dk.azp.jellybook.data.jellyfin.UpdateUserItemDataDto
import dk.azp.jellybook.data.jellyfin.msToTicks
import dk.azp.jellybook.data.local.LocalProgress
import dk.azp.jellybook.data.local.ProgressConflict
import dk.azp.jellybook.data.local.ProgressStore
import dk.azp.jellybook.data.local.ServerSession
import dk.azp.jellybook.data.local.SessionStore
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.data.model.PlaybackTarget
import dk.azp.jellybook.data.model.RemoteProgress
import dk.azp.jellybook.data.model.toPlaybackTarget
import dk.azp.jellybook.data.model.toRemoteProgress
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the listening position on the device and on the Jellyfin server in step.
 *
 * Live playback reports through Jellyfin's session API (so the server shows "now playing" and applies its own played/resume
 * rules) and additionally writes the exact position through the user-data endpoint, which Jellyfin's audiobook heuristics
 * would otherwise round away. Positions recorded while offline are flagged and pushed later; when the server position moved
 * in the meantime the two checkpoints are surfaced as a conflict for the user to decide.
 */
class ProgressRepository(
    private val client: JellyfinClient,
    private val sessionStore: SessionStore,
    private val progressStore: ProgressStore,
    private val connectivity: Connectivity,
) {

    sealed interface StartPosition {
        data class Resolved(val positionMs: Long) : StartPosition

        data class Conflict(val conflict: ProgressConflict) : StartPosition
    }

    data class AppliedCheckpoint(val itemId: String, val positionMs: Long)

    private enum class ReportKind { START, PROGRESS, PAUSE, STOP, FINISH }

    private val syncMutex = Mutex()
    private val appliedCheckpoints = MutableSharedFlow<AppliedCheckpoint>(extraBufferCapacity = 8)

    val conflicts: Flow<List<ProgressConflict>> = progressStore.pendingConflicts

    val localProgress: Flow<Map<String, LocalProgress>> = progressStore.all

    /** Emits after a conflict is resolved so an active player can jump to the chosen checkpoint. */
    val checkpointApplied: SharedFlow<AppliedCheckpoint> = appliedCheckpoints

    suspend fun localProgress(itemId: String): LocalProgress? = progressStore.get(itemId)

    suspend fun mostRecentlyPlayed(): LocalProgress? = progressStore.mostRecent()

    suspend fun resolveStartPosition(book: Book): StartPosition {
        val local = progressStore.get(book.id)
        val remote = book.remoteProgress
        val duration = book.durationMs
        val target = book.toPlaybackTarget(null)
        return when {
            remote == null -> StartPosition.Resolved(local?.positionMs ?: 0L)
            local == null -> {
                adoptRemote(target, remote)
                StartPosition.Resolved(remote.effectivePositionMs(duration))
            }
            !local.pendingSync -> {
                if (serverUnchanged(local, remote, duration)) {
                    StartPosition.Resolved(local.positionMs)
                } else {
                    adoptRemote(target, remote)
                    StartPosition.Resolved(remote.effectivePositionMs(duration))
                }
            }
            serverUnchanged(local, remote, duration) || near(remote.effectivePositionMs(duration), local.positionMs) -> {
                sessionStore.currentSession()?.let { session -> runCatching { pushLocal(session, local) } }
                StartPosition.Resolved(local.positionMs)
            }
            else -> {
                val conflict = conflictOf(local, remote, duration)
                progressStore.addConflict(conflict)
                StartPosition.Conflict(conflict)
            }
        }
    }

    suspend fun onPlaybackStarted(target: PlaybackTarget, playSessionId: String, positionMs: Long) =
        report(target, playSessionId, positionMs, ReportKind.START)

    suspend fun onPlaybackProgress(target: PlaybackTarget, playSessionId: String, positionMs: Long, paused: Boolean) =
        report(target, playSessionId, positionMs, if (paused) ReportKind.PAUSE else ReportKind.PROGRESS)

    suspend fun onPlaybackStopped(target: PlaybackTarget, playSessionId: String, positionMs: Long) =
        report(target, playSessionId, positionMs, ReportKind.STOP)

    suspend fun onPlaybackFinished(target: PlaybackTarget, playSessionId: String) =
        report(target, playSessionId, target.durationMs, ReportKind.FINISH)

    /** Pushes positions recorded while offline, recording a conflict for any book the server moved in the meantime. */
    suspend fun flushPending() = syncMutex.withLock {
        val session = sessionStore.currentSession() ?: return
        if (!connectivity.isOnline()) return
        var reachable = true
        for (local in progressStore.pending()) {
            if (!reachable || progressStore.conflict(local.itemId) != null) continue
            try {
                val remote = client.item(session.serverUrl, session.userId, local.itemId).userData?.toRemoteProgress()
                val duration = local.durationMs
                val compatible = remote == null || serverUnchanged(local, remote, duration) || near(remote.effectivePositionMs(duration), local.positionMs)
                if (compatible) {
                    pushLocal(session, local)
                } else {
                    progressStore.addConflict(conflictOf(local, remote, duration))
                }
            } catch (e: JellyfinException) {
                if (e.statusCode == 404) {
                    progressStore.update(local.itemId) { it.copy(pendingSync = false) }
                } else {
                    Log.w(TAG, "Could not sync ${local.title}", e)
                    reachable = false
                }
            } catch (e: IOException) {
                Log.w(TAG, "Server unreachable while syncing ${local.title}", e)
                reachable = false
            }
        }
    }

    suspend fun resolveConflict(itemId: String, keepLocal: Boolean) {
        val conflict = progressStore.conflict(itemId) ?: return
        val chosen = if (keepLocal) conflict.localPositionMs else conflict.serverPositionMs
        val now = System.currentTimeMillis()
        if (keepLocal) {
            val local = progressStore.get(itemId)
            val session = sessionStore.currentSession()
            if (local != null && session != null) {
                runCatching { pushLocal(session, local) }.onFailure { Log.w(TAG, "Keeping local checkpoint pending; push failed", it) }
            }
        } else {
            progressStore.update(itemId) { entry ->
                entry.copy(positionMs = chosen, updatedAtEpochMs = now, finished = false, pendingSync = false, serverPositionMs = chosen)
            }
        }
        progressStore.removeConflict(itemId)
        appliedCheckpoints.tryEmit(AppliedCheckpoint(itemId, chosen))
    }

    suspend fun clearLocalData() = progressStore.clear()

    private suspend fun report(target: PlaybackTarget, playSessionId: String, positionMs: Long, kind: ReportKind) {
        val now = System.currentTimeMillis()
        val previous = progressStore.get(target.itemId)
        val duration = if (target.durationMs > 0) target.durationMs else previous?.durationMs ?: 0L
        progressStore.save(
            LocalProgress(
                itemId = target.itemId,
                title = target.title,
                author = target.author,
                imageTag = target.imageTag,
                mediaSourceId = target.mediaSourceId,
                positionMs = positionMs,
                durationMs = duration,
                updatedAtEpochMs = now,
                finished = kind == ReportKind.FINISH,
                pendingSync = true,
                serverPositionMs = previous?.serverPositionMs,
            ),
        )
        val session = sessionStore.currentSession() ?: return
        if (!connectivity.isOnline()) return
        try {
            pushLive(session, target, playSessionId, positionMs, kind)
            progressStore.update(target.itemId) { it.copy(pendingSync = false, serverPositionMs = positionMs) }
        } catch (e: IOException) {
            Log.w(TAG, "Progress report failed; will retry when online", e)
        }
    }

    private suspend fun pushLive(session: ServerSession, target: PlaybackTarget, playSessionId: String, positionMs: Long, kind: ReportKind) {
        val ticks = positionMs.msToTicks()
        val startInfo = PlaybackStartInfo(
            itemId = target.itemId,
            mediaSourceId = target.mediaSourceId,
            playSessionId = playSessionId,
            positionTicks = ticks,
            isPaused = kind == ReportKind.PAUSE,
        )
        when (kind) {
            ReportKind.START -> client.reportPlaybackStart(session.serverUrl, startInfo)
            ReportKind.PROGRESS, ReportKind.PAUSE -> client.reportPlaybackProgress(session.serverUrl, startInfo)
            ReportKind.STOP, ReportKind.FINISH ->
                client.reportPlaybackStopped(session.serverUrl, PlaybackStopInfo(target.itemId, target.mediaSourceId, playSessionId, ticks))
        }
        if (kind != ReportKind.START) {
            writeExactPosition(session, target.itemId, target.mediaSourceId, positionMs, finished = kind == ReportKind.FINISH)
        }
    }

    private suspend fun pushLocal(session: ServerSession, local: LocalProgress) {
        writeExactPosition(session, local.itemId, local.mediaSourceId, local.positionMs, local.finished)
        progressStore.update(local.itemId) { it.copy(pendingSync = false, serverPositionMs = local.positionMs) }
    }

    private suspend fun writeExactPosition(session: ServerSession, itemId: String, mediaSourceId: String?, positionMs: Long, finished: Boolean) {
        val update = if (finished) UpdateUserItemDataDto(playbackPositionTicks = 0, played = true) else UpdateUserItemDataDto(playbackPositionTicks = positionMs.msToTicks())
        val result = client.updateUserData(session.serverUrl, session.userId, itemId, update)
        if (result == null) {
            val stopInfo = PlaybackStopInfo(itemId, mediaSourceId, UUID.randomUUID().toString(), positionMs.msToTicks())
            client.reportPlaybackStopped(session.serverUrl, stopInfo)
        }
    }

    private suspend fun adoptRemote(target: PlaybackTarget, remote: RemoteProgress) {
        val position = remote.effectivePositionMs(target.durationMs)
        progressStore.save(
            LocalProgress(
                itemId = target.itemId,
                title = target.title,
                author = target.author,
                imageTag = target.imageTag,
                mediaSourceId = target.mediaSourceId,
                positionMs = position,
                durationMs = target.durationMs,
                updatedAtEpochMs = remote.lastPlayedEpochMs ?: System.currentTimeMillis(),
                finished = remote.played && remote.positionMs == 0L,
                pendingSync = false,
                serverPositionMs = position,
            ),
        )
    }

    private fun conflictOf(local: LocalProgress, remote: RemoteProgress?, durationMs: Long): ProgressConflict = ProgressConflict(
        itemId = local.itemId,
        title = local.title,
        durationMs = durationMs,
        localPositionMs = local.positionMs,
        localUpdatedAtEpochMs = local.updatedAtEpochMs,
        serverPositionMs = remote?.effectivePositionMs(durationMs) ?: 0L,
        serverUpdatedAtEpochMs = remote?.lastPlayedEpochMs,
    )

    /**
     * True when the server still holds what this device last wrote. Jellyfin rounds audiobook positions inside the first
     * minutes to zero and marks the book played near the end, so those two cases count as unchanged as well.
     */
    private fun serverUnchanged(local: LocalProgress, remote: RemoteProgress, durationMs: Long): Boolean {
        val known = local.serverPositionMs ?: return false
        val remotePosition = remote.positionMs
        val roundedToStart = remotePosition == 0L && !remote.played && known < MIN_AUDIOBOOK_RESUME_MS
        val markedPlayed = remote.played && remotePosition == 0L && (durationMs <= 0 || durationMs - known < MAX_AUDIOBOOK_RESUME_MS || local.finished)
        return near(remotePosition, known) || roundedToStart || markedPlayed
    }

    private fun near(a: Long, b: Long): Boolean = abs(a - b) <= POSITION_TOLERANCE_MS

    private companion object {
        const val TAG = "ProgressRepository"
        const val POSITION_TOLERANCE_MS = 5_000L
        const val MIN_AUDIOBOOK_RESUME_MS = 5 * 60_000L
        const val MAX_AUDIOBOOK_RESUME_MS = 5 * 60_000L
    }
}
