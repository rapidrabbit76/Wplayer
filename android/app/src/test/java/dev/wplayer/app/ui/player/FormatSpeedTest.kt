package dev.wplayer.app.ui.player

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatSpeedTest {
    private val original = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun `shows one decimal, or two when the speed needs them`() {
        assertEquals("1.0x", formatSpeed(1.0))
        assertEquals("0.5x", formatSpeed(0.5))
        assertEquals("1.25x", formatSpeed(1.25))
        assertEquals("0.75x", formatSpeed(0.75))
        assertEquals("3.0x", formatSpeed(3.0))
        assertEquals("10.0x", formatSpeed(10.0))
    }

    @Test
    fun `rounds a speed that arrives as an inexact float to two decimals`() {
        assertEquals("1.1x", formatSpeed(1.1f.toDouble()))
        assertEquals("1.33x", formatSpeed(4.0 / 3))
    }

    @Test
    fun `uses a dot in locales that write a decimal comma`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.25x", formatSpeed(1.25))
    }
}
