package dev.wplayer.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: Int, usedAt: Long): File =
        folder.newFile(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(usedAt)
        }

    private fun remaining(): Set<String> = folder.root.listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun `drops the least recently viewed pictures that no longer fit`() {
        val files = listOf(
            file("old.jpg", 100, usedAt = 1_000),
            file("middle.jpg", 200, usedAt = 2_000),
            file("recent.jpg", 150, usedAt = 3_000),
            file("new.jpg", 100, usedAt = 4_000),
        )
        trimToSize(files, maxBytes = 360, keep = files.last())
        assertEquals(setOf("new.jpg", "recent.jpg", "old.jpg"), remaining())
    }

    @Test
    fun `keeps the picture just copied even when it alone is over the limit`() {
        val files = listOf(file("old.jpg", 100, usedAt = 1_000), file("huge.dng", 500, usedAt = 2_000))
        trimToSize(files, maxBytes = 300, keep = files.last())
        assertEquals(setOf("huge.dng"), remaining())
    }
}
