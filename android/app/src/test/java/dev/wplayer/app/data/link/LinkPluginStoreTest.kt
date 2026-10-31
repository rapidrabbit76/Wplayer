package dev.wplayer.app.data.link

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wplayer.app.data.toHex
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric only for android.util.Log, which the store uses for skipped folders. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class LinkPluginStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("link-plugins") }
    private val store by lazy { LinkPluginStore(root, hostApi = 1) }

    private fun manifest(id: String = "clips", version: String = "1.0.0", api: Int = 1) =
        """{"id":"$id","name":"Clips","version":"$version","api":$api,"matches":["^https://video\\.example\\.test/"]}"""

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun plugin(version: String = "1.0.0", script: String = "function resolve(url) { return { streams: [] } }") =
        zip("manifest.json" to manifest(version = version), "main.js" to script)

    /** Every file under [root], with its text, so a test sees the whole stored state. */
    private fun storedFiles(): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }

    @Test
    fun `installing the same package twice leaves the same plugin`() {
        val zip = plugin()

        store.install(zip)
        val once = storedFiles()
        store.install(zip)

        assertEquals(once, storedFiles())
        assertEquals(listOf("clips"), store.readAll().map { it.id })
        assertEquals(setOf("clips/manifest.json", "clips/main.js"), once.keys)
    }

    @Test
    fun `a newer version replaces the old one whole`() {
        store.install(plugin("1.0.0", script = "// one"))
        store.install(plugin("1.1.0", script = "// two"))

        assertEquals(PluginVersion.parse("1.1.0"), store.readAll().single().version)
        assertEquals("// two", store.script("clips"))
        assertEquals(setOf("clips/manifest.json", "clips/main.js"), storedFiles().keys)
    }

    @Test
    fun `bad packages are refused and leave the installed plugin alone`() {
        store.install(plugin(script = "// kept"))
        val before = storedFiles()
        val bad = mapOf(
            "api too new" to zip("manifest.json" to manifest(api = 2), "main.js" to "x"),
            "bad id" to zip("manifest.json" to manifest(id = "Clips!"), "main.js" to "x"),
            "extra entry" to zip("manifest.json" to manifest(), "main.js" to "x", "README" to "x"),
            "folder entry" to zip("manifest.json" to manifest(), "main.js" to "x", "lib/" to ""),
            "no script" to zip("manifest.json" to manifest()),
            "no manifest" to zip("main.js" to "x"),
            "path traversal" to zip("manifest.json" to manifest(), "../main.js" to "x"),
            "nested script" to zip("manifest.json" to manifest(), "clips/main.js" to "x"),
            "script too large" to zip("manifest.json" to manifest(), "main.js" to "x".repeat(2 * 1024 * 1024 + 1)),
            "not a zip" to "plain text".encodeToByteArray(),
        )
        for ((case, zip) in bad) {
            assertThrows(case, InvalidPluginException::class.java) { store.install(zip) }
        }

        assertEquals(before, storedFiles())
        assertEquals("// kept", store.script("clips"))
    }

    @Test
    fun `an install cut off after moving the old version aside keeps the old version`() {
        store.install(plugin(script = "// old"))
        // The state a crash leaves between moving the old folder aside and renaming the new one in.
        File(root, "clips").renameTo(File(root, ".aside-clips"))
        File(root, ".staging-clips").mkdirs()
        File(root, ".staging-clips/main.js").writeText("// half")

        assertEquals(listOf("clips"), store.readAll().map { it.id })
        assertEquals("// old", store.script("clips"))
        assertEquals(setOf("clips/manifest.json", "clips/main.js"), storedFiles().keys)
    }

    @Test
    fun `removing a plugin deletes its folder`() {
        store.install(plugin())
        store.remove("clips")

        assertEquals(emptyList<LinkPluginManifest>(), store.readAll())
        assertEquals(emptyMap<String, String>(), storedFiles())
    }

    @Test
    fun `a download must be the package the index lists`() {
        val zip = plugin("1.2")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(zip).toHex()
        val listed = PluginOffer(LinkPluginManifest.parse(manifest(version = "1.2")), "clips.zip", sha256)

        requireOffered(listed, zip)
        assertThrows(InvalidPluginException::class.java) { requireOffered(listed.copy(sha256 = "0".repeat(64)), zip) }
        val otherVersion = listed.copy(manifest = LinkPluginManifest.parse(manifest(version = "1.3")))
        assertThrows(InvalidPluginException::class.java) { requireOffered(otherVersion, zip) }
    }
}
