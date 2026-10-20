package dev.wplayer.app.ui.player

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.BuildConfig
import dev.wplayer.app.data.CacheStorage
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.core.ffi.PlayerConfig
import dev.wplayer.core.player.MpvEngine
import java.io.File
import javax.inject.Inject

/** Builds [MpvEngine] instances configured for this device. */
class MpvEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheStorage: CacheStorage,
    private val environment: MpvEnvironment,
) {
    fun create(path: VideoPath, settings: AppSettings): MpvEngine {
        val dataDir = File(context.filesDir, "mpv").apply { mkdirs() }
        val fontsDir = prepareSubtitleFonts(dataDir)
        val options = buildMap {
            put("sub-fonts-dir", fontsDir.absolutePath)
            put("sub-font", SUBTITLE_FONT_FAMILY)
            put("sub-font-size", "46")
            put("sub-border-size", "2.5")
            put("sub-use-margins", "yes")
            // External subtitles arrive as UTF-8 from the core (wplayersub://).
            put("sub-codepage", "utf8")
            put("demuxer-readahead-secs", "20")
            put("volume-max", MAX_VOLUME_PERCENT.toString())
            // Network read-ahead goes to flash, not RAM, so it can be gigabytes.
            put("cache-on-disk", "yes")
            put("demuxer-cache-dir", cacheStorage.playerCacheDir.absolutePath)
            putAll(environment.options(settings))
        }
        return MpvEngine(
            PlayerConfig(
                dataDir = dataDir.absolutePath,
                hwdec = path.hwdec,
                // gpu-next (libplacebo) unless the decoder draws itself: mpv's recommended
                // VO, and the legacy `gpu` VO left video black on some GLES stacks (ANGLE).
                videoOutput = path.videoOutput,
                options = options,
                logLevel = if (BuildConfig.DEBUG) "v" else "warn",
            ),
        )
    }

    /**
     * libass has no system font provider on Android, so it only sees fonts in
     * `sub-fonts-dir`. Link the system CJK font there instead of scanning all
     * of /system/fonts, which would slow every file open.
     */
    private fun prepareSubtitleFonts(dataDir: File): File {
        val fontsDir = File(dataDir, "fonts").apply { mkdirs() }
        val systemFont = SYSTEM_CJK_FONTS.map(::File).firstOrNull(File::exists) ?: return fontsDir
        val link = File(fontsDir, systemFont.name)
        if (!link.exists()) {
            try {
                Os.symlink(systemFont.absolutePath, link.absolutePath)
            } catch (e: ErrnoException) {
                Log.w(TAG, "cannot link subtitle font ${systemFont.name}", e)
            }
        }
        return fontsDir
    }

    companion object {
        private const val TAG = "MpvEngineFactory"
        private const val SUBTITLE_FONT_FAMILY = "Noto Sans CJK KR"
        private val SYSTEM_CJK_FONTS = listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJKkr-Regular.otf",
            "/system/fonts/NotoSansKR-Regular.otf",
        )
    }
}
