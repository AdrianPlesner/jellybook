package dk.azp.jellybook.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.data.downloads.DownloadInfo
import dk.azp.jellybook.data.downloads.DownloadStatus
import dk.azp.jellybook.data.local.Bookmark
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.playback.PlayerUiState
import dk.azp.jellybook.ui.formatClock
import dk.azp.jellybook.ui.formatDurationShort
import dk.azp.jellybook.ui.formatRelativeTime
import dk.azp.jellybook.ui.formatSpeed
import dk.azp.jellybook.ui.library.Cover

private val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val SKIP_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(container: AppContainer, bookId: String, onBack: () -> Unit) {
    val viewModel: BookViewModel = viewModel(key = "book:$bookId") { BookViewModel(container, bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val isCurrent = player.bookId == bookId
    val positionMs = if (isCurrent) player.bookPositionMs else state.resumePositionMs
    val durationMs = state.book?.durationMs?.takeIf { it > 0 } ?: player.bookDurationMs
    val currentChapter = state.chapters.lastOrNull { it.startMs <= positionMs }

    var showSleepDialog by remember { mutableStateOf(false) }
    var showAddBookmark by remember { mutableStateOf(false) }
    var bookmarkToRename by remember { mutableStateOf<Bookmark?>(null) }
    var confirmRemoveDownload by remember { mutableStateOf(false) }
    var listTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(player.errorMessage) {
        player.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            container.playback.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.book != null) {
                        DownloadAction(state.download, onDownload = viewModel::download, onRemove = { confirmRemoveDownload = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val book = state.book
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            book == null -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(state.error ?: "Book not found", color = MaterialTheme.colorScheme.error)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                item { BookHeader(book, state) }
                item {
                    PlayerCard(
                        player = player,
                        isCurrent = isCurrent,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        currentChapter = currentChapter,
                        hasChapters = state.chapters.isNotEmpty(),
                        awaitingConflict = state.awaitingConflict,
                        onTogglePlay = viewModel::togglePlay,
                        onSeek = viewModel::seekTo,
                        onSkip = viewModel::skip,
                        onPreviousChapter = viewModel::previousChapter,
                        onNextChapter = viewModel::nextChapter,
                        onCycleSpeed = { viewModel.setSpeed(nextSpeed(player.speed)) },
                        onSleepTimer = { showSleepDialog = true },
                        onAddBookmark = { showAddBookmark = true },
                    )
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = listTab == 0, onClick = { listTab = 0 }, label = { Text("Chapters (${state.chapters.size})") })
                        FilterChip(selected = listTab == 1, onClick = { listTab = 1 }, label = { Text("Bookmarks (${state.bookmarks.size})") })
                    }
                }
                if (listTab == 0) {
                    if (state.chapters.isEmpty()) {
                        item {
                            val reason = state.chapterNote ?: "This file has no chapter markers."
                            HintText("$reason Use the slider or bookmarks to navigate.")
                        }
                    }
                    items(state.chapters, key = { it.index }) { chapter ->
                        ChapterRow(chapter, isCurrent = chapter == currentChapter, onClick = { viewModel.seekTo(chapter.startMs) })
                    }
                } else {
                    if (state.bookmarks.isEmpty()) {
                        item { HintText("No bookmarks yet. Tap \"Bookmark\" in the player to mark the current position.") }
                    }
                    items(state.bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            chapterTitle = viewModel.chapterAt(bookmark.positionMs)?.title,
                            onClick = { viewModel.jumpToBookmark(bookmark) },
                            onRename = { bookmarkToRename = bookmark },
                            onDelete = { viewModel.deleteBookmark(bookmark) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            activeTimer = player.sleepTimer,
            chapters = state.chapters,
            currentChapter = currentChapter,
            onMinutes = {
                viewModel.setSleepTimerMinutes(it)
                showSleepDialog = false
            },
            onChapterEnd = {
                viewModel.setSleepTimerAtChapterEnd(it)
                showSleepDialog = false
            },
            onTurnOff = {
                viewModel.clearSleepTimer()
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false },
        )
    }
    if (showAddBookmark) {
        val position = viewModel.currentPositionMs()
        BookmarkNameDialog(
            title = "New bookmark",
            initialName = currentChapter?.title ?: formatClock(position),
            positionMs = position,
            confirmLabel = "Save",
            onConfirm = {
                viewModel.addBookmark(it)
                showAddBookmark = false
            },
            onDismiss = { showAddBookmark = false },
        )
    }
    bookmarkToRename?.let { bookmark ->
        BookmarkNameDialog(
            title = "Rename bookmark",
            initialName = bookmark.name,
            positionMs = bookmark.positionMs,
            confirmLabel = "Rename",
            onConfirm = {
                viewModel.renameBookmark(bookmark, it)
                bookmarkToRename = null
            },
            onDismiss = { bookmarkToRename = null },
        )
    }
    if (confirmRemoveDownload) {
        RemoveDownloadDialog(
            onConfirm = {
                viewModel.removeDownload()
                confirmRemoveDownload = false
            },
            onDismiss = { confirmRemoveDownload = false },
        )
    }
}

private fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { it > current + 0.01f }
    return if (index == -1) SPEED_STEPS.first() else SPEED_STEPS[index]
}

@Composable
private fun DownloadAction(download: DownloadInfo?, onDownload: () -> Unit, onRemove: () -> Unit) {
    when (download?.status) {
        DownloadStatus.COMPLETED -> IconButton(onClick = onRemove) {
            Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded, tap to remove", tint = MaterialTheme.colorScheme.primary)
        }
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> IconButton(onClick = onRemove) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Icon(Icons.Filled.Close, contentDescription = "Cancel download", modifier = Modifier.size(12.dp))
            }
        }
        DownloadStatus.FAILED -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = "Download failed, tap to retry", tint = MaterialTheme.colorScheme.error)
        }
        DownloadStatus.REMOVING -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        null -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download for offline listening") }
    }
}

