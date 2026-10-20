package dev.wplayer.app.data.transcription

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtWriterTest {
    @Test
    fun `formats times as SRT`() {
        assertEquals("00:00:00,000", SrtWriter.srtTime(0))
        assertEquals("01:02:03,045", SrtWriter.srtTime(3_723_045))
        assertEquals("00:00:00,000", SrtWriter.srtTime(-5))
    }

    @Test
    fun `numbers cues and trims their text`() {
        val out = StringWriter()
        SrtWriter(out).use { writer ->
            writer.add(2_810, 7_500, " 안녕하세요? ")
            writer.add(60_000, 61_000, "둘째 줄")
        }
        assertEquals(
            "1\n00:00:02,810 --> 00:00:07,500\n안녕하세요?\n\n2\n00:01:00,000 --> 00:01:01,000\n둘째 줄\n\n",
            out.toString(),
        )
    }
}
