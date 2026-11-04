package dk.azp.jellybook.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.data.local.LocalProgress
import dk.azp.jellybook.data.local.ServerSession
import dk.azp.jellybook.playback.PlayerUiState
import dk.azp.jellybook.ui.formatClock
import dk.azp.jellybook.ui.library.Cover
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The book in the player, or else the one last listened to. A book that is not in the player yet is loaded on demand, from
 * its download when there is one, so it plays without waiting for the library to load.
 */
class MiniPlayerViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val bookId: String,
        val title: String,
        val author: String?,
        val coverModel: Any?,
        val positionMs: Long,
        val durationMs: Long,
        val isLoaded: Boolean,
        val isPlaying: Boolean,
        val isBusy: Boolean,
        val message: String?,
    )

    private val loading = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private var messageTimeout: Job? = null

    val state: StateFlow<State?> = combine(
        container.playback.state,
        container.progressRepository.localProgress,
        container.sessionStore.session,
        loading,
        message,
    ) { player, progress, session, isLoading, note ->
        val loadedId = player.bookId
        when {
            loadedId != null -> loadedState(player, progress[loadedId], isLoading, note)
            else -> progress.values.maxByOrNull { it.updatedAtEpochMs }?.let { lastPlayedState(it, session, isLoading, note) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun togglePlay() {
        val current = state.value ?: return
        if (current.isLoaded) {
            container.playback.togglePlayPause()
        } else {
            val finished = current.durationMs > 0 && current.positionMs >= current.durationMs - END_TOLERANCE_MS
            load(current.bookId, if (finished) 0L else current.positionMs, startPlaying = true)
        }
    }

    fun skip(deltaMs: Long) {
        val current = state.value ?: return
        if (current.isLoaded) {
            container.playback.seekBy(deltaMs)
        } else {
            val end = if (current.durationMs > 0) current.durationMs else Long.MAX_VALUE
            load(current.bookId, (current.positionMs + deltaMs).coerceIn(0L, end), startPlaying = false)
        }
    }

    private fun load(bookId: String, positionMs: Long, startPlaying: Boolean) {
        if (loading.value) return
        container.playback.clearError()
        viewModelScope.launch {
            if (container.progressRepository.conflicts.first().any { it.itemId == bookId }) {
                showMessage("Choose which progress checkpoint to keep first")
                return@launch
            }
            loading.value = true
            try {
                val resume = container.resumeLoader.load(bookId, positionMs)
                if (resume == null) {
                    showMessage("Not downloaded, and the server is unreachable")
                } else {
                    container.playback.play(resume.items, resume.partIndex, resume.offsetInPartMs, startPlaying)
                }
            } finally {
                loading.value = false
            }
        }
    }

    private fun showMessage(text: String) {
        message.value = text
        messageTimeout?.cancel()
        messageTimeout = viewModelScope.launch {
            delay(MESSAGE_DURATION_MS)
            message.value = null
        }
    }

    private fun loadedState(player: PlayerUiState, local: LocalProgress?, isLoading: Boolean, note: String?) = State(
        bookId = checkNotNull(player.bookId),
        title = player.bookTitle ?: local?.title.orEmpty(),
        author = player.author ?: local?.author,
        coverModel = player.artworkUri,
        positionMs = player.bookPositionMs,
        durationMs = player.bookDurationMs,
        isLoaded = true,
        isPlaying = player.isPlaying || (player.playWhenReady && player.isBuffering),
        isBusy = isLoading || (player.playWhenReady && player.isBuffering),
        message = note ?: player.errorMessage,
    )

    private fun lastPlayedState(local: LocalProgress, session: ServerSession?, isLoading: Boolean, note: String?) = State(
        bookId = local.itemId,
        title = local.title,
        author = local.author,
        coverModel = coverModel(local, session),
        positionMs = local.positionMs,
        durationMs = local.durationMs,
        isLoaded = false,
        isPlaying = false,
        isBusy = isLoading,
        message = note,
    )

    private fun coverModel(local: LocalProgress, session: ServerSession?): Any? {
        container.downloadRepository.coverFile(local.itemId)?.let { return it }
        val tag = local.imageTag ?: return null
        val serverUrl = session?.serverUrl ?: return null
        return container.client.primaryImageUrl(serverUrl, local.imageItemId ?: local.itemId, tag, COVER_HEIGHT_PX)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MESSAGE_DURATION_MS = 4_000L
        const val END_TOLERANCE_MS = 2_000L
        const val COVER_HEIGHT_PX = 200
    }
}

/** A bar with the current or last played book; hidden while that book's own screen is open. */
@Composable
fun MiniPlayerBar(container: AppContainer, openBookId: String?, onOpenBook: (String) -> Unit) {
    val viewModel: MiniPlayerViewModel = viewModel { MiniPlayerViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    if (current == null || current.bookId == openBookId) return

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            LinearProgressIndicator(
                progress = { if (current.durationMs > 0) (current.positionMs.toFloat() / current.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Row(
                modifier = Modifier
                    .clickable { onOpenBook(current.bookId) }
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(current.coverModel, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        current.message ?: subtitle(current),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { viewModel.skip(-SKIP_MS) }) { SkipIcon(forward = false, contentDescription = "Back 15 seconds") }
                FilledIconButton(onClick = viewModel::togglePlay) {
                    when {
                        current.isBusy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        current.isPlaying -> Icon(Icons.Filled.Pause, contentDescription = "Pause")
                        else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = { viewModel.skip(SKIP_MS) }) { SkipIcon(forward = true, contentDescription = "Forward 15 seconds") }
            }
        }
    }
}

private fun subtitle(state: MiniPlayerViewModel.State): String {
    val clock = if (state.durationMs > 0) "${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}" else formatClock(state.positionMs)
    return listOfNotNull(state.author?.takeIf { it.isNotBlank() }, clock).joinToString(" · ")
}

/** Material has replay icons for 5, 10 and 30 seconds only, so this draws the 15 into the plain one. */
@Composable
private fun SkipIcon(forward: Boolean, contentDescription: String) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Replay,
            contentDescription = contentDescription,
            modifier = if (forward) Modifier.graphicsLayer(scaleX = -1f) else Modifier,
        )
        Text("15", fontSize = 6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

private const val SKIP_MS = 15_000L
