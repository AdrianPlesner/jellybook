package dk.azp.jellybook.data.progress

import android.util.Log
import dk.azp.jellybook.data.BookRepository
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
 * Positions are stored on the device per book, on the book's own timeline. Jellyfin has no place to put a book-level
 * position for a multi-file book, so reports go to the part that is playing, and the book position is reassembled from the
 * parts' user data. Live playback reports through the session API (so the server shows "now playing" and applies its own
 * played/resume rules) and additionally writes the exact position through the user-data endpoint, which Jellyfin's
 * audiobook heuristics would otherwise round away. Positions recorded while offline are flagged and pushed later; when the
 * server position moved in the meantime the two checkpoints are surfaced as a conflict for the user to decide.
 */
class ProgressRepository(
    private val client: JellyfinClient,
    private val bookRepository: BookRepository,
    private val sessionStore: SessionStore,
    private val progressStore: ProgressStore,
    private val connectivity: Connectivity,
) {

    sealed interface StartPosition {
        data class Resolved(val positionMs: Long) : StartPosition

        data class Conflict(val conflict: ProgressConflict) : StartPosition
    }

    data class AppliedCheckpoint(val bookId: String, val positionMs: Long)

    enum class ReportKind { START, PROGRESS, PAUSE, STOP, PART_FINISHED, BOOK_FINISHED }

    private val syncMutex = Mutex()
    private val appliedCheckpoints = MutableSharedFlow<AppliedCheckpoint>(extraBufferCapacity = 8)

    val conflicts: Flow<List<ProgressConflict>> = progressStore.pendingConflicts

    val localProgress: Flow<Map<String, LocalProgress>> = progressStore.all

    /** Emits after a conflict is resolved so an active player can jump to the chosen checkpoint. */
    val checkpointApplied: SharedFlow<AppliedCheckpoint> = appliedCheckpoints

    suspend fun localProgress(bookId: String): LocalProgress? = progressStore.get(bookId)

    suspend fun mostRecentlyPlayed(): LocalProgress? = progressStore.mostRecent()

    suspend fun resolveStartPosition(book: Book): StartPosition {
        val local = progressStore.get(book.id)
        val remote = book.remoteProgress()
        val duration = book.durationMs
        return when {
            remote == null -> StartPosition.Resolved(local?.positionMs ?: 0L)
            local == null -> {
                adoptRemote(book, remote)
                StartPosition.Resolved(remote.effectivePositionMs(duration))
            }
            !local.pendingSync -> {
                if (serverUnchanged(local, remote, duration)) {
                    StartPosition.Resolved(local.positionMs)
                } else {
                    adoptRemote(book, remote)
                    StartPosition.Resolved(remote.effectivePositionMs(duration))
                }
            }
            serverUnchanged(local, remote, duration) || near(remote.effectivePositionMs(duration), local.positionMs) -> {
                sessionStore.currentSession()?.let { session -> runCatching { pushLocal(session, book, local) } }
                StartPosition.Resolved(local.positionMs)
            }
            else -> {
                val conflict = conflictOf(local, remote, duration)
                progressStore.addConflict(conflict)
                StartPosition.Conflict(conflict)
            }
        }
    }

    suspend fun onPlaybackStarted(target: PlaybackTarget, playSessionId: String, positionInPartMs: Long) =
        report(target, playSessionId, positionInPartMs, ReportKind.START)

    suspend fun onPlaybackProgress(target: PlaybackTarget, playSessionId: String, positionInPartMs: Long, paused: Boolean) =
        report(target, playSessionId, positionInPartMs, if (paused) ReportKind.PAUSE else ReportKind.PROGRESS)

    suspend fun onPlaybackStopped(target: PlaybackTarget, playSessionId: String, positionInPartMs: Long) =
        report(target, playSessionId, positionInPartMs, ReportKind.STOP)

    /** A part played to its end. The book counts as finished only when that part was the last one. */
    suspend fun onPartFinished(target: PlaybackTarget, playSessionId: String) {
        val kind = if (target.isLastPart) ReportKind.BOOK_FINISHED else ReportKind.PART_FINISHED
        report(target, playSessionId, target.partDurationMs, kind)
    }

    /** Pushes positions recorded while offline, recording a conflict for any book the server moved in the meantime. */
    suspend fun flushPending() {
        syncMutex.withLock { flushPendingLocked() }
    }

    private suspend fun flushPendingLocked() {
        val session = sessionStore.currentSession() ?: return
        if (!connectivity.isOnline()) return
        var reachable = true
        for (local in progressStore.pending()) {
            if (!reachable || progressStore.conflict(local.itemId) != null) continue
            try {
                val book = bookRepository.book(local.itemId)
                val remote = book.remoteProgress()
                val duration = if (book.durationMs > 0) book.durationMs else local.durationMs
                val compatible = remote == null ||
                    serverUnchanged(local, remote, duration) ||
                    near(remote.effectivePositionMs(duration), local.positionMs)
                if (compatible) {
                    pushLocal(session, book, local)
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

    suspend fun resolveConflict(bookId: String, keepLocal: Boolean) {
        val conflict = progressStore.conflict(bookId) ?: return
        val chosen = if (keepLocal) conflict.localPositionMs else conflict.serverPositionMs
        val now = System.currentTimeMillis()
        if (keepLocal) {
            val local = progressStore.get(bookId)
            val session = sessionStore.currentSession()
            if (local != null && session != null) {
                runCatching { pushLocal(session, bookRepository.book(bookId), local) }
                    .onFailure { Log.w(TAG, "Keeping local checkpoint pending; push failed", it) }
            }
        } else {
            progressStore.update(bookId) { entry ->
                entry.copy(positionMs = chosen, updatedAtEpochMs = now, finished = false, pendingSync = false, serverPositionMs = chosen)
            }
        }
        progressStore.removeConflict(bookId)
        appliedCheckpoints.tryEmit(AppliedCheckpoint(bookId, chosen))
    }

    suspend fun clearLocalData() = progressStore.clear()

    private suspend fun report(target: PlaybackTarget, playSessionId: String, positionInPartMs: Long, kind: ReportKind) {
        val now = System.currentTimeMillis()
        val previous = progressStore.get(target.bookId)
        val bookDuration = if (target.bookDurationMs > 0) target.bookDurationMs else previous?.durationMs ?: 0L
        val bookPosition = if (kind == ReportKind.BOOK_FINISHED) bookDuration else target.bookPositionMs(positionInPartMs)
        progressStore.save(
            LocalProgress(
                itemId = target.bookId,
                title = target.bookTitle,
                author = target.author,
                imageTag = target.imageTag,
                imageItemId = target.imageItemId,
                positionMs = bookPosition,
                durationMs = bookDuration,
                updatedAtEpochMs = now,
                finished = kind == ReportKind.BOOK_FINISHED,
                pendingSync = true,
                serverPositionMs = previous?.serverPositionMs,
            ),
        )
        val session = sessionStore.currentSession() ?: return
        if (!connectivity.isOnline()) return
        try {
            pushLive(session, target, playSessionId, positionInPartMs, kind)
            progressStore.update(target.bookId) { it.copy(pendingSync = false, serverPositionMs = bookPosition) }
        } catch (e: IOException) {
            Log.w(TAG, "Progress report failed; will retry when online", e)
        }
    }

    private suspend fun pushLive(
        session: ServerSession,
        target: PlaybackTarget,
        playSessionId: String,
        positionInPartMs: Long,
        kind: ReportKind,
    ) {
        val ticks = positionInPartMs.msToTicks()
        val startInfo = PlaybackStartInfo(
            itemId = target.partItemId,
            mediaSourceId = null,
            playSessionId = playSessionId,
            positionTicks = ticks,
            isPaused = kind == ReportKind.PAUSE,
        )
        when (kind) {
            ReportKind.START -> client.reportPlaybackStart(session.serverUrl, startInfo)
            ReportKind.PROGRESS, ReportKind.PAUSE -> client.reportPlaybackProgress(session.serverUrl, startInfo)
            ReportKind.STOP, ReportKind.PART_FINISHED, ReportKind.BOOK_FINISHED ->
                client.reportPlaybackStopped(session.serverUrl, PlaybackStopInfo(target.partItemId, null, playSessionId, ticks))
        }
        if (kind == ReportKind.START) return
        val finishedPart = kind == ReportKind.PART_FINISHED || kind == ReportKind.BOOK_FINISHED
        writePosition(session, target.partItemId, positionInPartMs, played = finishedPart)
    }

    /**
     * Writes a book position by placing it in the part that holds it. Everything before that part is marked played, so any
     * client (including Jellyfin's own web UI) resumes in the right place.
     */
    private suspend fun pushLocal(session: ServerSession, book: Book, local: LocalProgress) {
        val split = book.toPartPosition(local.positionMs)
        for (part in book.parts) {
            when {
                local.finished || part.index < split.partIndex -> writePosition(session, part.itemId, 0, played = true)
                part.index == split.partIndex -> writePosition(session, part.itemId, split.offsetMs, played = false)
                else -> Unit
            }
        }
        progressStore.update(local.itemId) { it.copy(pendingSync = false, serverPositionMs = local.positionMs) }
    }

    private suspend fun writePosition(session: ServerSession, itemId: String, positionMs: Long, played: Boolean) {
        val update = if (played) {
            UpdateUserItemDataDto(playbackPositionTicks = 0, played = true)
        } else {
            UpdateUserItemDataDto(playbackPositionTicks = positionMs.msToTicks())
        }
        val result = client.updateUserData(session.serverUrl, session.userId, itemId, update)
        if (result == null) {
            val stopInfo = PlaybackStopInfo(itemId, null, UUID.randomUUID().toString(), positionMs.msToTicks())
            client.reportPlaybackStopped(session.serverUrl, stopInfo)
        }
    }

    private suspend fun adoptRemote(book: Book, remote: RemoteProgress) {
        val position = remote.effectivePositionMs(book.durationMs)
        progressStore.save(
            LocalProgress(
                itemId = book.id,
                title = book.title,
                author = book.author,
                imageTag = book.imageTag,
                imageItemId = book.imageItemId,
                positionMs = position,
                durationMs = book.durationMs,
                updatedAtEpochMs = remote.lastPlayedEpochMs ?: System.currentTimeMillis(),
                finished = remote.played,
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
     * minutes to zero and marks an item played near its end, so those two cases count as unchanged as well.
     */
    private fun serverUnchanged(local: LocalProgress, remote: RemoteProgress, durationMs: Long): Boolean {
        val known = local.serverPositionMs ?: return false
        val remotePosition = remote.positionMs
        val roundedToStart = remotePosition == 0L && !remote.played && known < MIN_AUDIOBOOK_RESUME_MS
        val markedPlayed = remote.played && (durationMs <= 0 || durationMs - known < MAX_AUDIOBOOK_RESUME_MS || local.finished)
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
