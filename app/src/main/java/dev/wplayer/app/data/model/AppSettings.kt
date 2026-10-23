package dev.wplayer.app.data.model

import kotlinx.serialization.Serializable

/** What a vertical swipe on one half of the screen controls. */
@Serializable
enum class VerticalSwipeAction { BRIGHTNESS, VOLUME, NONE }

/** What a double tap in the middle of the video does. */
@Serializable
enum class CenterDoubleTapAction { PICTURE_IN_PICTURE, PLAY_PAUSE, NONE }

@Serializable
data class GestureSettings(
    val leftVertical: VerticalSwipeAction = VerticalSwipeAction.BRIGHTNESS,
    val rightVertical: VerticalSwipeAction = VerticalSwipeAction.VOLUME,
    val horizontalSeek: Boolean = true,
    val doubleTapSidesSeek: Boolean = true,
    val doubleTapCenter: CenterDoubleTapAction = CenterDoubleTapAction.PICTURE_IN_PICTURE,
    val longPressSpeedUp: Boolean = true,
    val pinchZoom: Boolean = true,
    /** Two fingers swiped down enters picture-in-picture. */
    val twoFingerDownPip: Boolean = true,
    /** Two fingers swiped sideways shifts subtitle timing. */
    val twoFingerSubtitleDelay: Boolean = true,
    /**
     * Gestures that start on a shown subtitle act on it: drag up/down moves
     * it, pinch resizes it, drag sideways jumps to the previous/next line.
     */
    val subtitleGestures: Boolean = true,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class AppearanceSettings(
    /** Dark by default: a video app is mostly used in dim rooms. */
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    /** Pure black dark theme, for OLED screens. */
    val pureBlack: Boolean = false,
)

/** Whether to continue a file from where it was left. */
@Serializable
enum class ResumeMode { ALWAYS, NEVER, ASK }

/** Fast lands on the nearest keyframe; accurate decodes up to the exact time. */
@Serializable
enum class SeekPrecision { FAST, ACCURATE }

@Serializable
enum class OrientationMode {
    /** Phones follow the video's orientation; tablets rotate freely. */
    AUTO,
    SENSOR,
    LANDSCAPE,
    PORTRAIT,
}

@Serializable
enum class RepeatMode { OFF, ALL, ONE }

/** What headset, Bluetooth and notification next/previous buttons do. */
@Serializable
enum class MediaButtonAction {
    /** Next and previous file in the folder or playlist. */
    FILES,

    /** Jump forward and back by the seek steps. */
    SEEK,
}

@Serializable
data class PlaybackSettings(
    val hardwareDecoding: Boolean = true,
    /** With hardware decoding, let the decoder draw onto the screen itself (TVs; see VideoPath.DIRECT). */
    val directVideoOutput: Boolean = false,
    /** Set by the player, not the user: the zero-copy path froze on this device, so start on the copy path. */
    val zeroCopyFroze: Boolean = false,
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 10,
    /** Repeated remote/keyboard seek taps add up and seek once after the last one. */
    val combineRepeatedSeeks: Boolean = false,
    /** Show the target time in the middle of the screen for remote, keyboard and double-tap seeks. */
    val showStepSeekTime: Boolean = true,
    val seekPrecision: SeekPrecision = SeekPrecision.ACCURATE,
    val longPressSpeed: Double = 2.0,
    val resume: ResumeMode = ResumeMode.ALWAYS,
    /** Keep the last playback speed for the next file (and the next launch). */
    val keepSpeed: Boolean = false,
    val lastSpeed: Double = 1.0,
    /** At the end of a file, play the next one in the folder or playlist. */
    val autoPlayNext: Boolean = true,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val autoPictureInPicture: Boolean = true,
    /** Keep playing the sound when the screen turns off or another app comes to the front. */
    val backgroundPlay: Boolean = true,
    val mediaButtons: MediaButtonAction = MediaButtonAction.FILES,
)

@Serializable
data class ScreenSettings(
    val orientation: OrientationMode = OrientationMode.AUTO,
    /** Remember brightness set by gesture for the next video. */
    val saveBrightness: Boolean = true,
    /** Window brightness 0..1 from the last gesture; null follows the system. */
    val savedBrightness: Float? = null,
    val autoHideControls: Boolean = true,
    val showClock: Boolean = false,
    /** Codec, decoder, dropped frames, bitrate and cache over the video, for checking performance. */
    val showStats: Boolean = false,
    /** GPU shaders that upscale and sharpen the picture; they only run when the video is enlarged. */
    val enhancement: VideoEnhancement = VideoEnhancement.OFF,
    /** Smooth the color banding of compressed gradients (skies, dark scenes). */
    val deband: Boolean = false,
    /** Blend frames to the display's refresh rate so motion does not judder; no new frames are made. */
    val smoothMotion: Boolean = false,
)

/** Picture enhancement presets, each a chain of mpv user shaders bundled in assets/shaders. */
@Serializable
enum class VideoEnhancement(val shaders: List<String>) {
    OFF(emptyList()),

    /** AMD FidelityFX Super Resolution 1: edge-aware upscale and sharpening. Light. */
    SHARP(listOf("FSR.glsl")),

    /** FSRCNNX: a small CNN that doubles luma detail. Heavier. */
    DETAIL(listOf("FSRCNNX_x2_8-0-4-1.glsl")),

    /** Anime4K mode A with the small networks: line restoration and upscaling for animation. */
    ANIME(
        listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        ),
    ),
}

