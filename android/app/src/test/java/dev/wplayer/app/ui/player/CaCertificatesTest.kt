package dev.wplayer.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CaCertificatesTest {
    private val first = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUQ0FfZXhhbXBsZQ==\nAbCd+/9=\n-----END CERTIFICATE-----"
    private val second = "-----BEGIN CERTIFICATE-----\nMIICAjCCAaigAwIBAgIUU2Vjb25k\n-----END CERTIFICATE-----"

    @Test
    fun `only the PEM blocks of a CA file are kept`() {
        val androidStyle = """
            $first
            Certificate:
                Data:
                    Version: 3 (0x2)
                    Issuer: CN = Example Root CA
            SHA1 Fingerprint=00:11:22
        """.trimIndent()

        assertEquals(listOf(first), pemCertificates(androidStyle))
    }

    @Test
    fun `several blocks come out in order and text without one gives none`() {
        assertEquals(listOf(first, second), pemCertificates("$first\nnotes\n$second\n"))
        assertEquals(emptyList<String>(), pemCertificates("Certificate:\n    Data: none"))
        assertEquals(emptyList<String>(), pemCertificates("-----BEGIN CERTIFICATE-----\nMIIB\n"))
    }
}
