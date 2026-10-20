package dev.wplayer.app.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import dev.wplayer.app.data.model.GestureSettings
import dev.wplayer.app.data.model.VerticalSwipeAction
import kotlin.math.abs

enum class TapZone { LEFT, CENTER, RIGHT }

/**
 * Receives gestures recognized on the video surface. Distances are fractions
 * of the surface size so behavior is identical on phones and tablets.
 */
interface PlayerGestureCallbacks {
    fun onTap()
    fun onDoubleTap(zone: TapZone)
    fun onLongPressStart()
    fun onLongPressEnd()

    /** [totalFraction] is the cumulative horizontal travel since the drag began (+ is right). */
    fun onSeekDrag(totalFraction: Float)
    fun onSeekDragEnd()

    /** [deltaFraction] is the travel since the previous call (+ is up). */
    fun onVerticalDrag(action: VerticalSwipeAction, deltaFraction: Float)
    fun onVerticalDragEnd()

    fun onPinch(zoomFactor: Float)
    fun onTwoFingerSwipeDown()

    /** [totalFraction] is the cumulative two-finger horizontal travel (+ is right). */
    fun onTwoFingerHorizontalDrag(totalFraction: Float)
    fun onTwoFingerHorizontalDragEnd()

    /** Whether [position] (in a surface of [size]) is on the subtitle currently shown. */
    fun isOnSubtitle(position: Offset, size: IntSize): Boolean

    /** A drag that started on the subtitle moved by [deltaFraction] of the height (+ is up). */
    fun onSubtitleDrag(deltaFraction: Float)

    /** A pinch that started on the subtitle. */
    fun onSubtitlePinch(zoomFactor: Float)

    /** A sideways swipe on the subtitle: +1 next line, -1 previous line. */
    fun onSubtitleStep(lines: Int)

    /** A subtitle drag or pinch ended. */
    fun onSubtitleAdjustEnd()
}

/**
 * Player gestures. While [locked], only a single tap is recognized
 * (to reveal the unlock button).
 */
fun Modifier.playerGestures(
    settings: GestureSettings,
    locked: Boolean,
    callbacks: PlayerGestureCallbacks,
): Modifier = this
    .pointerInput(settings, locked) {
        var longPressActive = false
        detectTapGestures(
            onTap = { callbacks.onTap() },
            onDoubleTap = if (locked) null else { offset -> callbacks.onDoubleTap(zoneOf(offset.x)) },
            onLongPress = if (locked || !settings.longPressSpeedUp) {
                null
            } else {
                { _ ->
                    longPressActive = true
                    callbacks.onLongPressStart()
                }
            },
            onPress = {
                tryAwaitRelease()
                if (longPressActive) {
                    longPressActive = false
                    callbacks.onLongPressEnd()
                }
            },
        )
    }
    .pointerInput(settings, locked) {
        if (!locked) detectDrags(settings, callbacks)
    }

private fun PointerInputScope.zoneOf(x: Float): TapZone = when {
    x < size.width * 0.35f -> TapZone.LEFT
    x > size.width * 0.65f -> TapZone.RIGHT
    else -> TapZone.CENTER
}

private enum class DragMode {
    UNDECIDED, SEEK, VERTICAL, SUBTITLE_MOVE, SUBTITLE_SWIPE,
    MULTI_UNDECIDED, PINCH, SUBTITLE_PINCH, TWO_FINGER_DOWN, TWO_FINGER_HORIZONTAL, IGNORED,
}

