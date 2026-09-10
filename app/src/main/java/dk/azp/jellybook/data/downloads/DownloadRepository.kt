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
import dk.azp.jellybook.data.model.toDownloadedPart
import dk.azp.jellybook.playback.BookDownloadService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, REMOVING }

data class DownloadInfo(
    val bookId: String,
    val status: DownloadStatus,
    val percent: Float,
    val bytesDownloaded: Long,
)

/**
 * Offline copies of audiobooks. The audio goes into the Media3 download cache the player reads from; the metadata needed to
 * list and play the book without the server goes into the offline catalogue. A multi-file book downloads as one job per
 * part, reported to the UI as a single aggregate state.
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

    private val partDownloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val pendingBooks = MutableStateFlow<Set<String>>(emptySet())
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    val offlineBooks: Flow<List<Book>> = catalog.downloadedBooks.map { list -> list.sortedBy { it.title }.map { it.toBook() } }

    val downloads: StateFlow<Map<String, DownloadInfo>> =
        combine(partDownloads, catalog.downloadedBooks, pendingBooks) { parts, books, pending ->
            books.mapNotNull { book -> aggregate(book, parts, pending)?.let { book.itemId to it } }.toMap()
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                partDownloads.update { it + (download.request.id to download) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                partDownloads.update { it - download.request.id }
            }
        })
        scope.launch(Dispatchers.IO) { loadExistingDownloads() }
        scope.launch {
            while (isActive) {
                val active = downloadManager.currentDownloads
                if (active.isNotEmpty()) {
                    partDownloads.update { current -> current + active.associateBy { it.request.id } }
                }
                delay(if (active.isNotEmpty()) ACTIVE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    fun isDownloaded(bookId: String): Boolean = downloads.value[bookId]?.status == DownloadStatus.COMPLETED

    fun coverFile(bookId: String): File? = File(coversDir, "$bookId.jpg").takeIf { it.exists() }

    suspend fun downloadedBook(bookId: String): Book? = catalog.downloadedBook(bookId)?.toBook()

    suspend fun startDownload(book: Book, chapters: List<Chapter>) {
        val session = sessionStore.currentSession() ?: return
        val coverPath = downloadCover(session, book)
        catalog.put(
            DownloadedBook(
                itemId = book.id,
                title = book.title,
                author = book.author,
                overview = book.overview,
                imageTag = book.imageTag,
                imageItemId = book.imageItemId,
                productionYear = book.productionYear,
                partList = book.parts.map { it.toDownloadedPart() },
                chapters = chapters,
                coverPath = coverPath,
                downloadedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        pendingBooks.update { it + book.id }
        for (part in book.parts) {
            val request = DownloadRequest.Builder(part.itemId, Uri.parse(client.fileUrl(session.serverUrl, part.itemId)))
                .setCustomCacheKey(part.itemId)
                .build()
            DownloadService.sendAddDownload(context, BookDownloadService::class.java, request, true)
        }
    }

    suspend fun removeDownload(bookId: String) {
        val stored = catalog.downloadedBook(bookId)
        val partIds = stored?.partList?.map { it.itemId } ?: listOf(bookId)
        for (partId in partIds) {
            DownloadService.sendRemoveDownload(context, BookDownloadService::class.java, partId, false)
        }
        stored?.coverPath?.let { File(it).delete() }
        catalog.remove(bookId)
        pendingBooks.update { it - bookId }
        partDownloads.update { current -> current - partIds.toSet() }
    }

    private fun loadExistingDownloads() {
        try {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                val found = mutableMapOf<String, Download>()
                while (cursor.moveToNext()) found[cursor.download.request.id] = cursor.download
                partDownloads.update { it + found }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read download index", e)
        }
    }

    private fun aggregate(book: DownloadedBook, parts: Map<String, Download>, pending: Set<String>): DownloadInfo? {
        val partIds = book.partList.map { it.itemId }.ifEmpty { listOf(book.itemId) }
        val known = partIds.mapNotNull { parts[it] }
        if (known.isEmpty()) {
            return if (book.itemId in pending) DownloadInfo(book.itemId, DownloadStatus.QUEUED, 0f, 0L) else null
        }
        val states = known.map { it.state }
        val status = when {
            states.any { it == Download.STATE_FAILED } -> DownloadStatus.FAILED
            states.any { it == Download.STATE_REMOVING } -> DownloadStatus.REMOVING
            known.size == partIds.size && states.all { it == Download.STATE_COMPLETED } -> DownloadStatus.COMPLETED
            states.any { it == Download.STATE_DOWNLOADING } -> DownloadStatus.DOWNLOADING
            else -> DownloadStatus.QUEUED
        }
        val completed = states.count { it == Download.STATE_COMPLETED }
        val inFlight = known.sumOf { it.percentDownloaded.coerceIn(0f, 100f).toDouble() } - completed * 100.0
        val percent = ((completed * 100.0 + inFlight.coerceAtLeast(0.0)) / partIds.size).toFloat().coerceIn(0f, 100f)
        return DownloadInfo(book.itemId, status, percent, known.sumOf { it.bytesDownloaded })
    }

    private suspend fun downloadCover(session: ServerSession, book: Book): String? = withContext(Dispatchers.IO) {
        val tag = book.imageTag ?: return@withContext null
        val target = File(coversDir, "${book.id}.jpg")
        try {
            val request = Request.Builder().url(client.primaryImageUrl(session.serverUrl, book.imageItemId, tag, COVER_HEIGHT_PX)).build()
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
