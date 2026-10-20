package dev.wplayer.app.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.di.IoDispatcher
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Turns URIs handed over by other apps into something libmpv can open. */
class ExternalUriResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * `content://` is opened here and passed as `fdclose://<fd>`: libmpv takes
     * ownership of the descriptor and closes it. Call only when about to load.
     */
    suspend fun toPlayerUri(uri: Uri): String = when (uri.scheme) {
        "content" -> withContext(io) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException(uri.toString())
            "fdclose://${descriptor.use { it.detachFd() }}"
        }
        "file" -> uri.path ?: throw FileNotFoundException(uri.toString())
        else -> uri.toString()
    }
}
