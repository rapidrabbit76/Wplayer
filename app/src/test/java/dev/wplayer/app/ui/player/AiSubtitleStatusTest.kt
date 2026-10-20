package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.data.transcription.ModelStatus
import dev.wplayer.app.data.transcription.TranscriptionState
import org.junit.Assert.assertEquals
import org.junit.Test

class AiSubtitleStatusTest {
    private val model = WhisperModel.ACCURATE

    @Test
    fun `asks for the model before anything can start`() {
        val status = aiSubtitleStatus(TranscriptionState.Idle, model, ModelStatus.NotInstalled, hasFile = false, durationSeconds = 100.0)
        assertEquals(AiSubtitleStatus.NeedsModel(model, ModelStatus.NotInstalled), status)
    }

    @Test
    fun `work in progress wins over the model state`() {
        val running = aiSubtitleStatus(TranscriptionState.Running(30.0, 12), model, ModelStatus.Installed, hasFile = true, durationSeconds = 100.0)
        assertEquals(AiSubtitleStatus.Running(30.0, 100.0, 12), running)
        val done = aiSubtitleStatus(TranscriptionState.Done, model, ModelStatus.Installed, hasFile = true, durationSeconds = 100.0)
        assertEquals(AiSubtitleStatus.Ready(made = true), done)
    }
}
