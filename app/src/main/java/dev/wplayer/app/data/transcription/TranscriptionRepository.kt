package dev.wplayer.app.data.transcription

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.wplayer.app.data.model.mediaKey
import java.io.File
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
 * Starts and follows AI subtitle work. Files are transcribed one at a time
 * (each job saturates the big cores), in the order they were asked for.
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
        val key = mediaKey(sourceKey, path)
        workManager.cancelAllWorkByTag(tag(key))
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
            .addTag(tag(key))
            .build()
        workManager.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(mediaKey: String) {
        workManager.cancelAllWorkByTag(tag(mediaKey))
    }

    fun subtitleFile(mediaKey: String): File? = subtitles.existing(mediaKey)

    fun state(mediaKey: String): Flow<TranscriptionState> =
        workManager.getWorkInfosByTagFlow(tag(mediaKey)).map { infos ->
            // The latest request for this file decides.
            val info = infos.lastOrNull()
            when (info?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TranscriptionState.Queued
                WorkInfo.State.RUNNING -> TranscriptionState.Running(
                    doneSeconds = info.progress.getDouble(TranscriptionWorker.KEY_DONE_SECONDS, 0.0),
                    lines = info.progress.getInt(TranscriptionWorker.KEY_LINES, 0),
                )
                WorkInfo.State.SUCCEEDED -> TranscriptionState.Done
                WorkInfo.State.FAILED -> info.outputData.getString(TranscriptionWorker.KEY_ERROR)
                    ?.let(TranscriptionState::Failed) ?: TranscriptionState.Idle
                WorkInfo.State.CANCELLED, null -> if (subtitles.existing(mediaKey) != null) TranscriptionState.Done else TranscriptionState.Idle
            }
        }

    private companion object {
        /** One queue for all files, so two transcriptions never compete for the CPU. */
        const val QUEUE = "transcription"

        fun tag(mediaKey: String) = "transcribe:$mediaKey"
    }
}
