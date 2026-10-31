package dev.wplayer.core.player

import android.view.Surface

/**
 * Converts a [Surface] into the JNI global reference libmpv expects as `wid`.
 * Every [acquire] must be balanced by one [release] after the player detaches.
 */
internal object SurfaceBridge {
    fun acquire(surface: Surface): Long = nativeAcquire(surface)

    fun release(reference: Long) = nativeRelease(reference)

    @JvmStatic
    private external fun nativeAcquire(surface: Surface): Long

    @JvmStatic
    private external fun nativeRelease(reference: Long)
}
