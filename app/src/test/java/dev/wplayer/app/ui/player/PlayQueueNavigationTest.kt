package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.RepeatMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayQueueNavigationTest {
    private val queue = listOf("1", "2", "3").map { MediaRef("s", "/$it.mkv", "$it.mkv", isDirectory = false) }

    @Test
    fun `next and previous move one file`() {
        assertEquals("/3.mkv", nextInQueue(queue, "/2.mkv", RepeatMode.OFF, shuffle = false)?.path)
        assertEquals("/1.mkv", previousInQueue(queue, "/2.mkv", RepeatMode.OFF)?.path)
    }

    @Test
    fun `the ends stop without repeat and wrap with repeat all`() {
        assertNull(nextInQueue(queue, "/3.mkv", RepeatMode.OFF, shuffle = false))
        assertNull(previousInQueue(queue, "/1.mkv", RepeatMode.OFF))
        assertEquals("/1.mkv", nextInQueue(queue, "/3.mkv", RepeatMode.ALL, shuffle = false)?.path)
        assertEquals("/3.mkv", previousInQueue(queue, "/1.mkv", RepeatMode.ALL)?.path)
    }

    @Test
    fun `shuffle never picks the current file`() {
        repeat(50) { seed ->
            assertNotEquals("/2.mkv", nextInQueue(queue, "/2.mkv", RepeatMode.OFF, shuffle = true, Random(seed))?.path)
        }
    }

    @Test
    fun `a single file has nothing next even when shuffling`() {
        assertNull(nextInQueue(queue.take(1), "/1.mkv", RepeatMode.OFF, shuffle = true))
    }
}
