package dev.wplayer.app.ui.common

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A remote's up and down keys leave a single-line text field, which otherwise keeps them for its cursor. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LeaveOnVerticalKeysTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun downAndUpMoveFocusOutOfTheField() {
        compose.setContent {
            val focusManager = LocalFocusManager.current
            Column {
                OutlinedTextField(
                    value = "nas",
                    onValueChange = {},
                    singleLine = true,
                    modifier = Modifier.testTag("first").leaveOnVerticalKeys(focusManager),
                )
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    singleLine = true,
                    modifier = Modifier.testTag("second").leaveOnVerticalKeys(focusManager),
                )
                Button(onClick = {}) { Text("Save") }
            }
        }
        compose.onNodeWithTag("first").requestFocus()
        press(KEYCODE_DPAD_DOWN, "first")
        compose.onNodeWithTag("second").assertIsFocused()
        press(KEYCODE_DPAD_DOWN, "second")
        compose.onNodeWithText("Save").assertIsFocused()
        press(KEYCODE_DPAD_UP, "Save", byText = true)
        compose.onNodeWithTag("second").assertIsFocused()
    }

    private fun press(code: Int, node: String, byText: Boolean = false) {
        val target = if (byText) compose.onNodeWithText(node) else compose.onNodeWithTag(node)
        target.performKeyPress(KeyEvent(android.view.KeyEvent(ACTION_DOWN, code)))
        target.performKeyPress(KeyEvent(android.view.KeyEvent(ACTION_UP, code)))
        compose.waitForIdle()
    }
}
