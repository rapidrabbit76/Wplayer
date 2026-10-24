package dev.wplayer.app.data

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePagesTest {
    private fun entry(name: String, modified: Long = 0, dir: Boolean = false) =
        MediaEntry(name, "/photos/$name", MediaEntry.kindOf(name, dir), size = 1, modifiedAtMillis = modified)

    private val folder = listOf(
        entry("IMG_10.jpg", modified = 100),
        entry("clip.mp4", modified = 400),
        entry("IMG_2.HEIC", modified = 300),
        entry("Trip", dir = true),
        entry(".thumb.png", modified = 500),
        entry("notes.txt"),
        entry("scan.png", modified = 200),
    )

    private val unlocked = LockState()

    @Test
    fun `lists only the pictures, in the browser's natural name order`() {
        val names = imagePages("nas", folder, BrowserSettings(), unlocked).map { it.name }
        assertEquals(listOf("IMG_2.HEIC", "IMG_10.jpg", "scan.png"), names)
    }

    @Test
    fun `follows the browser's sort, newest first here`() {
        val names = imagePages("nas", folder, BrowserSettings(sortField = SortField.DATE, ascending = false), unlocked).map { it.name }
        assertEquals(listOf("IMG_2.HEIC", "scan.png", "IMG_10.jpg"), names)
    }

    @Test
    fun `hidden pictures join only when the browser shows hidden files`() {
        val names = imagePages("nas", folder, BrowserSettings(showHiddenFiles = true), unlocked).map { it.name }
        assertEquals(listOf(".thumb.png", "IMG_2.HEIC", "IMG_10.jpg", "scan.png"), names)
    }

    @Test
    fun `a locked folder shows no pictures until it is unlocked`() {
        val folders = setOf(FolderKey("nas", "/photos"))
        assertEquals(emptyList<ImagePage>(), imagePages("nas", folder, BrowserSettings(), LockState(folders, unlocked = false)))
        assertEquals(3, imagePages("nas", folder, BrowserSettings(), LockState(folders, unlocked = true)).size)
    }

    @Test
    fun `a lock on another source leaves the pictures alone`() {
        val elsewhere = LockState(setOf(FolderKey("other", "/photos")), unlocked = false)
        assertEquals(3, imagePages("nas", folder, BrowserSettings(), elsewhere).size)
    }
}
