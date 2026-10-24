package dev.wplayer.app.data

/** Lower-case hex, two digits per byte, as checksums are written. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
