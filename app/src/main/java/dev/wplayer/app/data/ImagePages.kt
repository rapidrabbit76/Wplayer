package dev.wplayer.app.data

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind

/** One picture of the image viewer; [path] is unique among the viewer's pages. */
data class ImagePage(val name: String, val path: String)

/**
 * The pictures of [sourceKey]'s folder [entries] in the order the browser
 * shows them under [settings], leaving out those a lock keeps out of sight.
 */
fun imagePages(sourceKey: String, entries: List<MediaEntry>, settings: BrowserSettings, locks: LockState): List<ImagePage> =
    arrange(entries, settings)
        .filter { it.kind == MediaKind.IMAGE && !locks.isHidden(sourceKey, it.path) }
        .map { ImagePage(it.name, it.path) }
