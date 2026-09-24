package dk.azp.jellybook.data.downloads

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import dk.azp.jellybook.data.Connectivity
import dk.azp.jellybook.data.chapters.Chapter
import dk.azp.jellybook.data.downloads.NetworkPolicy.partIds
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DownloadStatus { QUEUED, DOWNLOADING, WAITING_FOR_WIFI, COMPLETED, FAILED, REMOVING }

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
 *
 * Each book also carries a network policy, see [NetworkPolicy]: one started on Wi-Fi pauses when the phone falls back to
 * mobile data and resumes by itself on Wi-Fi. Media3 keeps the download service running while it waits.
 */
class DownloadRepository(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val catalog: OfflineCatalog,
    private val client: JellyfinClient,
    private val sessionStore: SessionStore,
    private val httpClient: OkHttpClient,
    private val connectivity: Connectivity,
    scope: CoroutineScope,
) {

    private val partDownloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val pendingBooks = MutableStateFlow<Set<String>>(emptySet())
    private val notMetRequirements = MutableStateFlow(downloadManager.notMetRequirements)
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    val offlineBooks: Flow<List<Book>> = catalog.downloadedBooks.map { list -> list.sortedBy { it.title }.map { it.toBook() } }

    val downloads: StateFlow<Map<String, DownloadInfo>> =
        combine(partDownloads, catalog.downloadedBooks, pendingBooks, notMetRequirements) { parts, books, pending, notMet ->
            books.mapNotNull { book -> aggregate(book, parts, pending, notMet)?.let { book.itemId to it } }.toMap()
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val completedPartIds: Flow<Set<String>> =
        partDownloads.map { parts -> parts.filterValues { it.state == Download.STATE_COMPLETED }.keys }.distinctUntilChanged()

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                partDownloads.update { it + (download.request.id to download) }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                partDownloads.update { it - download.request.id }
            }

            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMet: Int) {
                notMetRequirements.value = notMet
            }
        })
        scope.launch(Dispatchers.IO) { loadExistingDownloads() }
        scope.launch {
            combine(catalog.downloadedBooks, completedPartIds, connectivity.metered) { books, completed, metered ->
                NetworkPolicy.decide(books, completed, metered)
            }.distinctUntilChanged().collect { applyPolicy(it) }
        }
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

    /** Whether starting a download now would spend mobile data, so the user should be asked first. */
    fun wouldUseMeteredNetwork(): Boolean = connectivity.isMetered()

    fun isOnMobileData(): Boolean = connectivity.isCellular()

    /**
     * Queues every part of [book]. With [allowMetered] false the download only runs on Wi-Fi, waiting for it if need be;
     * true is for when the user agreed to use mobile data.
     */
    suspend fun startDownload(book: Book, chapters: List<Chapter>, allowMetered: Boolean) {
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
                releaseDateEpochMs = book.releaseDateEpochMs,
                partList = book.parts.map { it.toDownloadedPart() },
                chapters = chapters,
                coverPath = coverPath,
                downloadedAtEpochMs = System.currentTimeMillis(),
                allowMetered = allowMetered,
            ),
        )
        pendingBooks.update { it + book.id }
        // The requirement has to be in place before the parts are queued, or a Wi-Fi-only part could start on mobile data.
        val decision = NetworkPolicy.decide(catalog.downloadedBooks.first(), completedIds(), connectivity.isMetered())
        applyPolicy(decision)
        val initialStopReason = if (!allowMetered && decision.holdBackWifiOnly) STOP_REASON_WAITING_FOR_WIFI else Download.STOP_REASON_NONE
        for (part in book.parts) {
            val request = DownloadRequest.Builder(part.itemId, Uri.parse(client.fileUrl(session.serverUrl, part.itemId)))
                .setCustomCacheKey(part.itemId)
                .build()
            DownloadService.sendAddDownload(context, BookDownloadService::class.java, request, initialStopReason, true)
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

    private fun completedIds(): Set<String> = partDownloads.value.filterValues { it.state == Download.STATE_COMPLETED }.keys

    /** Sets the shared network requirement and holds back or releases the Wi-Fi-only parts. Must run on the main thread. */
    private suspend fun applyPolicy(decision: NetworkPolicy.Decision) = withContext(Dispatchers.Main.immediate) {
        val requirements = Requirements(if (decision.requireUnmetered) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        if (downloadManager.requirements != requirements) downloadManager.requirements = requirements
        val reason = if (decision.holdBackWifiOnly) STOP_REASON_WAITING_FOR_WIFI else Download.STOP_REASON_NONE
        for (partId in decision.wifiOnlyPartIds) {
            if (partDownloads.value[partId]?.stopReason != reason) downloadManager.setStopReason(partId, reason)
        }
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

    private fun aggregate(book: DownloadedBook, parts: Map<String, Download>, pending: Set<String>, notMet: Int): DownloadInfo? {
        val partIds = book.partIds()
        val known = partIds.mapNotNull { parts[it] }
        if (known.isEmpty()) {
            return if (book.itemId in pending) DownloadInfo(book.itemId, DownloadStatus.QUEUED, 0f, 0L) else null
        }
        val states = known.map { it.state }
        val heldBack = known.any { it.state == Download.STATE_STOPPED && it.stopReason == STOP_REASON_WAITING_FOR_WIFI }
        val awaitingWifi = notMet and Requirements.NETWORK_UNMETERED != 0 && states.any { it == Download.STATE_QUEUED }
        val status = when {
            states.any { it == Download.STATE_FAILED } -> DownloadStatus.FAILED
            states.any { it == Download.STATE_REMOVING } -> DownloadStatus.REMOVING
            known.size == partIds.size && states.all { it == Download.STATE_COMPLETED } -> DownloadStatus.COMPLETED
            states.any { it == Download.STATE_DOWNLOADING } -> DownloadStatus.DOWNLOADING
            heldBack || awaitingWifi -> DownloadStatus.WAITING_FOR_WIFI
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

        /** Stop reason for a Wi-Fi-only part held back while another book downloads over mobile data. */
        const val STOP_REASON_WAITING_FOR_WIFI = 1
    }
}
