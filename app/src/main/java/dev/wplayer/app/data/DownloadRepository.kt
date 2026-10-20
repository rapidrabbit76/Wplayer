package dev.wplayer.app.data

import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.wplayer.app.data.local.DownloadDao
import dev.wplayer.app.data.local.DownloadEntity
import dev.wplayer.app.data.model.Download
import dev.wplayer.app.data.model.DownloadStatus
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.di.IoDispatcher
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Downloads from network sources into `Download/Wplayer` on device storage.
 * Each download is one WorkManager job, so it survives the app being closed.
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val downloads: Flow<List<Download>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** The folder downloads are saved to; it shows up under device storage. */
    val folder: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)

    suspend fun enqueue(ref: MediaRef) {
        val dest = withContext(io) { uniqueDestination(ref.name) }
        val id = dao.insert(
            DownloadEntity(
                sourceKey = ref.sourceKey,
                path = ref.path,
                name = ref.name,
                destPath = dest.absolutePath,
                totalBytes = null,
                downloadedBytes = 0,
                status = DownloadStatus.QUEUED.name,
                error = null,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        schedule(id)
    }

    /** Starts a failed or cancelled download again, continuing what is already on disk. */
    suspend fun retry(id: Long) {
        dao.setStatus(id, DownloadStatus.QUEUED.name, null)
        schedule(id)
    }

    /** Stops a download and deletes its partial file. */
    suspend fun cancel(id: Long) {
        dao.setStatus(id, DownloadStatus.CANCELLED.name, null)
        workManager.cancelUniqueWork(workName(id))
        val download = dao.get(id) ?: return
        withContext(io) { File(download.destPath).delete() }
        dao.setProgress(id, 0, download.totalBytes)
    }

    /** Removes a download from the list; [deleteFile] also deletes what was downloaded. */
    suspend fun remove(id: Long, deleteFile: Boolean) {
        workManager.cancelUniqueWork(workName(id))
        val download = dao.get(id) ?: return
        if (deleteFile || download.status != DownloadStatus.DONE.name) {
            withContext(io) { File(download.destPath).delete() }
        }
        dao.delete(id)
    }

    suspend fun clearFinished() = dao.deleteWithStatus(DownloadStatus.DONE.name)

    private suspend fun schedule(id: Long) {
        val overCellular = settings.settings.first().network.allowCellular
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (overCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    /** `name`, or `name (2)` and so on when a file or another download already uses it. */
    private suspend fun uniqueDestination(name: String): File {
        val dir = folder.apply { mkdirs() }
        val taken = dao.destPaths().toSet()
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) name else "$base ($n)$extension") }
            .first { !it.exists() && it.absolutePath !in taken }
    }

    private fun DownloadEntity.toModel() = Download(
        id = id,
        ref = MediaRef(sourceKey, path, name, isDirectory = false),
        destPath = destPath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        status = DownloadStatus.entries.firstOrNull { it.name == status } ?: DownloadStatus.FAILED,
        error = error,
    )

    companion object {
        const val FOLDER_NAME = "Wplayer"
        private const val RETRY_DELAY_SECONDS = 15L

        internal fun workName(id: Long) = "download-$id"
    }
}
