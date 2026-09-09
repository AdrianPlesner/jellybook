package dk.azp.jellybook.data.jellyfin

import android.os.Build
import dk.azp.jellybook.data.local.SessionStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class JellyfinException(val statusCode: Int, message: String) : IOException(message)

/**
 * Adds the Jellyfin "MediaBrowser" authorization header to every request. The token is read from the session store on the
 * OkHttp worker thread so that the same client can serve the API, ExoPlayer and Coil.
 */
class JellyfinAuthInterceptor(private val sessionStore: SessionStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val (token, deviceId) = runBlocking { sessionStore.currentSession()?.accessToken to sessionStore.deviceId() }
        val request = chain.request().newBuilder()
            .header("Authorization", authorizationHeader(deviceId, token))
            .build()
        return chain.proceed(request)
    }

    private fun authorizationHeader(deviceId: String, token: String?): String {
        val device = (Build.MODEL ?: "Android").replace("\"", "")
        val header = StringBuilder("MediaBrowser Client=\"$CLIENT_NAME\", Device=\"$device\", DeviceId=\"$deviceId\", Version=\"$CLIENT_VERSION\"")
        if (!token.isNullOrBlank()) {
            header.append(", Token=\"").append(token).append('"')
        }
        return header.toString()
    }

    private companion object {
        const val CLIENT_NAME = "Jellybook"
        const val CLIENT_VERSION = "0.1.0"
    }
}

class JellyfinClient(private val httpClient: OkHttpClient, private val json: Json) {

    suspend fun publicSystemInfo(serverUrl: String): PublicSystemInfo =
        get(url(serverUrl, "System/Info/Public"))

    suspend fun authenticateByName(serverUrl: String, username: String, password: String): AuthenticationResult =
        post(url(serverUrl, "Users/AuthenticateByName"), json.encodeToString(AuthenticateUserByName(username, password)))

    /** The user's libraries. A books library is the one that holds audiobooks. */
    suspend fun userViews(serverUrl: String, userId: String): List<BaseItemDto> =
        get<BaseItemDtoQueryResult>(url(serverUrl, "UserViews") { addQueryParameter("userId", userId) }).items

    /**
     * Direct children of a folder. Walking the library one level at a time is what tells a folder that *is* a book (its
     * children are audio files) apart from a folder that merely *contains* books.
     */
    suspend fun children(serverUrl: String, userId: String, parentId: String): List<BaseItemDto> {
        val url = url(serverUrl, "Items") {
            addQueryParameter("userId", userId)
            addQueryParameter("parentId", parentId)
            addQueryParameter("sortBy", "SortName")
            addQueryParameter("sortOrder", "Ascending")
            addQueryParameter("fields", "Overview,ProductionYear,ChildCount,Chapters")
            addQueryParameter("enableImageTypes", "Primary")
            addQueryParameter("imageTypeLimit", "1")
        }
        return get<BaseItemDtoQueryResult>(url).items
    }

    suspend fun item(serverUrl: String, userId: String, itemId: String): BaseItemDto =
        get(
            url(serverUrl, "Items/$itemId") {
                addQueryParameter("userId", userId)
                addQueryParameter("fields", "Overview,MediaSources,ProductionYear,ChildCount,Chapters")
            },
        )

    suspend fun reportPlaybackStart(serverUrl: String, info: PlaybackStartInfo) {
        postNoContent(url(serverUrl, "Sessions/Playing"), json.encodeToString(info))
    }

    suspend fun reportPlaybackProgress(serverUrl: String, info: PlaybackStartInfo) {
        postNoContent(url(serverUrl, "Sessions/Playing/Progress"), json.encodeToString(info))
    }

    suspend fun reportPlaybackStopped(serverUrl: String, info: PlaybackStopInfo) {
        postNoContent(url(serverUrl, "Sessions/Playing/Stopped"), json.encodeToString(info))
    }

    /**
     * Writes user data verbatim, bypassing Jellyfin's resume heuristics. Returns null on servers older than 10.10 that lack
     * the endpoint.
     */
    suspend fun updateUserData(serverUrl: String, userId: String, itemId: String, update: UpdateUserItemDataDto): UserItemDataDto? {
        val url = url(serverUrl, "UserItems/$itemId/UserData") { addQueryParameter("userId", userId) }
        return try {
            post(url, json.encodeToString(update))
        } catch (e: JellyfinException) {
            if (e.statusCode == 404 || e.statusCode == 405) null else throw e
        }
    }

    /** Per-user, per-client key/value storage that Jellyfin offers every client; used to sync bookmarks without a plugin. */
    suspend fun displayPreferences(serverUrl: String, userId: String): JsonObject =
        get(displayPreferencesUrl(serverUrl, userId))

    suspend fun updateDisplayPreferences(serverUrl: String, userId: String, preferences: JsonObject) {
        postNoContent(displayPreferencesUrl(serverUrl, userId), json.encodeToString(JsonObject.serializer(), preferences))
    }

    /** The original file with HTTP range support; stable across sessions so it doubles as the offline cache key. */
    fun fileUrl(serverUrl: String, itemId: String): String = url(serverUrl, "Items/$itemId/File").toString()

    fun primaryImageUrl(serverUrl: String, itemId: String, tag: String?, maxHeight: Int): String =
        url(serverUrl, "Items/$itemId/Images/Primary") {
            addQueryParameter("maxHeight", maxHeight.toString())
            addQueryParameter("quality", "90")
            if (tag != null) addQueryParameter("tag", tag)
        }.toString()

    private suspend inline fun <reified T> get(url: HttpUrl): T = execute(Request.Builder().url(url).get().build()) { body ->
        json.decodeFromString<T>(body)
    }

    private suspend inline fun <reified T> post(url: HttpUrl, body: String): T =
        execute(Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()) { responseBody ->
            json.decodeFromString<T>(responseBody)
        }

    private suspend fun postNoContent(url: HttpUrl, body: String) {
        execute(Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()) { }
    }

    private suspend fun <T> execute(request: Request, transform: (String) -> T): T = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw JellyfinException(response.code, "HTTP ${response.code} from ${request.url.encodedPath}: ${body.take(200)}")
            }
            transform(body)
        }
    }

    private fun displayPreferencesUrl(serverUrl: String, userId: String): HttpUrl =
        url(serverUrl, "DisplayPreferences/$DISPLAY_PREFERENCES_ID") {
            addQueryParameter("userId", userId)
            addQueryParameter("client", DISPLAY_PREFERENCES_CLIENT)
        }

    private fun url(serverUrl: String, path: String, configure: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val base = normalizeServerUrl(serverUrl).toHttpUrlOrNull() ?: throw JellyfinException(0, "Invalid server URL: $serverUrl")
        return base.newBuilder().addPathSegments(path).apply(configure).build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DISPLAY_PREFERENCES_ID = "jellybook"
        private const val DISPLAY_PREFERENCES_CLIENT = "jellybook"

        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
