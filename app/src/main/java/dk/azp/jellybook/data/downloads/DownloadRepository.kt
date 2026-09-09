package dk.azp.jellybook.data.downloads

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.data.jellyfin.JellyfinClient
import dk.azp.jellybook.data.local.DownloadedBook
import dk.azp.jellybook.data.local.OfflineCatalog
import dk.azp.jellybook.data.local.ServerSession
import dk.azp.jellybook.data.local.SessionStore
import dk.azp.jellybook.data.model.Book
import dk.azp.jellybook.data.model.toBook
import dk.azp.jellybook.playback.BookDownloadService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, REMOVING }

data class DownloadInfo(
    val itemId: String,
    val status: DownloadStatus,
    val percent: Float,
    val bytesDownloaded: Long,
)

/**
 * Offline copies of audiobooks. The audio goes into the Media3 download cache the player reads from; the metadata needed to
 * list and play the book without the server goes into the offline catalogue.
 */
class DownloadRepository(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val catalog: OfflineCatalog,
    private val client: JellyfinClient,
    private val sessionStore: SessionStore,
    private val httpClient: OkHttpClient,
    scope: CoroutineScope,
) {

    private val downloadsFlow = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    val downloads: StateFlow<Map<String, DownloadInfo>> = downloadsFlow

    val offlineBooks: Flow<List<Book>> = catalog.downloadedBooks.map { list -> list.sortedBy { it.title }.map { it.toBook() } }

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                publish(download)
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                downloadsFlow.update { it - download.request.id }
            }
        })
        scope.launch(Dispatchers.IO) { loadExistingDownloads() }
        scope.launch {
            while (isActive) {
                val active = downloadsFlow.value.values.any { it.status == DownloadStatus.DOWNLOADING }
                if (active) downloadManager.currentDownloads.forEach(::publish)
                delay(if (active) ACTIVE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    fun isDownloaded(itemId: String): Boolean = downloadsFlow.value[itemId]?.status == DownloadStatus.COMPLETED

    fun coverFile(itemId: String): File? = File(coversDir, "$itemId.jpg").takeIf { it.exists() }

    suspend fun downloadedBook(itemId: String): Book? = catalog.downloadedBook(itemId)?.toBook()

    suspend fun startDownload(book: Book, chapters: List<Chapter>) {
        val session = sessionStore.currentSession() ?: return
        val coverPath = downloadCover(session, book)
        catalog.put(
            DownloadedBook(
                itemId = book.id,
                title = book.title,
                author = book.author,
                overview = book.overview,
                durationMs = book.durationMs,
                imageTag = book.imageTag,
                mediaSourceId = book.mediaSourceId,
                container = book.container,
                productionYear = book.productionYear,
                chapters = chapters,
                coverPath = coverPath,
                downloadedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val request = DownloadRequest.Builder(book.id, Uri.parse(client.fileUrl(session.serverUrl, book.id)))
            .setCustomCacheKey(book.id)
            .build()
        downloadsFlow.update { it + (book.id to DownloadInfo(book.id, DownloadStatus.QUEUED, 0f, 0L)) }
        DownloadService.sendAddDownload(context, BookDownloadService::class.java, request, true)
    }

    suspend fun removeDownload(itemId: String) {
        DownloadService.sendRemoveDownload(context, BookDownloadService::class.java, itemId, false)
        catalog.downloadedBook(itemId)?.coverPath?.let { File(it).delete() }
        catalog.remove(itemId)
        downloadsFlow.update { it - itemId }
    }

    private fun loadExistingDownloads() {
        try {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) publish(cursor.download)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read download index", e)
        }
    }

    private fun publish(download: Download) {
        downloadsFlow.update { it + (download.request.id to download.toInfo()) }
    }

    private fun Download.toInfo(): DownloadInfo = DownloadInfo(
        itemId = request.id,
        status = when (state) {
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_REMOVING -> DownloadStatus.REMOVING
            else -> DownloadStatus.QUEUED
        },
        percent = percentDownloaded.coerceAtLeast(0f),
        bytesDownloaded = bytesDownloaded,
    )

    private suspend fun downloadCover(session: ServerSession, book: Book): String? = withContext(Dispatchers.IO) {
        val tag = book.imageTag ?: return@withContext null
        val target = File(coversDir, "${book.id}.jpg")
        try {
            val request = Request.Builder().url(client.primaryImageUrl(session.serverUrl, book.id, tag, COVER_HEIGHT_PX)).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@withContext null
                target.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            target.absolutePath
        } catch (e: IOException) {
            Log.w(TAG, "Cover download failed for ${book.title}", e)
            null
        }
    }

    private companion object {
        const val TAG = "DownloadRepository"
        const val ACTIVE_POLL_MS = 1_000L
        const val IDLE_POLL_MS = 4_000L
        const val COVER_HEIGHT_PX = 800
    }
}
