package dk.azp.jellybook.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dk.azp.jellybook.data.local.BookList
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

    data class State(
        val sections: List<LibrarySection> = emptyList(),
        val visibleCount: Int = 0,
        val isLoading: Boolean = true,
        val isOffline: Boolean = false,
        val error: String? = null,
        val query: String = "",
        val session: ServerSession? = null,
        val debugMode: Boolean = false,
        val sort: LibrarySort = LibrarySort.RECENT,
        val grouping: LibraryGrouping = LibraryGrouping.NONE,
        val lists: List<BookList> = emptyList(),
        val selectedList: BookList? = null,
    )

    private class Meta(
        val isLoading: Boolean,
        val isOffline: Boolean,
        val error: String?,
        val session: ServerSession?,
        val debugMode: Boolean,
    )

    private class ViewPreferences(val sort: LibrarySort, val grouping: LibraryGrouping, val selectedListId: String?)

    private class Surroundings(val meta: Meta, val view: ViewPreferences, val lists: List<BookList>)

    private val books = MutableStateFlow<List<Book>>(emptyList())
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val offline = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val meta = combine(
        loading,
        offline,
        error,
        container.sessionStore.session,
        container.sessionStore.debugMode,
    ) { isLoading, isOffline, message, session, debugMode ->
        Meta(isLoading, isOffline, message, session, debugMode)
    }

    private val viewPreferences = combine(
        container.sessionStore.librarySort,
        container.sessionStore.libraryGrouping,
        container.sessionStore.selectedListId,
    ) { sort, grouping, listId ->
        ViewPreferences(
            sort = LibrarySort.entries.firstOrNull { it.name == sort } ?: LibrarySort.RECENT,
            grouping = LibraryGrouping.entries.firstOrNull { it.name == grouping } ?: LibraryGrouping.NONE,
            selectedListId = listId,
        )
    }

    private val surroundings = combine(meta, viewPreferences, container.bookListRepository.lists) { info, view, lists ->
        Surroundings(info, view, lists)
    }

    val state: StateFlow<State> = combine(
        books,
        query,
        container.progressRepository.localProgress,
        container.downloadRepository.downloads,
        surroundings,
    ) { bookList, search, progress, downloads, around ->
        val selected = around.lists.firstOrNull { it.id == around.view.selectedListId }
        val entries = bookList
            .filter { book -> selected == null || book.id in selected.bookIds }
            .filter { book -> matches(book, search) }
            .map { book -> toEntry(book, progress[book.id], downloads[book.id]) }
        State(
            sections = arrange(entries, around.view.sort, around.view.grouping),
            visibleCount = entries.size,
            isLoading = around.meta.isLoading,
            isOffline = around.meta.isOffline,
            error = around.meta.error,
            query = search,
            session = around.meta.session,
            debugMode = around.meta.debugMode,
            sort = around.view.sort,
            grouping = around.view.grouping,
            lists = around.lists,
            selectedList = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    init {
        refresh()
        viewModelScope.launch { container.bookListRepository.sync() }
    }

    fun search(text: String) {
        query.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            try {
                val loaded = container.bookRepository.library()
                books.value = loaded
                offline.value = false
                // Only when the whole library is in hand: offline the list is just the downloads, which would prune the rest.
                container.bookListRepository.forgetMissingBooks(loaded.map { it.id }.toSet())
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

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { container.sessionStore.setDebugMode(enabled) }
    }

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { container.sessionStore.setLibrarySort(sort.name) }
    }

    fun setGrouping(grouping: LibraryGrouping) {
        viewModelScope.launch { container.sessionStore.setLibraryGrouping(grouping.name) }
    }

    fun selectList(listId: String?) {
        viewModelScope.launch { container.sessionStore.setSelectedListId(listId) }
    }

    fun createList(name: String) {
        viewModelScope.launch {
            val created = container.bookListRepository.create(name)
            container.sessionStore.setSelectedListId(created.id)
        }
    }

    fun renameSelectedList(name: String) {
        val listId = state.value.selectedList?.id ?: return
        viewModelScope.launch { container.bookListRepository.rename(listId, name) }
    }

    fun deleteSelectedList() {
        val listId = state.value.selectedList?.id ?: return
        viewModelScope.launch {
            container.bookListRepository.delete(listId)
            container.sessionStore.setSelectedListId(null)
        }
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

    private fun toEntry(book: Book, local: LocalProgress?, download: DownloadInfo?): LibraryEntry {
        val remote = book.remoteProgress()
        val position = local?.positionMs ?: remote?.effectivePositionMs(book.durationMs) ?: 0L
        val finishedRemotely = remote != null && remote.played && remote.positionMs == 0L
        val nearEnd = book.durationMs > 0 && position >= book.durationMs - END_TOLERANCE_MS
        val finished = local?.finished == true || (local == null && finishedRemotely) || nearEnd
        val fraction = if (book.durationMs > 0) (position.toFloat() / book.durationMs).coerceIn(0f, 1f) else 0f
        return LibraryEntry(book, position, fraction, finished, local?.updatedAtEpochMs ?: remote?.lastPlayedEpochMs, download)
    }

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
    var sortMenuOpen by remember { mutableStateOf(false) }
    var newListOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Changing the order or the filter rebuilds the list, so start at the top rather than at the old offset.
    LaunchedEffect(state.sort, state.grouping, state.selectedList?.id) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.selectedList?.name ?: "Audiobooks")
                        val subtitle = state.selectedList
                            ?.let { list -> if (list.bookIds.size == 1) "1 book in this list" else "${list.bookIds.size} books in this list" }
                            ?: state.session?.let { session ->
                                listOf(session.serverName, session.userName).filter { it.isNotBlank() }.joinToString(" · ")
                            }
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort and group")
                    }
                    SortMenu(
                        expanded = sortMenuOpen,
                        sort = state.sort,
                        grouping = state.grouping,
                        onDismiss = { sortMenuOpen = false },
                        onSort = viewModel::setSort,
                        onGrouping = viewModel::setGrouping,
                    )
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (state.selectedList != null) {
                            DropdownMenuItem(
                                text = { Text("Rename list") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    renameOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete list") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.deleteSelectedList()
                                },
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text("Sync progress now") },
                            leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.syncNow()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.debugMode) "Debug mode: on" else "Debug mode: off") },
                            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                            trailingIcon = { if (state.debugMode) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.setDebugMode(!state.debugMode)
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
            ListChips(
                lists = state.lists,
                selectedId = state.selectedList?.id,
                onSelect = viewModel::selectList,
                onNewList = { newListOpen = true },
            )
            when {
                state.isLoading && state.sections.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.sections.isEmpty() -> ErrorState(state.error ?: "", onRetry = viewModel::refresh)
                state.sections.isEmpty() -> EmptyState(state.query.isNotBlank(), state.selectedList)
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    state.sections.forEach { section ->
                        section.title?.let { title ->
                            item(key = "header-$title") { SectionHeader(title, section.entries.size) }
                        }
                        items(section.entries, key = { it.book.id }) { entry ->
                            BookRow(
                                entry = entry,
                                coverModel = coverModel(container, state.session, entry.book),
                                onClick = { onOpenBook(entry.book.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (newListOpen) {
        ListNameDialog(
            title = "New list",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = {
                viewModel.createList(it)
                newListOpen = false
            },
            onDismiss = { newListOpen = false },
        )
    }
    if (renameOpen) {
        ListNameDialog(
            title = "Rename list",
            initialName = state.selectedList?.name.orEmpty(),
            confirmLabel = "Rename",
            onConfirm = {
                viewModel.renameSelectedList(it)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
        )
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    sort: LibrarySort,
    grouping: LibraryGrouping,
    onDismiss: () -> Unit,
    onSort: (LibrarySort) -> Unit,
    onGrouping: (LibraryGrouping) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuHeading("Sort by")
        LibrarySort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                trailingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    onSort(option)
                    onDismiss()
                },
            )
        }
        HorizontalDivider()
        MenuHeading("Group by")
        LibraryGrouping.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                trailingIcon = { if (option == grouping) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    onGrouping(option)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ListChips(lists: List<BookList>, selectedId: String?, onSelect: (String?) -> Unit, onNewList: () -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item {
            FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text("All books") })
        }
        items(lists, key = { it.id }) { list ->
            FilterChip(
                selected = selectedId == list.id,
                onClick = { onSelect(if (selectedId == list.id) null else list.id) },
                label = { Text("${list.name} (${list.bookIds.size})") },
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onNewList,
                label = { Text("New list") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun coverModel(container: AppContainer, session: ServerSession?, book: Book): Any? {
    container.downloadRepository.coverFile(book.id)?.let { return it }
    val tag = book.imageTag ?: return null
    val serverUrl = session?.serverUrl ?: return null
    return container.client.primaryImageUrl(serverUrl, book.imageItemId, tag, COVER_HEIGHT_PX)
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
private fun EmptyState(isSearching: Boolean, selectedList: BookList?) {
    val message = when {
        isSearching -> "No books match your search."
        selectedList != null -> "This list is empty. Open a book and use \"Add to list\" to put it here."
        else -> "No audiobooks found. Add a library with content type \"Books\" in Jellyfin."
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookRow(entry: LibraryEntry, coverModel: Any?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(coverModel, size = 72.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.book.author?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            if (entry.progressFraction > 0f && !entry.isFinished) {
                LinearProgressIndicator(progress = { entry.progressFraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(progressLabel(entry), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.book.productionYear?.let { year ->
                    Text(" · $year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                DownloadBadge(entry.download)
            }
        }
    }
}

private fun progressLabel(entry: LibraryEntry): String = when {
    entry.isFinished -> "Finished"
    entry.positionMs > 0 -> "${formatDurationShort(entry.book.durationMs - entry.positionMs)} left"
    else -> formatDurationShort(entry.book.durationMs)
}

@Composable
private fun DownloadBadge(download: DownloadInfo?) {
    when (download?.status) {
        DownloadStatus.COMPLETED ->
            Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
            CircularProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        DownloadStatus.FAILED -> Text("Download failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
fun Cover(model: Any?, size: Dp, modifier: Modifier = Modifier) {
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
