package dev.wplayer.app.data.transcription

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.wplayer.app.R
import dev.wplayer.app.data.BrowserRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.model.mediaKey
import dev.wplayer.app.di.IoDispatcher
import dev.wplayer.core.ffi.CoreException
import dev.wplayer.core.ffi.TranscriptionListener
import dev.wplayer.core.ffi.TranscriptionRequest
import dev.wplayer.core.ffi.transcribe
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Makes AI subtitles for one file in the foreground: from where the user is
 * watching to the end, then the part before it. Lines are written to the
 * subtitle file as they are recognized, so the player can show them early.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val browser: BrowserRepository,
    private val settings: SettingsRepository,
    private val models: WhisperModelStore,
    private val subtitles: GeneratedSubtitles,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result = turn.withLock { transcribe() }

    private suspend fun transcribe(): Result {
        val sourceKey = inputData.getString(KEY_SOURCE) ?: return Result.failure()
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val start = inputData.getDouble(KEY_START, 0.0)
        val duration = inputData.getDouble(KEY_DURATION, 0.0)
        val audioTrack = inputData.getLong(KEY_AUDIO_TRACK, -1).takeIf { it >= 0 }
        val lines = AtomicInteger()
        val done = AtomicReference(0.0)
        return try {
            // Tells this file from those still waiting for their turn.
            setProgress(workDataOf(KEY_DONE_SECONDS to 0.0, KEY_LINES to 0))
            val config = settings.settings.first().transcription
            if (!models.isInstalled(config.model)) return failure(applicationContext.getString(R.string.ai_model_missing))
            setForeground(foregroundInfo(title, null))
            val uri = browser.playbackUri(sourceKey, path)
            val workDir = File(applicationContext.cacheDir, "transcription").apply {
                deleteRecursively()
                mkdirs()
            }
            coroutineScope {
                // The native work blocks; report from here every few seconds.
                val reporter = launch {
                    while (isActive) {
                        delay(PROGRESS_INTERVAL_MS)
                        setProgress(workDataOf(KEY_DONE_SECONDS to done.get(), KEY_LINES to lines.get()))
                        notifications.notify(NOTIFICATION_ID, notification(title, done.get(), duration))
                    }
                }
                withContext(io) {
                    SrtWriter.open(subtitles.file(mediaKey(sourceKey, path))).use { writer ->
                        // The part being watched first, then the beginning.
                        val passes = listOf(start to null) + if (start > 0) listOf(0.0 to start) else emptyList()
                        var finished = 0.0
                        for ((from, to) in passes) {
                            val listener = object : TranscriptionListener {
                                override fun onLine(startMs: ULong, endMs: ULong, text: String) {
                                    writer.add(startMs.toLong(), endMs.toLong(), text)
                                    lines.incrementAndGet()
                                }

                                override fun onProgress(positionSeconds: Double) {
                                    done.set(finished + positionSeconds - from)
                                }

                                override fun isCancelled(): Boolean = isStopped
                            }
                            transcribe(
                                TranscriptionRequest(
                                    uri = uri,
                                    modelPath = models.file(config.model).path,
                                    vadModelPath = models.vadFile.path,
                                    language = config.language.ifEmpty { null },
                                    audioTrack = audioTrack,
                                    startSeconds = from,
                                    endSeconds = to,
                                    threads = THREADS.toUInt(),
                                    workDir = workDir.path,
                                ),
                                listener,
                            )
                            finished += (to ?: duration) - from
                        }
                    }
                }
                reporter.cancel()
            }
            Result.success(workDataOf(KEY_LINES to lines.get()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreException.Cancelled) {
            Result.failure()
        } catch (e: Exception) {
            failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failure(reason: String) = Result.failure(workDataOf(KEY_ERROR to reason))

    private fun foregroundInfo(title: String, fraction: Float?): ForegroundInfo {
        // Android 15 has a service type for this kind of work; before it, data sync is the closest.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(NOTIFICATION_ID, notification(title, 0.0, 0.0, fraction), type)
    }

    private fun notification(title: String, done: Double, duration: Double, fraction: Float? = null) =
        NotificationCompat.Builder(applicationContext, ensureAiChannel(applicationContext))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(applicationContext.getString(R.string.ai_subtitles_running))
            .setContentText(if (duration > 0) "$title · ${clock(done)} / ${clock(duration)}" else title)
            .setProgress(PROGRESS_MAX, ((fraction ?: (done / duration).toFloat()).coerceIn(0f, 1f) * PROGRESS_MAX).toInt(), duration <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun clock(seconds: Double) = DateUtils.formatElapsedTime(seconds.toLong())

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_PATH = "path"
        const val KEY_TITLE = "title"
        const val KEY_START = "start"
        const val KEY_DURATION = "duration"
        const val KEY_AUDIO_TRACK = "audio_track"
        const val KEY_DONE_SECONDS = "done_seconds"
        const val KEY_LINES = "lines"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_ID = 0x4149_0002
        private const val PROGRESS_INTERVAL_MS = 3_000L
        private const val PROGRESS_MAX = 1000

        /** One file at a time: each saturates the big cores. */
        private val turn = Mutex()

        /** The big cores; the rest stay free for playback and the UI. */
        private val THREADS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
