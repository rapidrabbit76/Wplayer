package dev.wplayer.app.ui.viewer

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.ImagePage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Which swipe and which arrow turn to the next page, by reading direction; the count always runs from the first page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ImageViewerDirectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val pages = listOf("001.jpg", "002.jpg", "003.jpg").map { ImagePage(it, "/book/$it") }

    private fun show(rightToLeft: Boolean) {
        val state = ImageViewerUiState(
            pages = pages,
            startPath = pages.first().path,
            images = pages.associate { it.path to PageImage.Unreadable },
            rightToLeft = rightToLeft,
        )
        compose.setContent { ImageViewerContent(state, NoActions) }
        compose.onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun rightToLeftSwipeRightTurnsToTheNextPage() {
        show(rightToLeft = true)
        compose.onRoot().performTouchInput { swipeRight() }
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun rightToLeftSwipeLeftStaysOnTheFirstPage() {
        show(rightToLeft = true)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun leftToRightSwipeLeftTurnsToTheNextPage() {
        show(rightToLeft = false)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun rightToLeftLeftArrowTurnsToTheNextPage() {
        show(rightToLeft = true)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("2 / 3").assertExists()
    }

    private object NoActions : ImageViewerActions {
        override fun back() = Unit
        override fun show(page: ImagePage) = Unit
        override fun hide(page: ImagePage) = Unit
        override fun retry(page: ImagePage) = Unit
        override fun unreadable(page: ImagePage) = Unit
        override fun reload() = Unit
        override fun unlock() = Unit
    }
}
