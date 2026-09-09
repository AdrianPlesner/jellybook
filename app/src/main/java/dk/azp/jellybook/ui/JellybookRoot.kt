package dk.azp.jellybook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.data.local.ServerSession
import dk.azp.jellybook.ui.book.BookScreen
import dk.azp.jellybook.ui.library.LibraryScreen
import dk.azp.jellybook.ui.login.LoginScreen
import dk.azp.jellybook.ui.theme.JellybookTheme
import kotlinx.coroutines.launch

private sealed interface SessionUi {
    data object Loading : SessionUi

    data object SignedOut : SessionUi

    data class SignedIn(val session: ServerSession) : SessionUi
}

@Composable
fun JellybookRoot(container: AppContainer) {
    val sessionUi by produceState<SessionUi>(SessionUi.Loading) {
        container.sessionStore.session.collect { value = if (it == null) SessionUi.SignedOut else SessionUi.SignedIn(it) }
    }
    JellybookTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (sessionUi) {
                SessionUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                SessionUi.SignedOut -> LoginScreen(container)
                is SessionUi.SignedIn -> SignedInContent(container)
            }
        }
    }
}

@Composable
private fun SignedInContent(container: AppContainer) {
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var deferredConflictIds by remember { mutableStateOf(emptySet<String>()) }
    val conflicts by container.progressRepository.conflicts.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    BackHandler(enabled = openBookId != null) { openBookId = null }

    val bookId = openBookId
    if (bookId == null) {
        LibraryScreen(container, onOpenBook = { openBookId = it })
    } else {
        BookScreen(container, bookId, onBack = { openBookId = null })
    }

    conflicts.firstOrNull { it.itemId !in deferredConflictIds }?.let { conflict ->
        ProgressConflictDialog(
            conflict = conflict,
            onKeepLocal = { scope.launch { container.progressRepository.resolveConflict(conflict.itemId, keepLocal = true) } },
            onKeepServer = { scope.launch { container.progressRepository.resolveConflict(conflict.itemId, keepLocal = false) } },
            onLater = { deferredConflictIds = deferredConflictIds + conflict.itemId },
        )
    }
}
