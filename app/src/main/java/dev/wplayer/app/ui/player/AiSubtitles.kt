package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.data.transcription.ModelStatus
import dev.wplayer.app.data.transcription.TranscriptionState

/** Title of the AI subtitle track in mpv; the panel shows it localized. */
const val AI_SUBTITLE_TITLE = "wplayer-ai"

/** What the subtitle panel offers for making AI subtitles of the file being played. */
sealed interface AiSubtitleStatus {
    /** Media opened from another app: there is no source to read it again from. */
    data object Unavailable : AiSubtitleStatus

    /** The chosen whisper model is not on the device yet. */
    data class NeedsModel(val model: WhisperModel, val download: ModelStatus) : AiSubtitleStatus

    /** Ready to start; [made] when subtitles from an earlier run exist. */
    data class Ready(val made: Boolean) : AiSubtitleStatus
    data object Queued : AiSubtitleStatus
    data class Running(val doneSeconds: Double, val totalSeconds: Double, val lines: Int) : AiSubtitleStatus
    data class Failed(val reason: String) : AiSubtitleStatus
}

internal fun aiSubtitleStatus(
    transcription: TranscriptionState,
    model: WhisperModel,
    modelStatus: ModelStatus,
    hasFile: Boolean,
    durationSeconds: Double,
): AiSubtitleStatus = when (transcription) {
    TranscriptionState.Queued -> AiSubtitleStatus.Queued
    is TranscriptionState.Running -> AiSubtitleStatus.Running(transcription.doneSeconds, durationSeconds, transcription.lines)
    is TranscriptionState.Failed -> AiSubtitleStatus.Failed(transcription.reason)
    TranscriptionState.Idle, TranscriptionState.Done ->
        if (modelStatus == ModelStatus.Installed) AiSubtitleStatus.Ready(made = hasFile) else AiSubtitleStatus.NeedsModel(model, modelStatus)
}
