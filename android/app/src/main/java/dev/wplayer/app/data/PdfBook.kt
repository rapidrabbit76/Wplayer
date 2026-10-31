package dev.wplayer.app.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A document Android cannot open: protected by a password, or damaged. */
class UnreadableDocumentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A PDF on device storage, its pages drawn as pictures for the image viewer.
 * [PdfRenderer] draws one page at a time and must not be closed mid-page, so
 * every use takes [lock]. Call from a background thread.
 */
class PdfBook private constructor(private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val lock = Mutex()

    val pageCount: Int get() = renderer.pageCount

    /** Draws page [index] (from 0) on white and writes it to [target] as a PNG. */
    suspend fun render(index: Int, target: File) = lock.withLock {
        renderer.openPage(index).use { page ->
            val (width, height) = renderSize(page.width, page.height)
            val bitmap = createBitmap(width, height)
            try {
                // Pages are transparent where nothing is drawn; paper is white.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                target.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("cannot write page $index")
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun close() = lock.withLock {
        renderer.close()
        descriptor.close()
    }

    companion object {
        /** Opens [file]; [UnreadableDocumentException] when it is protected or not a PDF Android reads. */
        fun open(file: File): PdfBook {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                descriptor.close()
                // A password-protected PDF throws SecurityException, a damaged one IOException.
                if (e is SecurityException || e is IOException) throw UnreadableDocumentException("cannot open ${file.name}", e)
                throw e
            }
            return PdfBook(descriptor, renderer)
        }
    }
}

/**
 * The size in pixels to draw a page of [widthPoints] × [heightPoints] at:
 * sharp enough to zoom into on a tablet, at most [MAX_PAGE_PIXELS] in all
 * and [MAX_PAGE_SIDE] on the longer side.
 */
internal fun renderSize(widthPoints: Int, heightPoints: Int): Pair<Int, Int> {
    val w = widthPoints.coerceAtLeast(1).toDouble()
    val h = heightPoints.coerceAtLeast(1).toDouble()
    val scale = min(MAX_PAGE_SIDE / max(w, h), sqrt(MAX_PAGE_PIXELS / (w * h)))
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}

private const val MAX_PAGE_PIXELS = 8_000_000.0
private const val MAX_PAGE_SIDE = 4096.0
