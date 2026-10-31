package dev.wplayer.app.ui.viewer

import androidx.compose.ui.input.key.Key

/** What a key does in the image viewer. */
enum class ViewerKeyAction { NEXT_PAGE, PREVIOUS_PAGE, TOGGLE_OVERLAY, TOGGLE_SLIDESHOW }

/**
 * The action of [key], or null when the viewer leaves it alone. The arrows
 * follow the pages on screen, so with [rightToLeft] the next page is to the
 * left; media and page keys always mean the next and previous page. A
 * [zoomed] picture keeps the arrows: paging is for whole pictures.
 */
fun viewerKeyAction(key: Key, rightToLeft: Boolean, zoomed: Boolean): ViewerKeyAction? = when (key) {
    Key.DirectionLeft -> if (zoomed) null else if (rightToLeft) ViewerKeyAction.NEXT_PAGE else ViewerKeyAction.PREVIOUS_PAGE
    Key.DirectionRight -> if (zoomed) null else if (rightToLeft) ViewerKeyAction.PREVIOUS_PAGE else ViewerKeyAction.NEXT_PAGE
    Key.MediaPrevious, Key.PageUp -> ViewerKeyAction.PREVIOUS_PAGE
    Key.MediaNext, Key.PageDown -> ViewerKeyAction.NEXT_PAGE
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> ViewerKeyAction.TOGGLE_OVERLAY
    Key.MediaPlayPause, Key.Spacebar -> ViewerKeyAction.TOGGLE_SLIDESHOW
    else -> null
}
