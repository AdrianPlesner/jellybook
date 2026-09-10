package dk.azp.jellybook.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.data.chapters.ChapterResult
import dk.azp.jellybook.data.downloads.DownloadInfo
import dk.azp.jellybook.data.local.Bookmark
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.data.progress.ProgressRepository
import dk.azp.jellybook.playback.PlayerUiState
import dk.azp.jellybook.playback.SleepTimerState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BookViewModel(private val container: AppContainer, private val bookId: String) : ViewModel() {

    data class State(
        val book: Book? = null,
        val chapters: List<Chapter> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null,
        val resumePositionMs: Long = 0L,
        val awaitingConflict: Boolean = false,
        val bookmarks: List<Bookmark> = emptyList(),
        val download: DownloadInfo? = null,
        val isOfflineCopy: Boolean = false,
        val coverModel: Any? = null,
        val chapterNote: String? = null,
        val message: String? = null,
    )

    private val stateFlow = MutableStateFlow(State())
    private var mediaItems: List<MediaItem> = emptyList()

    val state: StateFlow<State> = stateFlow

    val player: StateFlow<PlayerUiState> = container.playback.state

    private val isCurrent: Boolean get() = player.value.bookId == bookId

    init {
        load()
        viewModelScope.launch { container.bookmarkRepository.bookmarks(bookId).collect { list -> stateFlow.update { it.copy(bookmarks = list) } } }
        viewModelScope.launch { container.downloadRepository.downloads.collect { map -> stateFlow.update { it.copy(download = map[bookId]) } } }
        viewModelScope.launch {
            container.progressRepository.checkpointApplied.filter { it.bookId == bookId }.collect { applied ->
                stateFlow.update { it.copy(resumePositionMs = applied.positionMs, awaitingConflict = false) }
            }
        }
        viewModelScope.launch {
            player.filter { it.bookId == bookId }.collect { playing ->
                if (playing.bookPositionMs > 0) stateFlow.update { it.copy(resumePositionMs = playing.bookPositionMs) }
            }
        }
    }

    /** Position on the book timeline, whether or not this book is the one loaded in the player. */
    fun currentPositionMs(): Long = if (isCurrent) player.value.bookPositionMs else stateFlow.value.resumePositionMs

    fun togglePlay() {
        if (isCurrent) container.playback.togglePlayPause() else startPlayback(startPosition())
    }

    fun seekTo(positionMs: Long) {
        val book = stateFlow.value.book ?: return
        val clamped = positionMs.coerceIn(0L, book.durationMs)
        if (isCurrent) {
            val split = book.toPartPosition(clamped)
            container.playback.seekToPart(split.partIndex, split.offsetMs)
        } else {
            startPlayback(clamped)
        }
    }

    fun skip(deltaMs: Long) = seekTo(currentPositionMs() + deltaMs)

    fun previousChapter() {
        val position = currentPositionMs()
        val current = chapterAt(position)
        val target = if (current != null && position - current.startMs > CHAPTER_RESTART_THRESHOLD_MS) {
            current.startMs
        } else {
            stateFlow.value.chapters.lastOrNull { it.startMs < (current?.startMs ?: position) }?.startMs ?: 0L
        }
        seekTo(target)
    }

    fun nextChapter() {
        val position = currentPositionMs()
        val next = stateFlow.value.chapters.firstOrNull { it.startMs > position + NEXT_CHAPTER_SLACK_MS } ?: return
        seekTo(next.startMs)
    }

    fun chapterAt(positionMs: Long): Chapter? = stateFlow.value.chapters.lastOrNull { it.startMs <= positionMs }

    fun setSpeed(speed: Float) {
        container.playback.setSpeed(speed)
        viewModelScope.launch { container.sessionStore.savePlaybackSpeed(speed) }
    }

    fun setSleepTimerMinutes(minutes: Int) = container.playback.setSleepTimer(SleepTimerState.forMinutes(minutes))

    fun setSleepTimerAtChapterEnd(chapter: Chapter) =
        container.playback.setSleepTimer(SleepTimerState.untilPosition(chapter.endMs, "End of ${chapter.title}"))

    fun clearSleepTimer() = container.playback.setSleepTimer(SleepTimerState.OFF)

    fun addBookmark(name: String) {
        val position = currentPositionMs()
        viewModelScope.launch {
            container.bookmarkRepository.add(bookId, name, position)
            stateFlow.update { it.copy(message = "Bookmark saved") }
        }
    }

    fun renameBookmark(bookmark: Bookmark, name: String) {
        viewModelScope.launch { container.bookmarkRepository.rename(bookmark, name) }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { container.bookmarkRepository.remove(bookmark) }
    }

    fun jumpToBookmark(bookmark: Bookmark) = seekTo(bookmark.positionMs)

    fun download() {
        val book = stateFlow.value.book ?: return
        viewModelScope.launch {
            container.downloadRepository.startDownload(book, stateFlow.value.chapters)
            val parts = if (book.isMultiPart) " (${book.parts.size} files)" else ""
            stateFlow.update { it.copy(message = "Download started$parts") }
        }
    }

    fun removeDownload() {
        viewModelScope.launch { container.downloadRepository.removeDownload(bookId) }
    }

    fun consumeMessage() = stateFlow.update { it.copy(message = null) }

    private fun load() {
        viewModelScope.launch {
            val session = container.sessionStore.currentSession()
            var offlineCopy = false
            val book = try {
                if (session == null) throw IOException("Not signed in")
                container.bookRepository.book(bookId)
            } catch (e: IOException) {
                offlineCopy = true
                container.downloadRepository.downloadedBook(bookId)
            }
            if (book == null) {
                stateFlow.update { it.copy(isLoading = false, error = "This book is not downloaded and the server is unreachable.") }
                return@launch
            }
            val coverFile = container.downloadRepository.coverFile(bookId)
            val cover: Any? = coverFile
                ?: book.imageTag?.let { tag -> session?.let { container.client.primaryImageUrl(it.serverUrl, book.imageItemId, tag, COVER_HEIGHT_PX) } }
            mediaItems = container.mediaItemFactory.create(book, coverFile?.absolutePath)
            val start = container.progressRepository.resolveStartPosition(book)
            stateFlow.update {
                it.copy(
                    book = book,
                    isLoading = false,
                    isOfflineCopy = offlineCopy,
                    coverModel = cover,
                    resumePositionMs = when (start) {
                        is ProgressRepository.StartPosition.Resolved -> start.positionMs
                        is ProgressRepository.StartPosition.Conflict -> start.conflict.localPositionMs
                    },
                    awaitingConflict = start is ProgressRepository.StartPosition.Conflict,
                )
            }
            val chapters = chaptersFor(book)
            stateFlow.update { it.copy(chapters = chapters.chapters, chapterNote = chapters.note) }
            launch { container.bookmarkRepository.sync(bookId) }
        }
    }

    /** A multi-file book gets its chapters from the files themselves; a single file may need its markers parsed. */
    private suspend fun chaptersFor(book: Book): ChapterResult {
        val firstPart = book.parts.firstOrNull() ?: return ChapterResult(emptyList())
        if (book.isMultiPart) return ChapterResult(book.serverChapters)
        val mediaUri = container.mediaItemFactory.mediaUri(firstPart.itemId) ?: return ChapterResult(book.serverChapters)
        return container.chapterRepository.chaptersFor(book, mediaUri)
    }

    private fun startPosition(): Long {
        val current = stateFlow.value
        val duration = current.book?.durationMs ?: 0L
        return if (duration > 0 && current.resumePositionMs >= duration - END_TOLERANCE_MS) 0L else current.resumePositionMs
    }

    private fun startPlayback(positionMs: Long) {
        val current = stateFlow.value
        if (current.awaitingConflict) {
            stateFlow.update { it.copy(message = "Choose which progress checkpoint to keep first") }
            return
        }
        val book = current.book ?: return
        if (mediaItems.isEmpty()) {
            stateFlow.update { it.copy(message = "Sign in again to play this book") }
            return
        }
        val split = book.toPartPosition(positionMs)
        viewModelScope.launch { container.playback.play(mediaItems, split.partIndex, split.offsetMs) }
    }

    private companion object {
        const val COVER_HEIGHT_PX = 800
        const val END_TOLERANCE_MS = 2_000L
        const val CHAPTER_RESTART_THRESHOLD_MS = 3_000L
        const val NEXT_CHAPTER_SLACK_MS = 500L
    }
}
