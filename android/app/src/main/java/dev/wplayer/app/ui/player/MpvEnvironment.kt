package dev.wplayer.app.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.data.CacheStorage
import dev.wplayer.app.data.model.AppSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the mpv options depend on besides the settings: free space for the
 * cache, the bundled video shaders and the display's refresh rate.
 */
@Singleton
class MpvEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheStorage: CacheStorage,
) {
    /**
     * The upscaling shaders from `assets/shaders`, copied out once per install
     * because mpv reads shaders from files, not from the APK.
     */
    val shaderDirectory: String by lazy {
        val dir = File(context.filesDir, SHADER_DIR)
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val marker = File(dir, ".installed")
        if (!marker.exists() || marker.readText() != installedAt) {
            dir.deleteRecursively()
            dir.mkdirs()
            context.assets.list(SHADER_DIR).orEmpty().forEach { name ->
                context.assets.open("$SHADER_DIR/$name").use { input -> File(dir, name).outputStream().use(input::copyTo) }
            }
            marker.writeText(installedAt)
        }
        dir.absolutePath
    }

    /**
     * The device's trusted CAs as one PEM file, for mpv's TLS (ffmpeg checks HLS
     * segment servers' certificates); written once per process. Null when none were found.
     */
    val caFile: File? by lazy {
        val certificates = systemCaCertificates()
        if (certificates.isEmpty()) {
            Log.w(TAG, "no CA certificates found; https streams will not verify")
            return@lazy null
        }
        val file = File(context.filesDir, CA_FILE)
        val partial = File(context.filesDir, "$CA_FILE.tmp")
        partial.writeText(certificates.joinToString("\n", postfix = "\n"))
        if (partial.renameTo(file)) file else null
    }

    fun options(settings: AppSettings): Map<String, String> =
        mpvOptions(settings, cacheStorage.maxCacheMegabytes(), shaderDirectory, displayFps())

    private fun displayFps(): Float? =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate

    private companion object {
        const val TAG = "MpvEnvironment"
        const val SHADER_DIR = "shaders"
        const val CA_FILE = "ca-certificates.pem"
    }
}