private suspend fun PointerInputScope.detectDrags(settings: GestureSettings, callbacks: PlayerGestureCallbacks) =
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val verticalAction = if (down.position.x < size.width / 2) settings.leftVertical else settings.rightVertical
        // Decided at touch-down: a gesture that starts on the subtitle adjusts the subtitle.
        val onSubtitle = settings.subtitleGestures && callbacks.isOnSubtitle(down.position, size)
        var mode = DragMode.UNDECIDED
        var travel = Offset.Zero
        var pinchScale = 1f

        fun endSingleFingerDrag() {
            when (mode) {
                DragMode.SEEK -> callbacks.onSeekDragEnd()
                DragMode.VERTICAL -> callbacks.onVerticalDragEnd()
                DragMode.SUBTITLE_MOVE -> callbacks.onSubtitleAdjustEnd()
                DragMode.SUBTITLE_SWIPE -> if (abs(travel.x) > size.width * SUBTITLE_SWIPE_FRACTION) {
                    callbacks.onSubtitleStep(if (travel.x > 0) 1 else -1)
                }
                else -> Unit
            }
        }

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2 && mode in SINGLE_FINGER_MODES) {
                endSingleFingerDrag()
                mode = DragMode.MULTI_UNDECIDED
                travel = Offset.Zero
            }

            when (mode) {
                DragMode.UNDECIDED -> {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    travel += change.positionChange()
                    if (travel.getDistance() > slop) {
                        val horizontal = abs(travel.x) > abs(travel.y)
                        mode = when {
                            onSubtitle && horizontal -> DragMode.SUBTITLE_SWIPE
                            onSubtitle -> DragMode.SUBTITLE_MOVE
                            horizontal -> if (settings.horizontalSeek) DragMode.SEEK else DragMode.IGNORED
                            verticalAction != VerticalSwipeAction.NONE -> DragMode.VERTICAL
                            else -> DragMode.IGNORED
                        }
                        change.consume()
                    }
                }
                DragMode.SEEK -> {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    travel += change.positionChange()
                    callbacks.onSeekDrag(travel.x / size.width)
                    change.consume()
                }
                DragMode.VERTICAL -> {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    callbacks.onVerticalDrag(verticalAction, -change.positionChange().y / size.height)
                    change.consume()
                }
                DragMode.SUBTITLE_MOVE -> {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    callbacks.onSubtitleDrag(-change.positionChange().y / size.height)
                    change.consume()
                }
                DragMode.SUBTITLE_SWIPE -> {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    travel += change.positionChange()
                    change.consume()
                }
                DragMode.MULTI_UNDECIDED -> {
                    pinchScale *= event.calculateZoom()
                    travel += event.calculatePan()
                    val pinching = abs(pinchScale - 1f) > PINCH_THRESHOLD
                    mode = when {
                        onSubtitle && pinching -> DragMode.SUBTITLE_PINCH
                        settings.pinchZoom && pinching -> DragMode.PINCH
                        travel.getDistance() < slop * 2 -> DragMode.MULTI_UNDECIDED
                        abs(travel.y) > abs(travel.x) && travel.y > 0 && settings.twoFingerDownPip -> DragMode.TWO_FINGER_DOWN
                        abs(travel.x) > abs(travel.y) && settings.twoFingerSubtitleDelay -> DragMode.TWO_FINGER_HORIZONTAL
                        else -> DragMode.MULTI_UNDECIDED
                    }
                    if (mode == DragMode.PINCH) callbacks.onPinch(pinchScale)
                    if (mode == DragMode.SUBTITLE_PINCH) callbacks.onSubtitlePinch(pinchScale)
                    event.changes.forEach { it.consume() }
                }
                DragMode.PINCH -> {
                    callbacks.onPinch(event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
                DragMode.SUBTITLE_PINCH -> {
                    callbacks.onSubtitlePinch(event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
                DragMode.TWO_FINGER_DOWN -> {
                    travel += event.calculatePan()
                    if (travel.y > size.height * PIP_SWIPE_FRACTION) {
                        callbacks.onTwoFingerSwipeDown()
                        mode = DragMode.IGNORED
                    }
                    event.changes.forEach { it.consume() }
                }
                DragMode.TWO_FINGER_HORIZONTAL -> {
                    travel += event.calculatePan()
                    callbacks.onTwoFingerHorizontalDrag(travel.x / size.width)
                    event.changes.forEach { it.consume() }
                }
                DragMode.IGNORED -> event.changes.forEach { it.consume() }
            }
        }

        endSingleFingerDrag()
        if (mode == DragMode.TWO_FINGER_HORIZONTAL) callbacks.onTwoFingerHorizontalDragEnd()
        if (mode == DragMode.SUBTITLE_PINCH) callbacks.onSubtitleAdjustEnd()
    }

private val SINGLE_FINGER_MODES =
    setOf(DragMode.UNDECIDED, DragMode.SEEK, DragMode.VERTICAL, DragMode.SUBTITLE_MOVE, DragMode.SUBTITLE_SWIPE)
private const val PINCH_THRESHOLD = 0.08f
private const val PIP_SWIPE_FRACTION = 0.15f

/** Sideways travel on a subtitle that counts as "next/previous line". */
private const val SUBTITLE_SWIPE_FRACTION = 0.12f
