package dev.wplayer.app.ui.player

/**
 * Forced display aspect ratio; [mpvValue] is the `video-aspect-override` value
 * and [ratio] the same as width / height (null keeps the video's own).
 */
enum class AspectRatio(val mpvValue: String, val label: String, val ratio: Float?) {
    DEFAULT("no", "", null),
    RATIO_16_9("16:9", "16:9", 16f / 9),
    RATIO_4_3("4:3", "4:3", 4f / 3),
    RATIO_1_85("1.85", "1.85:1", 1.85f),
    RATIO_2_35("2.35", "2.35:1", 2.35f),
    RATIO_1_1("1:1", "1:1", 1f),
}

/**
 * The frame rate to ask the display for, 0 for no preference. Only videos
 * faster than 60 fps ask: adaptive displays idle at 60 Hz during playback and
 * drop the extra frames. Slower videos already show every frame at 60 Hz, and
 * asking can make things worse (a ZUI tablet answers 24 fps with 30 Hz).
 * Smooth motion fits the video to the display itself, so it never asks.
 */
fun displayFrameRateFor(containerFps: Double?, smoothMotion: Boolean): Float =
    containerFps?.takeIf { !smoothMotion && it > MIN_FPS_TO_ASK && it <= MAX_VIDEO_FPS }?.toFloat() ?: 0f

private const val MIN_FPS_TO_ASK = 60.5
private const val MAX_VIDEO_FPS = 240.0

enum class ScaleMode {
    /** Whole picture visible, black bars where the shapes differ. */
    FIT,

    /** Stretched to the screen, ignoring the aspect ratio. */
    STRETCH,

    /** Fills the screen keeping the aspect ratio; edges are cropped. */
    CROP,
}

/**
 * The visible rows of the picture in a view [height] tall and [width] wide, as
 * top until bottom. mpv places subtitles relative to the picture, so with black
 * bars a subtitle sits above the view's bottom edge. [videoAspect] null means unknown.
 */
fun visibleVideoRows(width: Float, height: Float, videoAspect: Float?, scale: ScaleMode, zoom: Float): ClosedFloatingPointRange<Float> {
    if (videoAspect == null || videoAspect <= 0f || width <= 0f || scale == ScaleMode.STRETCH) return 0f..height
    val fitted = width / videoAspect
    val pictureHeight = (if (scale == ScaleMode.FIT) minOf(height, fitted) else maxOf(height, fitted)) * zoom
    val top = (height - pictureHeight) / 2
    return top.coerceAtLeast(0f)..(top + pictureHeight).coerceAtMost(height)
}
