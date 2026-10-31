package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.RepeatMode

/**
 * mpv options that follow the user's settings. Applied when an engine starts
 * and again, as properties, whenever the settings change during playback.
 *
 * The cache lives on disk, so it is limited by [maxCacheMegabytes] (free
 * space) rather than memory. Enhancement shaders are read from [shaderDirectory];
 * smooth motion needs the display's refresh rate, [displayFps].
 */
fun mpvOptions(
    settings: AppSettings,
    maxCacheMegabytes: Long = Long.MAX_VALUE,
    shaderDirectory: String = "",
    displayFps: Float? = null,
): Map<String, String> = buildMap {
    val network = settings.network
    val cacheMegabytes = network.cacheMegabytes.toLong().coerceAtMost(maxCacheMegabytes)
    put("cache", yesNo(cacheMegabytes > 0))
    // Without a cache mpv still needs a small demuxer buffer to play at all.
    val forwardMegabytes = cacheMegabytes.coerceAtLeast(MIN_DEMUXER_MEGABYTES)
    put("demuxer-max-bytes", "${forwardMegabytes}MiB")
    put("demuxer-max-back-bytes", "${(forwardMegabytes / 4).coerceAtLeast(MIN_DEMUXER_MEGABYTES)}MiB")
    put("cache-secs", (if (network.readAheadSeconds > 0) network.readAheadSeconds else UNLIMITED_READ_AHEAD_SECONDS).toString())
    put("cache-pause-wait", network.bufferSeconds.toString())

    val subtitle = settings.subtitle
    put("sub-visibility", yesNo(subtitle.showByDefault))
    put("sub-scale", (subtitle.sizePercent / 100.0).toString())
    put("sub-color", "#%06X".format(subtitle.color.argb and 0xFFFFFF))
    put("sub-font", subtitle.font?.family ?: DEFAULT_SUBTITLE_FONT)
    put("sub-bold", yesNo(subtitle.bold))
    if (subtitle.background) {
        put("sub-border-style", "background-box")
        put("sub-back-color", "#99000000")
    } else {
        put("sub-border-style", "outline-and-shadow")
        put("sub-border-size", if (subtitle.outline) "2.5" else "0")
    }
    put("sub-shadow-offset", if (subtitle.shadow) "1.5" else "0")
    put("sub-ass-override", if (subtitle.assFullStyling) "no" else "force")
    put("embeddedfonts", yesNo(subtitle.embeddedFonts))
    put("slang", subtitle.preferredLanguage)

    put("alang", settings.audio.preferredLanguage)
    put("volume", settings.audio.boostPercent.toString())

    put("loop-file", if (settings.playback.repeat == RepeatMode.ONE) "inf" else "no")

    val screen = settings.screen
    // A path list: ':' separates entries on Android, as on any Unix.
    put("glsl-shaders", screen.enhancement.shaders.joinToString(":") { "$shaderDirectory/$it" })
    put("deband", yesNo(screen.deband))
    val smooth = screen.smoothMotion && displayFps != null && displayFps > 0
    // mpv cannot read the refresh rate on Android; resampling to the display needs it.
    put("display-fps-override", if (smooth) displayFps.toString() else "0")
    put("video-sync", if (smooth) "display-resample" else "audio")
    put("interpolation", yesNo(smooth))
    put("tscale", "oversample")
}

/** Upper bound for [dev.wplayer.app.data.model.AudioSettings.boostPercent]; set once at start. */
const val MAX_VOLUME_PERCENT = 200

/** The system's Korean font, which SubtitleFontStore links into mpv's font folder. */
private const val DEFAULT_SUBTITLE_FONT = "Noto Sans CJK KR"

private const val MIN_DEMUXER_MEGABYTES = 8L

/** mpv's own default: read ahead until the cache is full. */
private const val UNLIMITED_READ_AHEAD_SECONDS = 3600

private fun yesNo(value: Boolean) = if (value) "yes" else "no"
