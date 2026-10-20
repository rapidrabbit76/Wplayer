package dev.wplayer.core

import dev.wplayer.core.ffi.initCore

/**
 * Loads `libwplayer_core.so` through [System.loadLibrary] so its `JNI_OnLoad`
 * runs (registering the JavaVM with FFmpeg) before any UniFFI call. JNA then
 * reuses the already-loaded image.
 */
object NativeCore {
    private val loaded: Unit by lazy {
        System.loadLibrary("wplayer_core")
        initCore()
    }

    fun ensureLoaded() = loaded
}
