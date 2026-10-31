package dev.wplayer.app.data

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `a PDF's pages are named by their number`() {
        assertEquals(listOf(ImagePage("1", "1.png"), ImagePage("2", "2.png")), pdfPages(2))
    }

    @Test
    fun `the PDF page on screen is drawn first, then the nearest, later ones before earlier ones`() {
        assertEquals(4, nextPdfPage(setOf(2, 4, 5, 6), current = 4))
        assertEquals(5, nextPdfPage(setOf(3, 5, 6), current = 4))
        assertEquals(2, nextPdfPage(setOf(2, 7), current = 4))
        assertEquals(null, nextPdfPage(emptySet(), current = 4))
    }

    @Test
    fun `PDF pages are drawn sharp but never huge`() {
        // A4 in points: about 2375 x 3361, under eight million pixels.
        val (w, h) = renderSize(595, 842)
        assertTrue(w * h <= 8_000_000 && w > 2_300 && h > 3_300)
        // A long strip is held to 4096 pixels on its long side.
        assertEquals(4096, renderSize(100, 20_000).second)
    }
}
