package dev.wplayer.app.ui.player

import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.RepeatMode
import kotlin.random.Random

/**
 * The file to play after [currentPath]: the following one, wrapping around
 * with repeat-all, or a random other one when shuffling. Null at the end.
 */
internal fun nextInQueue(
    queue: List<MediaRef>,
    currentPath: String,
    repeat: RepeatMode,
    shuffle: Boolean,
    random: Random = Random.Default,
): MediaRef? {
    val index = queue.indexOfFirst { it.path == currentPath }
    if (shuffle) {
        val others = queue.filterIndexed { i, _ -> i != index }
        return if (others.isEmpty()) null else others[random.nextInt(others.size)]
    }
    return when {
        index + 1 < queue.size -> queue[index + 1]
        repeat == RepeatMode.ALL && queue.isNotEmpty() -> queue.first()
        else -> null
    }
}

/** The file before [currentPath], wrapping around with repeat-all. */
internal fun previousInQueue(queue: List<MediaRef>, currentPath: String, repeat: RepeatMode): MediaRef? {
    val index = queue.indexOfFirst { it.path == currentPath }
    return when {
        index > 0 -> queue[index - 1]
        repeat == RepeatMode.ALL && queue.size > 1 -> queue.last()
        else -> null
    }
}
