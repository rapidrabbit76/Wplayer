package dev.wplayer.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Opens EPUB files on device storage with Readium. */
@Singleton
class EpubBooks @Inject constructor(@ApplicationContext context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null))

    /** The book in [file]; [UnreadableDocumentException] when it is protected or not an EPUB Readium reads. */
    suspend fun open(file: File): Publication {
        val asset = assetRetriever.retrieve(file, MediaType.EPUB)
            .getOrElse { throw UnreadableDocumentException("cannot read ${file.name}: ${it.message}") }
        return opener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close()
            throw UnreadableDocumentException("cannot open ${file.name}: ${it.message}")
        }
    }
}

/** Where a book was left, as kept with the reading positions. */
fun Locator.toSaved(): String = toJSON().toString()

/** A place kept by [toSaved], or null when it does not read as one. */
fun savedLocator(text: String): Locator? = runCatching { Locator.fromJSON(JSONObject(text)) }.getOrNull()