@Composable
private fun BookHeader(book: Book, state: BookViewModel.State) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row {
            Cover(state.coverModel, size = 140.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                book.author?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(4.dp))
                val details = listOfNotNull(
                    book.productionYear?.toString(),
                    formatDurationShort(book.durationMs),
                    book.container?.uppercase(),
                    if (book.isMultiPart) "${book.parts.size} files" else null,
                )
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.download?.status == DownloadStatus.COMPLETED) SuggestionChip(onClick = {}, label = { Text("Downloaded") })
                    if (state.isOfflineCopy) SuggestionChip(onClick = {}, label = { Text("Offline") })
                }
            }
        }
        book.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Spacer(Modifier.height(12.dp))
            Text(overview, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlayerCard(
    player: PlayerUiState,
    isCurrent: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentChapter: Chapter?,
    hasChapters: Boolean,
    awaitingConflict: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSleepTimer: () -> Unit,
    onAddBookmark: () -> Unit,
) {
    val rangeStart = currentChapter?.startMs ?: 0L
    val rangeEnd = (currentChapter?.endMs ?: durationMs).coerceAtLeast(rangeStart + 1)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val sliderValue = dragValue ?: (positionMs - rangeStart).coerceIn(0L, rangeEnd - rangeStart).toFloat()
    val isPlaying = isCurrent && player.isPlaying
    val isBuffering = isCurrent && player.isBuffering && player.playWhenReady

    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                currentChapter?.title ?: if (hasChapters) "Before first chapter" else "Whole book",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (awaitingConflict) {
                Text("Progress conflict: choose a checkpoint to continue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Slider(
                value = sliderValue,
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeek(rangeStart + it.toLong()) }
                    dragValue = null
                },
                valueRange = 0f..(rangeEnd - rangeStart).toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(sliderValue.toLong()), style = MaterialTheme.typography.labelMedium)
                Text("-${formatClock(rangeEnd - rangeStart - sliderValue.toLong())}", style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatClock(positionMs)} / ${formatClock(durationMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatDurationShort(durationMs - positionMs)} left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousChapter, enabled = hasChapters) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter") }
                IconButton(onClick = { onSkip(-SKIP_MS) }) { Icon(Icons.Filled.Replay30, contentDescription = "Back 30 seconds") }
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp)) {
                    when {
                        isBuffering -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                        isPlaying -> Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(36.dp))
                        else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(36.dp))
                    }
                }
                IconButton(onClick = { onSkip(SKIP_MS) }) { Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds") }
                IconButton(onClick = onNextChapter, enabled = hasChapters) { Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                AssistChip(
                    onClick = onCycleSpeed,
                    label = { Text(formatSpeed(player.speed)) },
                    leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = onSleepTimer,
                    enabled = isCurrent,
                    label = { Text(if (player.sleepTimer.isActive) player.sleepTimer.label ?: "Sleep on" else "Sleep") },
                    leadingIcon = { Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = onAddBookmark,
                    label = { Text("Bookmark") },
                    leadingIcon = { Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, isCurrent: Boolean, onClick: () -> Unit) {
    val background = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().background(background).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${chapter.index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(chapter.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Starts ${formatClock(chapter.startMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatDurationShort(chapter.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, chapterTitle: String?, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(bookmark.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(formatClock(bookmark.positionMs), chapterTitle, formatRelativeTime(bookmark.createdAtEpochMs))
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Rename bookmark") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark") }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}
