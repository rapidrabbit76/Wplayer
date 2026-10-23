package dev.wplayer.app.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontNamesTest {
    @Test
    fun `prefers the Korean Windows family name`() {
        val font = sfnt(
            NameRecord(platform = 1, language = 0, nameId = 1, text = "DX Movie Mac"),
            NameRecord(platform = 3, language = 0x0409, nameId = 1, text = "DXYeonghwajamak Medium"),
            NameRecord(platform = 3, language = 0x0412, nameId = 1, text = "DX영화자막 Medium"),
            NameRecord(platform = 3, language = 0x0412, nameId = 4, text = "DX영화자막 Medium Regular"),
        )
        assertEquals("DX영화자막 Medium", fontFamilyName(font))
    }

    @Test
    fun `falls back to English, then any family name`() {
        assertEquals(
            "Movie Sans",
            fontFamilyName(sfnt(NameRecord(3, 0x0411, 1, "ムービー"), NameRecord(3, 0x0409, 1, "Movie Sans"))),
        )
        assertEquals("Old Mac Font", fontFamilyName(sfnt(NameRecord(1, 0, 1, "Old Mac Font"))))
    }

    @Test
    fun `reads the first font of a collection`() {
        val header = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(0x74746366) // "ttcf"
                writeInt(0x00010000)
                writeInt(1)
                writeInt(16) // the font follows this 16-byte header
            }
        }.toByteArray()
        // Offsets inside a collection's fonts count from the file's start.
        assertEquals("Collected", fontFamilyName(header + sfnt(NameRecord(3, 0x0409, 1, "Collected"), base = header.size)))
    }

    @Test
    fun `rejects files that are not fonts`() {
        assertNull(fontFamilyName("1\n00:00:01,000 --> 00:00:02,000\n".toByteArray()))
        assertNull(fontFamilyName(ByteArray(0)))
        // A font header whose tables point past the end of the file.
        assertNull(fontFamilyName(sfnt(NameRecord(3, 0x0409, 1, "Cut")).copyOf(40)))
    }

    @Test
    fun `font file names keep a font extension and lose folders`() {
        assertEquals("DX영화자막Medium.ttf", SubtitleFontStore.safeFileName("DX영화자막Medium.ttf"))
        assertEquals("a_b.otf", SubtitleFontStore.safeFileName("fonts/a:b.otf"))
        assertEquals("subtitle.ttf", SubtitleFontStore.safeFileName("subtitle"))
    }

    private data class NameRecord(val platform: Int, val language: Int, val nameId: Int, val text: String)

    /** A font with only a `name` table, as its first bytes start at [base] in the file. */
    private fun sfnt(vararg names: NameRecord, base: Int = 0): ByteArray {
        val strings = names.map { if (it.platform == 3) it.text.toByteArray(Charsets.UTF_16BE) else it.text.toByteArray(Charsets.ISO_8859_1) }
        val nameTableOffset = base + 12 + 16
        val stringsOffset = 6 + names.size * 12
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x00010000)
            writeShort(1) // tables
            writeShort(0); writeShort(0); writeShort(0)
            writeInt(0x6E616D65) // "name"
            writeInt(0)
            writeInt(nameTableOffset)
            writeInt(stringsOffset + strings.sumOf { it.size })
            writeShort(0) // format
            writeShort(names.size)
            writeShort(stringsOffset)
            var offset = 0
            names.forEachIndexed { i, name ->
                writeShort(name.platform)
                writeShort(if (name.platform == 3) 1 else 0)
                writeShort(name.language)
                writeShort(name.nameId)
                writeShort(strings[i].size)
                writeShort(offset)
                offset += strings[i].size
            }
            strings.forEach(::write)
        }
        return out.toByteArray()
    }
}
