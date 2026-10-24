package dev.wplayer.app.data

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.core.ffi.ArchiveEntry

/**
 * One picture of the image viewer. [path] is unique among the viewer's pages:
 * the file's path in the source, or the picture's path inside the archive.
 */
data class ImagePage(val name: String, val path: String)

/**
 * The pictures of [sourceKey]'s folder [entries] in the order the browser
 * shows them under [settings], leaving out those a lock keeps out of sight.
 */
fun imagePages(sourceKey: String, entries: List<MediaEntry>, settings: BrowserSettings, locks: LockState): List<ImagePage> =
    arrange(entries, settings)
        .filter { it.kind == MediaKind.IMAGE && !locks.isHidden(sourceKey, it.path) }
        .map { ImagePage(it.name, it.path) }

/** The pictures of an archive, in the page order of [entries]. */
fun archivePages(entries: List<ArchiveEntry>): List<ImagePage> = entries.map { ImagePage(it.path.substringAfterLast('/'), it.path) }

/**
 * Which of the [wanted] pages (indexes into [entries]) to take out of the
 * archive next. The [current] page comes first, since it is on screen. Then
 * the archive is read on from the entry read last ([lastRead], an
 * [ArchiveEntry.index]): going back means reading it again from the start,
 * which only happens once no wanted page lies further on.
 */
fun nextArchivePage(entries: List<ArchiveEntry>, wanted: Set<Int>, current: Int, lastRead: UInt?): Int? {
    if (current in wanted) return current
    val further = wanted.filter { lastRead == null || entries[it].index > lastRead }
    return further.ifEmpty { wanted }.minByOrNull { entries[it].index }
}
