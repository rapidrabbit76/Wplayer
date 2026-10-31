package dev.wplayer.app.data.transcription

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val fraction: Float?) : ModelStatus
    data object Installed : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

/**
 * The whisper models on the device. Each is downloaded once by a WorkManager
 * job ([ModelDownloadWorker]), together with the small voice activity model
 * every whisper model uses.
 */
@Singleton
class WhisperModelStore @Inject constructor(
    @ApplicationContext context: Context,
    private val workManager: WorkManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val directory = File(context.filesDir, "whisper")
    val vadFile: File get() = File(directory, VAD_FILE_NAME)

    /** Bumped when files change outside a download (deletion), so statuses are read again. */
    private val changes = MutableStateFlow(0)

    fun file(model: WhisperModel) = File(directory, model.fileName)

    fun isInstalled(model: WhisperModel): Boolean =
        file(model).length() == model.sizeBytes && vadFile.length() == VAD_SIZE_BYTES

    fun status(model: WhisperModel): Flow<ModelStatus> =
        combine(workManager.getWorkInfosForUniqueWorkFlow(workName(model)), changes) { infos, _ ->
            val info = infos.firstOrNull()
            when {
                info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED ->
                    ModelStatus.Downloading(info.progress.getFloat(ModelDownloadWorker.KEY_FRACTION, -1f).takeIf { it >= 0 })
                isInstalled(model) -> ModelStatus.Installed
                info?.state == WorkInfo.State.FAILED ->
                    ModelStatus.Failed(info.outputData.getString(ModelDownloadWorker.KEY_ERROR).orEmpty())
                else -> ModelStatus.NotInstalled
            }
        }

    fun download(model: WhisperModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.name))
            .setConstraints(
                Constraints.Builder()
                    // Hundreds of megabytes: Wi-Fi only.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(model: WhisperModel) {
        workManager.cancelUniqueWork(workName(model))
    }

    suspend fun delete(model: WhisperModel) {
        cancelDownload(model)
        withContext(io) {
            file(model).delete()
            File(directory, model.fileName + ModelDownloadWorker.PARTIAL_SUFFIX).delete()
        }
        changes.value++
    }

    companion object {
        const val VAD_FILE_NAME = "ggml-silero-v5.1.2.bin"
        const val VAD_SIZE_BYTES = 885_098L
        const val VAD_SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"
        const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE_NAME"
        private const val MODEL_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        fun url(model: WhisperModel) = MODEL_BASE_URL + model.fileName

        private fun workName(model: WhisperModel) = "whisper-model-${model.name}"
    }
}
