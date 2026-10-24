package dev.wplayer.app.data.transcription

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
import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.data.toHex
import dev.wplayer.app.di.IoDispatcher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads a whisper model (and the voice activity model, if missing) from
 * Hugging Face. An interrupted download continues from its partial file;
 * the finished file must match its known SHA-256.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: WhisperModelStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val model = inputData.getString(KEY_MODEL)?.let { name -> WhisperModel.entries.firstOrNull { it.name == name } }
            ?: return Result.failure()
        return try {
            setForeground(foregroundInfo(model, null))
            withContext(io) {
                store.directory.mkdirs()
                if (store.vadFile.length() != WhisperModelStore.VAD_SIZE_BYTES) {
                    fetch(WhisperModelStore.VAD_URL, store.vadFile, WhisperModelStore.VAD_SIZE_BYTES, WhisperModelStore.VAD_SHA256) { }
                }
                fetch(WhisperModelStore.url(model), store.file(model), model.sizeBytes, model.sha256) { fraction ->
                    setProgress(workDataOf(KEY_FRACTION to fraction))
                    notifications.notify(NOTIFICATION_ID, notification(model, fraction))
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(e.message ?: "I/O error")
        } catch (e: Exception) {
            failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failure(reason: String) = Result.failure(workDataOf(KEY_ERROR to reason))

    /** Downloads [url] to [target] via a partial file, continuing an earlier attempt. */
    private suspend fun fetch(url: String, target: File, size: Long, sha256: String, onProgress: suspend (Float) -> Unit) {
        if (target.length() == size) return
        val partial = File(target.path + PARTIAL_SUFFIX)
        var offset = partial.length().takeIf { it < size } ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> offset = 0 // The server ignored the range: start over.
                else -> throw IOException("HTTP ${connection.responseCode} for $url")
            }
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, offset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (copied - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = copied
                            onProgress(copied.toFloat() / size)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (partial.length() != size || sha256Of(partial) != sha256) {
            partial.delete()
            throw IOException("${target.name} did not download correctly")
        }
        if (!partial.renameTo(target)) throw IOException("cannot save ${target.name}")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun foregroundInfo(model: WhisperModel, fraction: Float?) =
        ForegroundInfo(NOTIFICATION_ID, notification(model, fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(model: WhisperModel, fraction: Float?) =
        NotificationCompat.Builder(applicationContext, ensureAiChannel(applicationContext))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.ai_model_downloading))
            .setContentText(model.fileName)
            .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_FRACTION = "fraction"
        const val KEY_ERROR = "error"
        const val PARTIAL_SUFFIX = ".part"
        private const val NOTIFICATION_ID = 0x4149_0001
        private const val TIMEOUT_MILLIS = 30_000
        private const val BUFFER_BYTES = 256 * 1024
        private const val REPORT_EVERY_BYTES = 4L * 1024 * 1024
        private const val PROGRESS_MAX = 1000
        private const val MAX_RETRIES = 5
    }
}
