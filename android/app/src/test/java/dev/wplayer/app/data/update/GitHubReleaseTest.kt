package dev.wplayer.app.data.update

import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Trimmed from a real `releases/latest` answer. */
    private val release = json.decodeFromString<GitHubRelease>(
        """
        {
          "tag_name": "v0.1.5",
          "name": "Wplayer 0.1.5",
          "draft": false,
          "assets": [
            {"id": 1, "name": "wplayer-v0.1.5.apk", "size": 91226112,
             "url": "https://api.github.com/repos/rapidrabbit76/Wplayer/releases/assets/1",
             "browser_download_url": "https://github.com/rapidrabbit76/Wplayer/releases/download/v0.1.5/wplayer-v0.1.5.apk"},
            {"id": 2, "name": "latest.json", "size": 400,
             "url": "https://api.github.com/repos/rapidrabbit76/Wplayer/releases/assets/2"}
          ]
        }
        """,
    )

    @Test
    fun `finds an asset by its file name`() {
        assertEquals("https://api.github.com/repos/rapidrabbit76/Wplayer/releases/assets/2", release.asset("latest.json").url)
        assertEquals(91226112L, release.asset("wplayer-v0.1.5.apk").size)
    }

    @Test
    fun `a missing asset fails with the release named`() {
        val error = assertThrows(IOException::class.java) { release.asset("wplayer-v0.1.4.apk") }
        assertEquals("v0.1.5 has no wplayer-v0.1.4.apk", error.message)
    }
}
