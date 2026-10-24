package dev.wplayer.app.data

internal fun Throwable.reason(): String = message ?: javaClass.simpleName
