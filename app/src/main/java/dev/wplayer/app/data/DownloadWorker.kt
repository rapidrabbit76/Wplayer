package dev.wplayer.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.wplayer.app.R
import dev.wplayer.app.data.local.DownloadDao
import dev.wplayer.app.data.local.DownloadEntity
import dev.wplayer.app.data.model.DownloadStatus
import dev.wplayer.app.di.IoDispatcher
import dev.wplayer.core.ffi.CoreException
import dev.wplayer.core.ffi.TransferListener
import dev.wplayer.core.ffi.downloadFile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one download in the foreground (with a progress notification) so it
 * keeps going while the app is in the background. Stopped by the system when
 * its network constraint is lost; it then continues from the partial file.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    private val sources: SourceRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_DOWNLOAD_ID, -1)
        val download = dao.get(id) ?: return Result.failure()
        if (download.status == DownloadStatus.CANCELLED.name || download.status == DownloadStatus.DONE.name) {
            return Result.success()
        }
        dao.setStatus(id, DownloadStatus.RUNNING.name, null)
        setForeground(foregroundInfo(download, progress = null))

        val copiedBytes = AtomicLong(download.downloadedBytes)
        val totalBytes = AtomicLong(download.totalBytes ?: UNKNOWN)
        return try {
            // A retry usually follows a dropped connection; the old session is gone with it.
            if (runAttemptCount > 0) sources.reconnect(download.sourceKey) else sources.ensureConnected(download.sourceKey)
            coroutineScope {
                // The native copy blocks; report its progress from here once a second.
                val reporter = launch {
                    while (isActive) {
                        delay(PROGRESS_INTERVAL_MS)
                        reportProgress(download, copiedBytes.get(), totalBytes.get())
                    }
                }
                withContext(io) {
                    downloadFile(
                        download.sourceKey,
                        download.path,
                        download.destPath,
                        object : TransferListener {
                            override fun onProgress(copied: ULong, total: ULong?): Boolean {
                                copiedBytes.set(copied.toLong())
                                total?.let { totalBytes.set(it.toLong()) }
                                // Returning false makes the copy stop with Cancelled.
                                return !isStopped
                            }
                        },
                    )
                }
                reporter.cancel()
            }
            dao.setProgress(id, copiedBytes.get(), totalBytes.get().takeIf { it != UNKNOWN })
            dao.setStatus(id, DownloadStatus.DONE.name, null)
            Result.success()
        } catch (e: CoreException.Cancelled) {
            continueLater(id)
        } catch (e: CancellationException) {
            continueLater(id)
            throw e
        } catch (e: CoreException) {
            // Wi-Fi dropping or the server restarting: wait (for the network constraint) and continue.
            val transient = e is CoreException.Connection || e is CoreException.Io
            if (transient && runAttemptCount < MAX_RETRIES) {
                continueLater(id)
            } else {
                fail(id, e.detailOrName())
            }
        } catch (e: Exception) {
            fail(id, e.reason())
        }
    }

    private suspend fun fail(id: Long, reason: String): Result {
        withContext(NonCancellable) { dao.setStatus(id, DownloadStatus.FAILED.name, reason) }
        return Result.failure()
    }

    private fun CoreException.detailOrName(): String = when (this) {
        is CoreException.Connection -> detail
        is CoreException.Io -> detail
        is CoreException.Unsupported -> detail
        is CoreException.NotFound -> path
        else -> javaClass.simpleName
    }

    /** Stopping cancels this coroutine, and the row must still leave RUNNING. */
    private suspend fun continueLater(id: Long): Result {
        withContext(NonCancellable) {
            if (dao.get(id)?.status == DownloadStatus.RUNNING.name) dao.setStatus(id, DownloadStatus.QUEUED.name, null)
        }
        return Result.retry()
    }

    private suspend fun reportProgress(download: DownloadEntity, copied: Long, total: Long) {
        val knownTotal = total.takeIf { it != UNKNOWN }
        dao.setProgress(download.id, copied, knownTotal)
        setProgress(workDataOf(KEY_COPIED to copied))
        val fraction = knownTotal?.takeIf { it > 0 }?.let { copied.toFloat() / it }
        notifications.notify(download.id.toInt(), notification(download, fraction))
    }

    private fun foregroundInfo(download: DownloadEntity, progress: Float?) = ForegroundInfo(
        download.id.toInt(),
        notification(download, progress),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun notification(download: DownloadEntity, progress: Float?) =
        NotificationCompat.Builder(applicationContext, ensureChannel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(download.name)
            .setContentText(applicationContext.getString(R.string.download_notification_running))
            .setProgress(PROGRESS_MAX, ((progress ?: 0f) * PROGRESS_MAX).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun ensureChannel(): String {
        if (notifications.getNotificationChannel(CHANNEL_ID) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return CHANNEL_ID
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_COPIED = "copied"
        private const val CHANNEL_ID = "downloads"
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val PROGRESS_MAX = 1000
        private const val UNKNOWN = -1L
        private const val MAX_RETRIES = 5
    }
}
