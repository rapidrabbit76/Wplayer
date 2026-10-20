package dev.wplayer.app.ui.player

/** Forced display aspect ratio; [mpvValue] is the `video-aspect-override` value. */
enum class AspectRatio(val mpvValue: String, val label: String) {
    DEFAULT("no", ""),
    RATIO_16_9("16:9", "16:9"),
    RATIO_4_3("4:3", "4:3"),
    RATIO_1_85("1.85", "1.85:1"),
    RATIO_2_35("2.35", "2.35:1"),
    RATIO_1_1("1:1", "1:1"),
}

enum class ScaleMode {
    /** Whole picture visible, black bars where the shapes differ. */
    FIT,

    /** Stretched to the screen, ignoring the aspect ratio. */
    STRETCH,

    /** Fills the screen keeping the aspect ratio; edges are cropped. */
    CROP,
}
