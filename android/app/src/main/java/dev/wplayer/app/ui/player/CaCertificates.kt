package dev.wplayer.app.ui.player

import java.io.File

/**
 * The PEM certificates in [text]. Android's CA files hold one PEM block each,
 * followed by a text dump of the certificate, which TLS libraries would not parse.
 */
internal fun pemCertificates(text: String): List<String> = PEM_BLOCK.findAll(text).map { it.value }.toList()

private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+?-----END CERTIFICATE-----")

/**
 * The device's trusted CAs, the Conscrypt module's set where the system has one
 * (Android 14+ updates it apart from the system), else the system's. User-added
 * CAs are not readable by apps and are not included.
 */
internal fun systemCaCertificates(): List<String> {
    val dir = SYSTEM_CA_DIRS.map(::File).firstOrNull { it.isDirectory } ?: return emptyList()
    return dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.flatMap { file ->
        runCatching { pemCertificates(file.readText()) }.getOrDefault(emptyList())
    }
}

private val SYSTEM_CA_DIRS = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
