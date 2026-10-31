package dev.wplayer.app.ui.common

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A remote's select key, held to open the menu, must not pick an item when released. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class RowActionMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()

    @Test
    fun releasingTheHeldSelectKeyPicksNothing() {
        openMenu()
        // The long press that opened the menu: its repeats and its release arrive in the menu.
        press(listOf(select(ACTION_DOWN, repeat = 12), select(ACTION_UP)))
        compose.runOnIdle { assertEquals(emptyList<String>(), picked) }
    }

    @Test
    fun aNewPressPicksTheFocusedItem() {
        openMenu()
        press(listOf(select(ACTION_DOWN, repeat = 12), select(ACTION_UP)))
        press(listOf(select(ACTION_DOWN), select(ACTION_UP)))
        compose.runOnIdle { assertEquals(listOf("first"), picked) }
    }

    private fun openMenu() {
        compose.setContent {
            RowActionMenu(
                expanded = true,
                onDismiss = {},
                actions = listOf(RowAction("first") { picked += "first" }, RowAction("second") { picked += "second" }),
            )
        }
        compose.onNodeWithText("first").requestFocus()
    }

    private fun press(events: List<KeyEvent>) = events.forEach { compose.onNodeWithText("first").performKeyPress(it) }

    private fun select(action: Int, repeat: Int = 0) =
        KeyEvent(android.view.KeyEvent(0, 0, action, KEYCODE_DPAD_CENTER, repeat))
}
