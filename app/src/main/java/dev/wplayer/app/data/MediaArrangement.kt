package dev.wplayer.app.data

import dev.wplayer.app.data.model.BrowserSettings
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.SortField
import java.text.Normalizer

/** Filters and orders a folder listing the way the browser shows it. */
fun arrange(entries: List<MediaEntry>, settings: BrowserSettings): List<MediaEntry> {
    val byField: Comparator<MediaEntry> = when (settings.sortField) {
        SortField.NAME -> compareBy(NaturalOrderComparator) { it.name }
        SortField.EXTENSION -> compareBy<MediaEntry> { it.extension }.thenBy(NaturalOrderComparator) { it.name }
        SortField.DATE -> compareBy<MediaEntry> { it.modifiedAtMillis ?: 0L }.thenBy(NaturalOrderComparator) { it.name }
        SortField.SIZE -> compareBy<MediaEntry> { it.size }.thenBy(NaturalOrderComparator) { it.name }
    }
    val ordered = if (settings.ascending) byField else byField.reversed()
    val comparator = if (settings.foldersFirst) compareBy<MediaEntry> { it.kind != MediaKind.DIRECTORY }.then(ordered) else ordered
    return entries.filter { it.isShown(settings) }.sortedWith(comparator)
}

/**
 * Whether the browser lists [this] under [settings]' file filters. Font files
 * always show: opening one makes it the subtitle font, the only way to add one
 * on a TV.
 */
fun MediaEntry.isShown(settings: BrowserSettings): Boolean = when {
    name.startsWith('.') -> settings.showHiddenFiles
    kind == MediaKind.SUBTITLE -> settings.showSubtitleFiles
    kind == MediaKind.OTHER && SubtitleFontStore.isFontFile(name) -> true
    kind == MediaKind.OTHER -> settings.showOtherFiles
    else -> true
}

/** [name] as the search compares it: NFC-normalized (see [nameMatches]) and lower-cased. */
fun searchKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase()

/**
 * Whether [name] contains [query], ignoring case. Both are NFC-normalized:
 * names written by a Mac often arrive with Hangul decomposed into jamo (NFD).
 */
fun nameMatches(name: String, query: String): Boolean =
    Normalizer.normalize(name, Normalizer.Form.NFC).contains(Normalizer.normalize(query, Normalizer.Form.NFC), ignoreCase = true)
