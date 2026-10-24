package dev.wplayer.app.data

import java.io.InputStream

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal inline fun InputStream.forEachChunk(bufferBytes: Int, onChunk: (buffer: ByteArray, length: Int) -> Unit) {
    val buffer = ByteArray(bufferBytes)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        onChunk(buffer, n)
    }
}
