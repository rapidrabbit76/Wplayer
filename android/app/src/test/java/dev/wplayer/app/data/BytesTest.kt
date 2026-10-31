package dev.wplayer.app.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BytesTest {
    @Test
    fun `writes a SHA-256 the way checksums are published`() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.toHex())
    }

    @Test
    fun `hands over every byte when the last chunk is short`() {
        val data = ByteArray(10) { it.toByte() }
        val copy = ByteArrayOutputStream()
        data.inputStream().forEachChunk(bufferBytes = 4) { buffer, n -> copy.write(buffer, 0, n) }
        assertArrayEquals(data, copy.toByteArray())
    }
}
