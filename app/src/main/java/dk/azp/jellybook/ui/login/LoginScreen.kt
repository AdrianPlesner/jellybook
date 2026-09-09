package dk.azp.jellybook.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.azp.jellybook.AppContainer
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.jellyfin.JellyfinException
import dk.azp.jellybook.data.local.ServerSession
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val isBusy: Boolean = false,
        val error: String? = null,
    )

    private val stateFlow = MutableStateFlow(State())

    val state: StateFlow<State> = stateFlow

    init {
        viewModelScope.launch {
            container.sessionStore.lastServerUrl()?.let { url -> stateFlow.update { it.copy(serverUrl = url) } }
        }
    }

    fun onServerUrlChange(value: String) = stateFlow.update { it.copy(serverUrl = value, error = null) }

    fun onUsernameChange(value: String) = stateFlow.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = stateFlow.update { it.copy(password = value, error = null) }

    fun signIn() {
        val current = stateFlow.value
        if (current.isBusy) return
        if (current.serverUrl.isBlank() || current.username.isBlank()) {
            stateFlow.update { it.copy(error = "Server address and username are required") }
            return
        }
        viewModelScope.launch {
            stateFlow.update { it.copy(isBusy = true, error = null) }
            try {
                val serverUrl = JellyfinClient.normalizeServerUrl(current.serverUrl)
                val info = container.client.publicSystemInfo(serverUrl)
                val auth = container.client.authenticateByName(serverUrl, current.username.trim(), current.password)
                container.sessionStore.saveSession(
                    ServerSession(
                        serverUrl = serverUrl,
                        accessToken = auth.accessToken,
                        userId = auth.user.id,
                        userName = auth.user.name ?: current.username.trim(),
                        serverName = info.serverName ?: "Jellyfin",
                    ),
                )
            } catch (e: JellyfinException) {
                val message = if (e.statusCode == 401) "Wrong username or password" else "Server rejected the request (${e.statusCode})"
                stateFlow.update { it.copy(error = message) }
            } catch (e: SerializationException) {
                stateFlow.update { it.copy(error = "Unexpected reply. Is this the address of a Jellyfin server?") }
            } catch (e: IOException) {
                stateFlow.update { it.copy(error = "Could not reach the server: ${e.message ?: "network error"}") }
            } finally {
                stateFlow.update { it.copy(isBusy = false) }
            }
        }
    }
}

@Composable
fun LoginScreen(container: AppContainer) {
    val viewModel: LoginViewModel = viewModel { LoginViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("Jellybook", style = MaterialTheme.typography.headlineMedium)
            Text("Audiobooks from your Jellyfin server", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server address") },
                placeholder = { Text("https://jellyfin.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = viewModel::signIn, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}
