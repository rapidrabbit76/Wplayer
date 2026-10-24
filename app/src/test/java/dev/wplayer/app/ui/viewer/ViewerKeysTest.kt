package dev.wplayer.app.ui.viewer

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerKeysTest {
    private fun action(key: Key, rightToLeft: Boolean, zoomed: Boolean = false) = viewerKeyAction(key, rightToLeft, zoomed)

    @Test
    fun `left to right, the right arrow goes to the next page`() {
        assertEquals(ViewerKeyAction.NEXT_PAGE, action(Key.DirectionRight, rightToLeft = false))
        assertEquals(ViewerKeyAction.PREVIOUS_PAGE, action(Key.DirectionLeft, rightToLeft = false))
    }

    @Test
    fun `right to left, the left arrow goes to the next page`() {
        assertEquals(ViewerKeyAction.NEXT_PAGE, action(Key.DirectionLeft, rightToLeft = true))
        assertEquals(ViewerKeyAction.PREVIOUS_PAGE, action(Key.DirectionRight, rightToLeft = true))
    }

    @Test
    fun `media and page keys mean next and previous in either direction`() {
        for (rightToLeft in listOf(false, true)) {
            assertEquals(ViewerKeyAction.NEXT_PAGE, action(Key.MediaNext, rightToLeft))
            assertEquals(ViewerKeyAction.NEXT_PAGE, action(Key.PageDown, rightToLeft))
            assertEquals(ViewerKeyAction.PREVIOUS_PAGE, action(Key.MediaPrevious, rightToLeft))
            assertEquals(ViewerKeyAction.PREVIOUS_PAGE, action(Key.PageUp, rightToLeft))
        }
    }

    @Test
    fun `a zoomed picture keeps the arrows but not the media keys`() {
        assertNull(action(Key.DirectionLeft, rightToLeft = true, zoomed = true))
        assertNull(action(Key.DirectionRight, rightToLeft = false, zoomed = true))
        assertEquals(ViewerKeyAction.NEXT_PAGE, action(Key.MediaNext, rightToLeft = true, zoomed = true))
    }

    @Test
    fun `center toggles the overlay and play toggles the slideshow`() {
        assertEquals(ViewerKeyAction.TOGGLE_OVERLAY, action(Key.DirectionCenter, rightToLeft = false))
        assertEquals(ViewerKeyAction.TOGGLE_SLIDESHOW, action(Key.MediaPlayPause, rightToLeft = true))
        assertNull(action(Key.DirectionUp, rightToLeft = false))
    }
}
