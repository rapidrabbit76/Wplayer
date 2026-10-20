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

enum class ScaleMode {
    /** Whole picture visible, black bars where the shapes differ. */
    FIT,

    /** Stretched to the screen, ignoring the aspect ratio. */
    STRETCH,

    /** Fills the screen keeping the aspect ratio; edges are cropped. */
    CROP,
}
