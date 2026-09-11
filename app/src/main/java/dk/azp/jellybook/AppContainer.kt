package dk.azp.jellybook

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dk.azp.jellybook.data.BookRepository
import dk.azp.jellybook.data.Connectivity
import dk.azp.jellybook.data.bookmarks.BookmarkRepository
import dk.azp.jellybook.data.chapters.ChapterRepository
import dk.azp.jellybook.data.downloads.DownloadRepository
import dk.azp.jellybook.data.jellyfin.JellyfinAuthInterceptor
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.lists.BookListRepository
import dk.azp.jellybook.data.local.BookListStore
import dk.azp.jellybook.data.local.BookmarkStore
import dk.azp.jellybook.data.local.OfflineCatalog
import dk.azp.jellybook.data.local.ProgressStore
import dk.azp.jellybook.data.local.SessionStore
import dk.azp.jellybook.data.progress.ProgressRepository
import dk.azp.jellybook.playback.MediaItemFactory
import dk.azp.jellybook.playback.PlaybackConnection
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Hand-wired singletons; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val sessionStore = SessionStore(context)
    private val progressStore = ProgressStore(context)
    private val offlineCatalog = OfflineCatalog(context)
    private val bookmarkStore = BookmarkStore(context)
    private val bookListStore = BookListStore(context)
    val connectivity = Connectivity(context)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(JellyfinAuthInterceptor(sessionStore))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val client = JellyfinClient(httpClient, json)

    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val downloadCache: Cache = SimpleCache(File(context.filesDir, "media_cache"), NoOpCacheEvictor(), databaseProvider)

    /** Network (and file) access with Jellyfin authentication; used for downloads and artwork. */
    val upstreamDataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(httpClient))

    /** Reads downloaded bytes from the cache and streams the rest; never writes to the cache while streaming. */
    val playbackDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val downloadManager = DownloadManager(context, databaseProvider, downloadCache, upstreamDataSourceFactory, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
    }

    val mediaItemFactory = MediaItemFactory(client, sessionStore)
    val chapterRepository = ChapterRepository(playbackDataSourceFactory, offlineCatalog)
    val bookRepository = BookRepository(client, sessionStore)
    val progressRepository = ProgressRepository(client, bookRepository, sessionStore, progressStore, connectivity)
    val bookmarkRepository = BookmarkRepository(client, sessionStore, bookmarkStore, connectivity)
    val bookListRepository = BookListRepository(client, sessionStore, bookListStore, connectivity)
    val downloadRepository = DownloadRepository(context, downloadManager, offlineCatalog, client, sessionStore, httpClient, appScope)
    val playback = PlaybackConnection(context)

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(httpClient)) }
        .build()

    fun startBackgroundSync() {
        appScope.launch { connectivity.becameAvailable.collect { syncNow() } }
    }

    fun syncNow() {
        appScope.launch {
            progressRepository.flushPending()
            bookmarkRepository.flushPending()
            bookListRepository.flushPending()
        }
    }

    suspend fun signOut() {
        runCatching { progressRepository.flushPending() }
        sessionStore.clearSession()
    }
}
