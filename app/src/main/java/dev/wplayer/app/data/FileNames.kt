package dev.wplayer.app.data

internal fun safeFileName(name: String, fallback: String): String =
    name.substringAfterLast('/').replace(UNSAFE_CHARACTERS, "_").trim()
        .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: fallback

private val UNSAFE_CHARACTERS = Regex("[\\\\:*?\"<>|\\u0000-\\u001F]")
