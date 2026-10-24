package dev.wplayer.app.ui.common

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A switch row is one stop for a remote, and selecting the row flips it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class SwitchRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusStopsOnceOnTheRow() {
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            Column {
                Button(onClick = {}) { Text("Above") }
                SwitchRow("Shuffle", checked = false, onCheckedChange = {})
                Button(onClick = {}) { Text("Below") }
            }
        }
        compose.onNodeWithText("Above").requestFocus()
        move(focusManager, FocusDirection.Next)
        compose.onNodeWithText("Shuffle").assertIsFocused()
        move(focusManager, FocusDirection.Next)
        compose.onNodeWithText("Below").assertIsFocused()
    }

    @Test
    fun selectingTheRowToggles() {
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            SwitchRow("Shuffle", checked = on, onCheckedChange = { on = it })
        }
        val row = compose.onNodeWithText("Shuffle")
        row.assertIsOff()
        row.performClick()
        row.assertIsOn()
    }

    private fun move(focusManager: FocusManager, direction: FocusDirection) {
        compose.runOnIdle { focusManager.moveFocus(direction) }
        compose.waitForIdle()
    }
}
