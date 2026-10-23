package dev.wplayer.app.data

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * The family name of a TrueType or OpenType font (or a collection's first
 * font), read from its `name` table: the name libass looks the font up by.
 * Windows names come first, as libass reads those; a Korean one is preferred,
 * then an English one. Null when [data] is not a font.
 */
internal fun fontFamilyName(data: ByteArray): String? = try {
    val font = ByteBuffer.wrap(data)
    val start = when (font.getInt(0)) {
        TTC_TAG -> font.getInt(TTC_FIRST_FONT_OFFSET)
        TRUETYPE, OPENTYPE, APPLE_TRUETYPE -> 0
        else -> null
    }
    start?.let { familyName(data, font, it) }
} catch (_: IndexOutOfBoundsException) {
    null
} catch (_: BufferUnderflowException) {
    null
}

private fun familyName(data: ByteArray, font: ByteBuffer, start: Int): String? {
    val tables = font.getShort(start + 4).toInt() and 0xFFFF
    val nameTable = (0 until tables)
        .map { start + TABLE_DIRECTORY_OFFSET + it * TABLE_RECORD_SIZE }
        .firstOrNull { font.getInt(it) == NAME_TAG }
        ?.let { font.getInt(it + 8) }
        ?: return null
    val count = font.getShort(nameTable + 2).toInt() and 0xFFFF
    val strings = nameTable + (font.getShort(nameTable + 4).toInt() and 0xFFFF)
    val families = (0 until count).mapNotNull { index ->
        val record = nameTable + NAME_HEADER_SIZE + index * NAME_RECORD_SIZE
        fun field(offset: Int) = font.getShort(record + offset).toInt() and 0xFFFF
        if (field(6) != FAMILY_NAME_ID) return@mapNotNull null
        val bytes = data.copyOfRange(strings + field(10), strings + field(10) + field(8))
        val name = when (field(0)) {
            PLATFORM_WINDOWS -> String(bytes, Charsets.UTF_16BE)
            PLATFORM_MAC -> String(bytes, MAC_ROMAN)
            else -> return@mapNotNull null
        }.trim()
        if (name.isEmpty()) null else NameRecord(field(0), field(4), name)
    }
    return families.minByOrNull { it.rank }?.name
}

private data class NameRecord(val platform: Int, val language: Int, val name: String) {
    val rank: Int
        get() = when {
            platform == PLATFORM_WINDOWS && language == LANGUAGE_KOREAN -> 0
            platform == PLATFORM_WINDOWS && language == LANGUAGE_ENGLISH_US -> 1
            platform == PLATFORM_WINDOWS -> 2
            else -> 3
        }
}

private const val TRUETYPE = 0x00010000
private const val OPENTYPE = 0x4F54544F // "OTTO"
private const val APPLE_TRUETYPE = 0x74727565 // "true"
private const val TTC_TAG = 0x74746366 // "ttcf"
private const val TTC_FIRST_FONT_OFFSET = 12
private const val NAME_TAG = 0x6E616D65 // "name"
private const val TABLE_DIRECTORY_OFFSET = 12
private const val TABLE_RECORD_SIZE = 16
private const val NAME_HEADER_SIZE = 6
private const val NAME_RECORD_SIZE = 12
private const val FAMILY_NAME_ID = 1
private const val PLATFORM_MAC = 1
private const val PLATFORM_WINDOWS = 3
private const val LANGUAGE_ENGLISH_US = 0x0409
private const val LANGUAGE_KOREAN = 0x0412

// Mac names of old fonts are ASCII in practice; Latin-1 keeps them and never fails.
private val MAC_ROMAN: Charset = Charsets.ISO_8859_1
