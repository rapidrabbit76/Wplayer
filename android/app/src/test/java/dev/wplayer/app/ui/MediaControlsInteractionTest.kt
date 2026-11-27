package dev.wplayer.app.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.ui.common.ThumbnailSizeButton
import dev.wplayer.app.ui.player.ControlsConfig
import dev.wplayer.app.ui.player.PlayerControlActions
import dev.wplayer.app.ui.player.PlayerControls
import dev.wplayer.app.ui.player.PlayerUiState
import dev.wplayer.app.ui.sources.SourceEditorActions
import dev.wplayer.app.ui.sources.SourceEditorContent
import dev.wplayer.app.ui.sources.SourceEditorUiState
import dev.wplayer.core.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class MediaControlsInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val slider get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    @Test
    fun seekBarFollowsPlaybackAndSeeksToTheRequestedPosition() {
        val position = mutableStateOf(25.0)
        val seeks = mutableListOf<Double>()
        val actions = object : PlayerControlActions by NoOpPlayerActions {
            override fun onSeekTo(seconds: Double) {
                seeks += seconds
            }
        }
        compose.setContent {
            PlayerControls(
                state = PlayerUiState(playback = PlaybackState(duration = 100.0, position = position.value)),
                config = ControlsConfig(),
                actions = actions,
            )
        }
        assertProgress(0.25f)
        compose.runOnIdle { position.value = 50.0 }
        assertProgress(0.5f)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle { assertEquals(listOf(75.0), seeks) }
    }

    @Test
    fun thumbnailSliderReflectsExternalChangesAndRoundsInput() {
        val scale = mutableStateOf(1f)
        compose.setContent { ThumbnailSizeButton(scale.value) { scale.value = it } }
        compose.onNodeWithContentDescription("Thumbnail size").performClick()
        assertProgress(1f)
        compose.runOnIdle { scale.value = 1.5f }
        assertProgress(1.5f)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1.38f) }
        compose.runOnIdle { assertEquals(1.4f, scale.value, 0.0001f) }
        assertProgress(1.4f)
    }

    @Test
    fun selectingAProtocolUpdatesTheFieldAndClosesTheMenu() {
        val selected = mutableStateOf(SourceProtocol.SMB)
        val actions = object : SourceEditorActions by NoOpEditorActions {
            override fun onProtocolChange(protocol: SourceProtocol) {
                selected.value = protocol
            }
        }
        compose.setContent { SourceEditorContent(SourceEditorUiState(protocol = selected.value), actions) }
        compose.onNodeWithText("SMB").performClick()
        compose.onNodeWithText("NFS").performClick()
        compose.runOnIdle { assertEquals(SourceProtocol.NFS, selected.value) }
        compose.onNodeWithText("NFS").assertExists()
        compose.onNodeWithText("FTP").assertDoesNotExist()
    }

    private fun assertProgress(expected: Float) {
        val progress = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(expected, progress.current, 0.0001f)
    }
}
