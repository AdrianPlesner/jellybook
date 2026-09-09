package dk.azp.jellybook.ui.library

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.data.downloads.DownloadInfo
import dk.azp.jellybook.data.downloads.DownloadStatus
import dk.azp.jellybook.data.jellyfin.JellyfinException
import dk.azp.jellybook.data.local.LocalProgress
import dk.azp.jellybook.data.local.ServerSession
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.ui.formatDurationShort
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    data class Item(
        val book: Book,
        val positionMs: Long,
        val progressFraction: Float,
        val isFinished: Boolean,
        val lastPlayedEpochMs: Long?,
        val download: DownloadInfo?,
    )

    data class State(
        val items: List<Item> = emptyList(),
        val isLoading: Boolean = true,
        val isOffline: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val session: ServerSession? = null,
    )

    private class Meta(val isLoading: Boolean, val isOffline: Boolean, val error: String?, val session: ServerSession?)

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val offline = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val meta = combine(loading, offline, error, container.sessionStore.session) { isLoading, isOffline, message, session ->
        Meta(isLoading, isOffline, message, session)
    }

    val state: StateFlow<State> = combine(books, query, container.progressRepository.localProgress, container.downloadRepository.downloads, meta) {
            bookList, search, progress, downloads, metadata ->
        val items = bookList
            .filter { book -> matches(book, search) }
            .map { book -> toItem(book, progress[book.id], downloads[book.id]) }
            .sortedWith(itemOrder)
        State(items, metadata.isLoading, metadata.isOffline, metadata.error, search, metadata.session)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    init {
        refresh()
    }

    fun search(text: String) {
        query.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                books.value = container.bookRepository.library()
                offline.value = false
            } catch (e: JellyfinException) {
                if (e.statusCode == 401) container.sessionStore.clearSession() else showOfflineBooks("Server error: ${e.message}")
            } catch (e: IOException) {
                showOfflineBooks(null)
            } finally {
                loading.value = false
            }
        }
    }

    fun syncNow() = container.syncNow()

    fun signOut() {
        viewModelScope.launch { container.signOut() }
    }

    private suspend fun showOfflineBooks(message: String?) {
        books.value = container.downloadRepository.offlineBooks.first()
        offline.value = true
        if (books.value.isEmpty()) error.value = message ?: "The server is unreachable and no books are downloaded"
    }

    private fun matches(book: Book, search: String): Boolean {
        if (search.isBlank()) return true
        val needle = search.trim()
        return book.title.contains(needle, ignoreCase = true) || book.author?.contains(needle, ignoreCase = true) == true
    }

    private fun toItem(book: Book, local: LocalProgress?, download: DownloadInfo?): Item {
        val remote = book.remoteProgress()
        val position = local?.positionMs ?: remote?.effectivePositionMs(book.durationMs) ?: 0L
        val finishedRemotely = remote != null && remote.played && remote.positionMs == 0L
        val nearEnd = book.durationMs > 0 && position >= book.durationMs - END_TOLERANCE_MS
        val finished = local?.finished == true || (local == null && finishedRemotely) || nearEnd
        val fraction = if (book.durationMs > 0) (position.toFloat() / book.durationMs).coerceIn(0f, 1f) else 0f
        return Item(book, position, fraction, finished, local?.updatedAtEpochMs ?: remote?.lastPlayedEpochMs, download)
    }

    private val itemOrder = compareBy<Item> { item ->
        when {
            item.isFinished -> 2
            item.positionMs > 0 -> 0
            else -> 1
        }
    }.thenByDescending { it.lastPlayedEpochMs ?: 0L }.thenBy { it.book.title.lowercase() }

    private companion object {
        const val END_TOLERANCE_MS = 2_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, onOpenBook: (String) -> Unit) {
    val viewModel: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audiobooks")
                        state.session?.let { session ->
                            val subtitle = listOf(session.serverName, session.userName).filter { it.isNotBlank() }.joinToString(" · ")
                            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sync progress now") },
                            leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.syncNow()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.signOut()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isOffline) {
                OfflineBanner()
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                placeholder = { Text("Search title or author") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                state.isLoading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.items.isEmpty() -> ErrorState(state.error ?: "", onRetry = viewModel::refresh)
                state.items.isEmpty() -> EmptyState(state.query.isNotBlank())
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.book.id }) { item ->
                        BookRow(item, coverModel = coverModel(container, state.session, item.book), onClick = { onOpenBook(item.book.id) })
                    }
                }
            }
        }
    }
}

private fun coverModel(container: AppContainer, session: ServerSession?, book: Book): Any? {
    container.downloadRepository.coverFile(book.id)?.let { return it }
    val tag = book.imageTag ?: return null
    val serverUrl = session?.serverUrl ?: return null
    return container.client.primaryImageUrl(serverUrl, book.id, tag, COVER_HEIGHT_PX)
}

private const val COVER_HEIGHT_PX = 300

@Composable
private fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "Offline. Showing downloaded books; progress syncs when the server is reachable again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun EmptyState(isSearching: Boolean) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            if (isSearching) "No books match your search." else "No audiobooks found. Add a library with content type \"Books\" in Jellyfin.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookRow(item: LibraryViewModel.Item, coverModel: Any?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(coverModel, size = 72.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Spacer(Modifier.height(6.dp))
            if (item.progressFraction > 0f && !item.isFinished) {
                LinearProgressIndicator(progress = { item.progressFraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progressLabel(item), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                DownloadBadge(item.download)
            }
        }
    }
}

private fun progressLabel(item: LibraryViewModel.Item): String = when {
    item.isFinished -> "Finished"
    item.positionMs > 0 -> "${formatDurationShort(item.book.durationMs - item.positionMs)} left"
    else -> formatDurationShort(item.book.durationMs)
}

@Composable
private fun DownloadBadge(download: DownloadInfo?) {
    when (download?.status) {
        DownloadStatus.COMPLETED -> Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
            CircularProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        DownloadStatus.FAILED -> Text("Download failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
fun Cover(model: Any?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape, modifier = modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FinishedIcon() {
    Icon(Icons.Filled.CheckCircle, contentDescription = "Finished", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
}
