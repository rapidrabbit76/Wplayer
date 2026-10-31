package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.NetworkSettings
import dev.wplayer.app.data.model.PlaybackSettings
import dev.wplayer.app.data.model.RepeatMode
import dev.wplayer.app.data.model.ScreenSettings
import dev.wplayer.app.data.model.SubtitleColor
import dev.wplayer.app.data.model.SubtitleFont
import dev.wplayer.app.data.model.SubtitleSettings
import dev.wplayer.app.data.model.VideoEnhancement
import org.junit.Assert.assertEquals
import org.junit.Test

class MpvOptionsTest {
    @Test
    fun `network settings map to the demuxer cache`() {
        val options = mpvOptions(AppSettings(network = NetworkSettings(bufferSeconds = 10, cacheMegabytes = 256)))
        assertEquals("yes", options["cache"])
        assertEquals("256MiB", options["demuxer-max-bytes"])
        assertEquals("64MiB", options["demuxer-max-back-bytes"])
        assertEquals("10", options["cache-pause-wait"])
    }

    @Test
    fun `read ahead is limited by seconds, or by the cache when unlimited`() {
        assertEquals("300", mpvOptions(AppSettings())["cache-secs"])
        assertEquals("3600", mpvOptions(AppSettings(network = NetworkSettings(readAheadSeconds = 0)))["cache-secs"])
    }

    @Test
    fun `the disk cache never exceeds the free space`() {
        val options = mpvOptions(AppSettings(network = NetworkSettings(cacheMegabytes = 10240)), maxCacheMegabytes = 3000)
        assertEquals("3000MiB", options["demuxer-max-bytes"])
        assertEquals("no", mpvOptions(AppSettings(), maxCacheMegabytes = 0)["cache"])
    }

    @Test
    fun `no cache still keeps a small demuxer buffer`() {
        val options = mpvOptions(AppSettings(network = NetworkSettings(cacheMegabytes = 0)))
        assertEquals("no", options["cache"])
        assertEquals("8MiB", options["demuxer-max-bytes"])
    }

    @Test
    fun `subtitle style maps to mpv options`() {
        val options = mpvOptions(
            AppSettings(subtitle = SubtitleSettings(sizePercent = 150, color = SubtitleColor.YELLOW, background = true, assFullStyling = false)),
        )
        assertEquals("1.5", options["sub-scale"])
        assertEquals("#FFE066", options["sub-color"])
        assertEquals("background-box", options["sub-border-style"])
        assertEquals("force", options["sub-ass-override"])
    }

    @Test
    fun `subtitles use the added font, or the system's Korean font`() {
        assertEquals("Noto Sans CJK KR", mpvOptions(AppSettings())["sub-font"])
        val font = SubtitleFont("DX영화자막Medium.ttf", "DX영화자막 Medium")
        assertEquals("DX영화자막 Medium", mpvOptions(AppSettings(subtitle = SubtitleSettings(font = font)))["sub-font"])
    }

    @Test
    fun `repeat one loops the file`() {
        assertEquals("inf", mpvOptions(AppSettings(playback = PlaybackSettings(repeat = RepeatMode.ONE)))["loop-file"])
        assertEquals("no", mpvOptions(AppSettings())["loop-file"])
    }

    @Test
    fun `enhancement presets become shader paths and smooth motion needs the display rate`() {
        val sharp = mpvOptions(AppSettings(screen = ScreenSettings(enhancement = VideoEnhancement.SHARP)), shaderDirectory = "/s")
        assertEquals("/s/CfL_Prediction_Lite.glsl:/s/FSR.glsl:/s/CAS.glsl", sharp["glsl-shaders"])
        val light = mpvOptions(AppSettings(screen = ScreenSettings(enhancement = VideoEnhancement.LIGHT_DETAIL)), shaderDirectory = "/s")
        assertEquals("/s/CfL_Prediction_Lite.glsl:/s/ravu-lite-ar-r4.hook:/s/CAS.glsl", light["glsl-shaders"])
        val anime = mpvOptions(AppSettings(screen = ScreenSettings(enhancement = VideoEnhancement.ANIME)), shaderDirectory = "/s")
        assertEquals(7, anime.getValue("glsl-shaders").split(':').size)
        assertEquals("", mpvOptions(AppSettings())["glsl-shaders"])

        val smooth = AppSettings(screen = ScreenSettings(smoothMotion = true))
        assertEquals("display-resample", mpvOptions(smooth, displayFps = 144f)["video-sync"])
        assertEquals("144.0", mpvOptions(smooth, displayFps = 144f)["display-fps-override"])
        assertEquals("audio", mpvOptions(smooth, displayFps = null)["video-sync"])
        assertEquals("no", mpvOptions(AppSettings(), displayFps = 144f)["interpolation"])
    }
}
