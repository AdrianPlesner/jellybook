package dk.azp.jellybook.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dk.azp.jellybook.JellybookApp
import dk.azp.jellybook.MainActivity
import dk.azp.jellybook.R

class BookDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0,
) {

    override fun getDownloadManager(): DownloadManager = (application as JellybookApp).container.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val known = active.map { it.percentDownloaded }.filter { it >= 0f }
        val percent = if (known.isEmpty()) 0 else (known.sum() / known.size).toInt()
        val title = if (active.size == 1) "Downloading audiobook" else "Downloading ${active.size} audiobooks"
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$percent%")
            .setProgress(100, percent, known.isEmpty())
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "downloads"
    }
}
