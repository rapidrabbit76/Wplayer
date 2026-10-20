package dev.wplayer.app.data

import androidx.datastore.core.CorruptionException
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.SortField
import dev.wplayer.app.data.model.ThemeMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsSerializerTest {
    private suspend fun read(json: String) = AppSettingsSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))

    @Test
    fun `settings survive a round trip`() = runTest {
        val settings = AppSettings().let {
            it.copy(appearance = it.appearance.copy(themeMode = ThemeMode.DARK), browser = it.browser.copy(sortField = SortField.SIZE))
        }
        val out = ByteArrayOutputStream()
        AppSettingsSerializer.writeTo(settings, out)
        assertEquals(settings, read(out.toString(Charsets.UTF_8)))
    }

    @Test
    fun `missing fields take defaults and unknown ones are ignored`() = runTest {
        val settings = read("""{"appearance":{"themeMode":"LIGHT"},"fromTheFuture":1}""")
        assertEquals(ThemeMode.LIGHT, settings.appearance.themeMode)
        assertEquals(AppSettings().playback, settings.playback)
    }

    @Test
    fun `an unknown enum value falls back to its default`() = runTest {
        assertEquals(AppSettings().appearance.themeMode, read("""{"appearance":{"themeMode":"SEPIA"}}""").appearance.themeMode)
    }

    @Test(expected = CorruptionException::class)
    fun `garbage is reported as corruption`() = runTest {
        read("not json")
    }
}
