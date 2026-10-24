package dev.wplayer.app.ui.player

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.ExternalUriResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PlaybackRequestTest {
    class NamingProvider : ContentProvider() {
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor {
            queries++
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Holiday.mkv")) }
        }

        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/x-matroska"
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0

        companion object {
            var queries = 0
        }
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val shared = Uri.parse("content://test.media/document/1234")

    @Before
    fun registerProvider() {
        NamingProvider.queries = 0
        Robolectric.setupContentProvider(NamingProvider::class.java, "test.media")
    }

    @Test
    fun `opening a shared file does not wait for its provider`() {
        val request = PlaybackRequest.from(Intent(Intent.ACTION_VIEW, shared))

        assertEquals(PlaybackRequest.FromUri(shared, "1234"), request)
        assertEquals(0, NamingProvider.queries)
    }

    @Test
    fun `the provider's name for a shared file becomes its title`() = runBlocking {
        val resolver = ExternalUriResolver(app, Dispatchers.IO)

        assertEquals("Holiday.mkv", resolver.displayName(shared))
        assertEquals(null, resolver.displayName(Uri.parse("https://example.com/v/clip.mp4")))
    }
}
