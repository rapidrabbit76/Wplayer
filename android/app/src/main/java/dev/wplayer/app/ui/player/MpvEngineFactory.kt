package dev.wplayer.app.ui.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.BuildConfig
import dev.wplayer.app.data.CacheStorage
import dev.wplayer.app.data.SubtitleFontStore
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
    private val subtitleFonts: SubtitleFontStore,
) {
    fun create(path: VideoPath, settings: AppSettings): MpvEngine {
        val dataDir = File(context.filesDir, "mpv").apply { mkdirs() }
        val options = buildMap {
            put("sub-fonts-dir", subtitleFonts.prepare().absolutePath)
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
            environment.caFile?.let {
                put("tls-ca-file", it.absolutePath)
                put("tls-verify", "yes")
            }
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
}
