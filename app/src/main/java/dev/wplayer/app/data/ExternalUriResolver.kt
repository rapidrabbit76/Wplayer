package dev.wplayer.app.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.di.IoDispatcher
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

sealed interface MediaInput {
    /** A path or URL libmpv opens itself. */
    data class Location(val uri: String) : MediaInput

    /** A `content://` URI, which libmpv cannot open: [ExternalUriResolver.openFile] opens it for the engine. */
    data class Content(val uri: Uri) : MediaInput
}

/** Turns URIs handed over by other apps into something libmpv can open. */
class ExternalUriResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun resolve(uri: Uri): MediaInput = when (uri.scheme) {
        "content" -> MediaInput.Content(uri)
        "file" -> MediaInput.Location(uri.path ?: throw FileNotFoundException(uri.toString()))
        else -> MediaInput.Location(uri.toString())
    }

    /** Call only when about to load; the file is closed again if the caller is cancelled before receiving it. */
    suspend fun openFile(uri: Uri): ParcelFileDescriptor {
        var opened: ParcelFileDescriptor? = null
        try {
            withContext(io) { opened = context.contentResolver.openFileDescriptor(uri, "r") }
        } catch (e: CancellationException) {
            opened?.close()
            throw e
        }
        return opened ?: throw FileNotFoundException(uri.toString())
    }

    /** The provider's name for a `content://` file, when it offers one; providers may refuse or return null. */
    suspend fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return withContext(io) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        }
    }
}
