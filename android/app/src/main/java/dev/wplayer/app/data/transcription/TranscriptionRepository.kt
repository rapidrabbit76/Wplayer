package dev.wplayer.app.data.transcription

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dev.wplayer.app.data.model.mediaKey
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where the AI subtitles of one file stand. */
sealed interface TranscriptionState {
    data object Idle : TranscriptionState
    data object Queued : TranscriptionState

    /** [doneSeconds] of the file's audio are transcribed, [lines] lines so far. */
    data class Running(val doneSeconds: Double, val lines: Int) : TranscriptionState
    data object Done : TranscriptionState
    data class Failed(val reason: String) : TranscriptionState
}

/**
 * Each file is its own job, so failing or cancelling one leaves the others
 * alone; [TranscriptionWorker] runs them one at a time.
 */
@Singleton
class TranscriptionRepository @Inject constructor(
    private val workManager: WorkManager,
    private val subtitles: GeneratedSubtitles,
) {
    /**
     * Transcribes [path] of [sourceKey] starting at [startSeconds], then the
     * part before it. [durationSeconds] is for progress; [audioTrack] is
     * mpv's audio track id (null: the default one).
     */
    fun start(sourceKey: String, path: String, title: String, startSeconds: Double, durationSeconds: Double, audioTrack: Long?) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_SOURCE to sourceKey,
                    TranscriptionWorker.KEY_PATH to path,
                    TranscriptionWorker.KEY_TITLE to title,
                    TranscriptionWorker.KEY_START to startSeconds,
                    TranscriptionWorker.KEY_DURATION to durationSeconds,
                    TranscriptionWorker.KEY_AUDIO_TRACK to (audioTrack ?: -1L),
                ),
            )
            // A job waiting for its turn is stopped by the system after about ten minutes and
            // started again after this backoff; kept short so it follows the one before soon.
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(workName(mediaKey(sourceKey, path)), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(mediaKey: String) {
        workManager.cancelUniqueWork(workName(mediaKey))
    }

    fun subtitleFile(mediaKey: String): File? = subtitles.existing(mediaKey)

    fun state(mediaKey: String): Flow<TranscriptionState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(mediaKey)).map { infos ->
            val info = infos.firstOrNull()
            when (info?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TranscriptionState.Queued
                // A job waiting for its turn has no progress yet.
                WorkInfo.State.RUNNING -> if (TranscriptionWorker.KEY_DONE_SECONDS in info.progress.keyValueMap) {
                    TranscriptionState.Running(
                        doneSeconds = info.progress.getDouble(TranscriptionWorker.KEY_DONE_SECONDS, 0.0),
                        lines = info.progress.getInt(TranscriptionWorker.KEY_LINES, 0),
                    )
                } else {
                    TranscriptionState.Queued
                }
                WorkInfo.State.SUCCEEDED -> TranscriptionState.Done
                WorkInfo.State.FAILED -> info.outputData.getString(TranscriptionWorker.KEY_ERROR)
                    ?.let(TranscriptionState::Failed) ?: TranscriptionState.Idle
                WorkInfo.State.CANCELLED, null -> if (subtitles.existing(mediaKey) != null) TranscriptionState.Done else TranscriptionState.Idle
            }
        }

    private companion object {
        fun workName(mediaKey: String) = "transcribe:$mediaKey"
    }
}
