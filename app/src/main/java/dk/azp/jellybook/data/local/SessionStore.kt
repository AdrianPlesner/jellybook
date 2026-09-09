package dk.azp.jellybook.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ServerSession(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverName: String,
)

/** The signed-in Jellyfin session, the stable device id and playback preferences. */
class SessionStore(context: Context) {

    private val dataStore = context.jellybookDataStore

    val session: Flow<ServerSession?> = dataStore.data.map { it.toSession() }

    val playbackSpeed: Flow<Float> = dataStore.data.map { it[PLAYBACK_SPEED] ?: 1f }

    suspend fun currentSession(): ServerSession? = dataStore.data.first().toSession()

    suspend fun deviceId(): String {
        val existing = dataStore.data.first()[DEVICE_ID]
        return existing ?: UUID.randomUUID().toString().also { generated ->
            dataStore.edit { prefs -> if (prefs[DEVICE_ID] == null) prefs[DEVICE_ID] = generated }
        }
    }

    suspend fun saveSession(session: ServerSession) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = session.serverUrl
            prefs[ACCESS_TOKEN] = session.accessToken
            prefs[USER_ID] = session.userId
            prefs[USER_NAME] = session.userName
            prefs[SERVER_NAME] = session.serverName
        }
    }

    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(USER_ID)
            prefs.remove(USER_NAME)
        }
    }

    suspend fun lastServerUrl(): String? = dataStore.data.first()[SERVER_URL]

    suspend fun savePlaybackSpeed(speed: Float) {
        dataStore.edit { prefs -> prefs[PLAYBACK_SPEED] = speed }
    }

    suspend fun currentPlaybackSpeed(): Float = dataStore.data.first()[PLAYBACK_SPEED] ?: 1f

    private fun Preferences.toSession(): ServerSession? {
        val serverUrl = this[SERVER_URL] ?: return null
        val token = this[ACCESS_TOKEN] ?: return null
        val userId = this[USER_ID] ?: return null
        return ServerSession(
            serverUrl = serverUrl,
            accessToken = token,
            userId = userId,
            userName = this[USER_NAME] ?: "",
            serverName = this[SERVER_NAME] ?: "",
        )
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    }
}
