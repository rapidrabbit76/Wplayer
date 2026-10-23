package dev.wplayer.app.ui.settings

import android.app.Application
import android.net.Uri
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.model.SubtitleFont
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The font dialog's choices respond to a remote's select key, as to a tap. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SubtitleFontDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectOnTheSystemFontChoosesIt() {
        val added = SubtitleFont("a.ttf", "Myungjo")
        val chosen = mutableListOf<SubtitleFont?>()
        compose.setContent {
            SubtitleFontRow(added, SubtitleFontsState(listOf(added)), NoActions) { chosen += it }
        }
        compose.onNodeWithText("Subtitle font").performClick()
        val system = compose.onNodeWithText("System font")
        system.requestFocus()
        system.performKeyPress(KeyEvent(android.view.KeyEvent(ACTION_DOWN, KEYCODE_DPAD_CENTER)))
        system.performKeyPress(KeyEvent(android.view.KeyEvent(ACTION_UP, KEYCODE_DPAD_CENTER)))
        compose.runOnIdle { assertEquals(listOf<SubtitleFont?>(null), chosen) }
    }

    private object NoActions : SubtitleFontActions {
        override fun add(uri: Uri) = Unit
        override fun delete(font: SubtitleFont) = Unit
        override fun dismissFailure() = Unit
    }
}
