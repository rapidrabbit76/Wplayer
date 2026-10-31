package dev.wplayer.app.data.link

import dev.wplayer.core.ffi.LinkStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPluginsTest {
    private fun manifestJson(
        id: String = "clips",
        version: String = "1.0.0",
        api: Int = 1,
        matches: String = """["^https://video\\.example\\.test/watch/"]""",
        name: String = "Clips",
    ) = """{"id":"$id","name":"$name","version":"$version","api":$api,"description":"Example clips","matches":$matches}"""

    private fun manifest(id: String = "clips", version: String = "1.0.0", api: Int = 1, pattern: String = "^https://video\\.example\\.test/") =
        LinkPluginManifest(id, id.uppercase(), PluginVersion.parse(version), api, "", listOf(pattern), emptyList())

    private fun offer(id: String = "clips", version: String = "1.0.0", api: Int = 1) = PluginOffer(manifest(id, version, api), "$id-$version.zip", "00")

    @Test
    fun `a valid manifest parses with its fields`() {
        val manifest = LinkPluginManifest.parse(manifestJson())

        assertEquals("clips", manifest.id)
        assertEquals("Clips", manifest.name)
        assertEquals(PluginVersion.parse("1.0.0"), manifest.version)
        assertEquals("Example clips", manifest.description)
        assertTrue(manifest.takes("https://video.example.test/watch/123"))
        assertFalse(manifest.takes("https://other.example.test/watch/123"))
    }

    @Test
    fun `manifests that break the format are refused`() {
        val broken = mapOf(
            "upper-case id" to manifestJson(id = "Clips"),
            "id starting with a dash" to manifestJson(id = "-clips"),
            "id with a slash" to manifestJson(id = "../clips"),
            "id too long" to manifestJson(id = "a".repeat(65)),
            "five version parts" to manifestJson(version = "1.2.3.4.5"),
            "version with letters" to manifestJson(version = "1.0-beta"),
            "no patterns" to manifestJson(matches = "[]"),
            "broken pattern" to manifestJson(matches = """["(unclosed"]"""),
            "blank name" to manifestJson(name = " "),
            "api zero" to manifestJson(api = 0),
            "not json" to "manifest",
            "missing id" to """{"name":"Clips","version":"1","api":1,"matches":["x"]}""",
        )
        for ((case, json) in broken) {
            assertThrows(case, InvalidPluginException::class.java) { LinkPluginManifest.parse(json) }
        }
    }

    @Test
    fun `a plugin needing a newer host is refused`() {
        val manifest = LinkPluginManifest.parse(manifestJson(api = 2))

        assertThrows(InvalidPluginException::class.java) { manifest.requireRunnableOn(1) }
        manifest.requireRunnableOn(2)
    }

    @Test
    fun `versions compare number by number`() {
        val ordered = listOf("0.9", "1", "1.0.1", "1.2", "1.10", "2.0.0.1", "10")
        assertEquals(ordered, ordered.reversed().map(PluginVersion::parse).sorted().map { it.toString() })
        assertEquals(PluginVersion.parse("1.2"), PluginVersion.parse("1.2.0.0"))
        assertEquals(PluginVersion.parse("1.2").hashCode(), PluginVersion.parse("1.2.0").hashCode())
    }

    @Test
    fun `statuses compare installed plugins with the index`() {
        val installed = listOf(manifest("current", "1.0"), manifest("old", "1.0"), manifest("blocked", "1.0"), manifest("unlisted", "3"))
        val offers = listOf(
            offer("current", "1.0"),
            offer("old", "1.1"),
            offer("blocked", "2.0", api = 9),
            offer("fresh", "1.0"),
            offer("future", "1.0", api = 9),
        )

        val statuses = pluginStatuses(installed, offers, hostApi = 1)

        assertEquals(PluginStatus.UpToDate, statuses["current"])
        assertEquals(PluginStatus.UpdateAvailable(offers[1]), statuses["old"])
        assertEquals(PluginStatus.NeedsNewerApp(offers[2]), statuses["blocked"])
        assertEquals(PluginStatus.UpToDate, statuses["unlisted"])
        assertEquals(PluginStatus.NotInstalled(offers[3]), statuses["fresh"])
        assertEquals(PluginStatus.NeedsNewerApp(offers[4]), statuses["future"])
    }

    @Test
    fun `an older index version never offers a downgrade`() {
        assertEquals(PluginStatus.UpToDate, pluginStatuses(listOf(manifest("clips", "2.0")), listOf(offer("clips", "1.9")), 1)["clips"])
    }

    @Test
    fun `the index keeps good entries and drops broken ones`() {
        val index = """
            {"format":1,"plugins":[
              {"id":"clips","name":"Clips","version":"1.2","api":1,"matches":["^https://video\\.example\\.test/"],"file":"clips-1.2.zip","sha256":"ABCD"},
              {"id":"Bad Id","name":"Bad","version":"1","api":1,"matches":["x"],"file":"bad.zip","sha256":"00"}
            ]}
        """.trimIndent()

        val offers = parseIndex(index)

        assertEquals(listOf("clips"), offers.map { it.manifest.id })
        assertEquals("clips-1.2.zip", offers.single().file)
        assertEquals("abcd", offers.single().sha256)
        assertThrows(InvalidPluginException::class.java) { parseIndex("""{"format":2,"plugins":[]}""") }
    }

    @Test
    fun `the first enabled plugin that takes the address is used`() {
        val first = manifest("first", pattern = "^https://video\\.example\\.test/watch")
        val second = manifest("second", pattern = "example\\.test")
        val plugins = listOf(first, second)

        assertEquals(first, pluginFor("https://video.example.test/watch/1", plugins, disabled = emptySet()))
        assertEquals(second, pluginFor("https://video.example.test/watch/1", plugins, disabled = setOf("first")))
        assertEquals(second, pluginFor("https://www.example.test/page", plugins, disabled = emptySet()))
        assertNull(pluginFor("https://elsewhere.test/watch/1", plugins, disabled = emptySet()))
    }

    @Test
    fun `the stream is the first within the quality limit, else the first`() {
        fun stream(height: Int?) = LinkStream("https://cdn.example.test/$height.m3u8", null, height?.toUInt(), emptyMap())
        val streams = listOf(stream(1080), stream(null), stream(720), stream(480))

        assertEquals(streams[0], chooseStream(streams, maxHeight = null))
        assertEquals(streams[0], chooseStream(streams, maxHeight = 1080))
        assertEquals(streams[2], chooseStream(streams, maxHeight = 720))
        assertEquals(streams[3], chooseStream(streams, maxHeight = 600))
        assertEquals(streams[0], chooseStream(streams, maxHeight = 240))
        assertNull(chooseStream(emptyList(), maxHeight = 720))
    }

    @Test
    fun `the first web address is taken from shared text`() {
        assertEquals("https://video.example.test/watch/1", firstWebUrl("A clip https://video.example.test/watch/1"))
        assertEquals("https://video.example.test/watch/1?t=3", firstWebUrl("See https://video.example.test/watch/1?t=3."))
        assertEquals("https://video.example.test/a_(b)", firstWebUrl("(https://video.example.test/a_(b))"))
        assertEquals("http://video.example.test/", firstWebUrl("HTTP://video.example.test/ and https://second.example.test/"))
        assertEquals("https://video.example.test/x", firstWebUrl("\"https://video.example.test/x\""))
        assertNull(firstWebUrl("no address here"))
        assertNull(firstWebUrl("ftp://files.example.test/a.mp4"))
    }
}