@Serializable
enum class SubtitleColor(val argb: Long) {
    WHITE(0xFFFFFFFF),
    YELLOW(0xFFFFE066),
    CYAN(0xFF7FE7FF),
    GREEN(0xFF9CFF9C),
}

/** Allowed subtitle sizes, in percent of the default. */
val SUBTITLE_SIZE_RANGE = 40..300

/** Allowed subtitle positions: bottom edge in percent of the screen height. */
val SUBTITLE_POSITION_RANGE = 10..100

@Serializable
data class SubtitleSettings(
    val showByDefault: Boolean = true,
    /** Text size relative to the default, in percent. */
    val sizePercent: Int = 100,
    /** Bottom edge of the text, in percent of the screen height from the top (100 = bottom). */
    val position: Int = 100,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val bold: Boolean = false,
    val outline: Boolean = true,
    val shadow: Boolean = true,
    val background: Boolean = false,
    /** ISO 639 code tried first when choosing a subtitle track; empty means any. */
    val preferredLanguage: String = "ko",
    /** Render ASS/SSA with the file's own styles rather than the settings above. */
    val assFullStyling: Boolean = true,
    val embeddedFonts: Boolean = true,
    /** A font file the user added; null for the system's Korean font. */
    val font: SubtitleFont? = null,
)

/** A font file added for subtitles, in the subtitle font folder; [family] is the name mpv asks libass for. */
@Serializable
data class SubtitleFont(val file: String, val family: String)

@Serializable
data class AudioSettings(
    /** 100 is normal volume; up to 200 amplifies quiet files. */
    val boostPercent: Int = 100,
    val preferredLanguage: String = "",
)

@Serializable
data class NetworkSettings(
    /** How far ahead of the playback position network files are read; 0 reads as far as the cache allows. */
    val readAheadSeconds: Int = 300,
    /** Seconds buffered before playback resumes after the cache ran dry. */
    val bufferSeconds: Int = 5,
    /** Network read-ahead cache, kept on disk; 0 turns the cache off. */
    val cacheMegabytes: Int = 1024,
    /** Play and download network sources over mobile data. */
    val allowCellular: Boolean = false,
    /** Render thumbnails of network files over mobile data. */
    val thumbnailsOnCellular: Boolean = false,
)

@Serializable
enum class SortField { NAME, EXTENSION, DATE, SIZE }

@Serializable
enum class BrowserViewMode {
    /** Large 16:9 thumbnails in a grid, for big screens. */
    GRID,
    LIST,
}

@Serializable
data class BrowserSettings(
    val viewMode: BrowserViewMode = BrowserViewMode.GRID,
    val sortField: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
    val showSubtitleFiles: Boolean = false,
    val showOtherFiles: Boolean = false,
    val showHiddenFiles: Boolean = false,
    /** Show how much of each file was watched. */
    val showProgress: Boolean = true,
    val showThumbnails: Boolean = true,
    /** On wide screens, show the parent folder next to the current one. */
    val columnView: Boolean = true,
    val folderNavigation: FolderNavigation = FolderNavigation.BOTH,
    /** The home screen's row of unfinished videos; off keeps what was watched private. */
    val showContinueWatching: Boolean = true,
    /** Once a day, on Wi-Fi while charging, index network sources' file names for instant subfolder search. */
    val backgroundIndexing: Boolean = true,
    /** Size of thumbnails in lists and of grid cells, relative to the default. */
    val thumbnailScale: Float = 1f,
)

/** How the browser offers the way back up from deep folders. */
@Serializable
enum class FolderNavigation {
    BOTH,
    /** A tappable path above the list that jumps to any folder above. */
    PATH_BAR,
    /** A ".." entry at the top of the list, one folder up. */
    PARENT_ROW;

    val showsPathBar: Boolean get() = this != PARENT_ROW
    val showsParentRow: Boolean get() = this != PATH_BAR
}

/** How the app looks for new versions (see UpdateRepository). */
@Serializable
data class UpdateSettings(
    /** Check once in a while when the app starts. */
    val autoCheck: Boolean = true,
    val lastCheckedAtMillis: Long = 0,
)

/**
 * whisper.cpp models offered for AI subtitles, from the whisper.cpp model
 * repository on Hugging Face. [sha256] checks the download.
 */
@Serializable
enum class WhisperModel(val fileName: String, val sizeBytes: Long, val sha256: String) {
    /** whisper small, 8-bit: about three times faster, weaker on Korean. */
    FAST("ggml-small-q8_0.bin", 264_464_607, "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"),

    /** whisper large-v3-turbo, 5-bit: close to large-v3 quality at a fraction of its cost. */
    ACCURATE("ggml-large-v3-turbo-q5_0.bin", 574_041_195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
}

@Serializable
data class TranscriptionSettings(
    val model: WhisperModel = WhisperModel.ACCURATE,
    /** Spoken language as an ISO 639-1 code; empty detects it. */
    val language: String = "",
)

@Serializable
data class AppSettings(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val screen: ScreenSettings = ScreenSettings(),
    val subtitle: SubtitleSettings = SubtitleSettings(),
    val audio: AudioSettings = AudioSettings(),
    val network: NetworkSettings = NetworkSettings(),
    val browser: BrowserSettings = BrowserSettings(),
    val gestures: GestureSettings = GestureSettings(),
    val transcription: TranscriptionSettings = TranscriptionSettings(),
    val update: UpdateSettings = UpdateSettings(),
)
