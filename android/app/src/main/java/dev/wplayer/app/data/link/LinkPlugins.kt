package dev.wplayer.app.data.link

import dev.wplayer.core.ffi.LinkStream
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A plugin package or index entry that cannot be used; [message] says why, for the settings screen. */
class InvalidPluginException(message: String) : IOException(message)

/**
 * A plugin's version: one to four dotted numbers, compared number by number,
 * with missing parts counting as 0 (1.2 is 1.2.0).
 */
class PluginVersion private constructor(private val parts: List<Int>, private val text: String) : Comparable<PluginVersion> {
    override fun compareTo(other: PluginVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is PluginVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = text

    companion object {
        private val FORMAT = Regex("""\d{1,9}(\.\d{1,9}){0,3}""")

        fun parse(text: String): PluginVersion {
            if (!FORMAT.matches(text)) throw InvalidPluginException("version \"$text\" is not 1 to 4 dotted numbers")
            return PluginVersion(text.split('.').map(String::toInt), text)
        }
    }
}

/** `manifest.json` and the manifest fields of an index entry, as written; see [LinkPluginManifest.from]. */
@Serializable
internal data class ManifestJson(
    val id: String,
    val name: String,
    val version: String,
    val api: Int,
    val description: String = "",
    val matches: List<String> = emptyList(),
    val withoutSni: List<String> = emptyList(),
)

/** `index.json`: the plugins offered at the index address, each with its package next to it. */
@Serializable
internal data class IndexJson(val format: Int, val plugins: List<IndexEntryJson> = emptyList())

@Serializable
internal data class IndexEntryJson(
    val id: String,
    val name: String,
    val version: String,
    val api: Int,
    val description: String = "",
    val matches: List<String> = emptyList(),
    val withoutSni: List<String> = emptyList(),
    /** The package's address, relative to the index's. */
    val file: String,
    val sha256: String,
) {
    val manifest: ManifestJson get() = ManifestJson(id, name, version, api, description, matches, withoutSni)
}

/** A validated plugin manifest: what a plugin is, which pages it takes and which host API it needs. */
data class LinkPluginManifest(
    val id: String,
    val name: String,
    val version: PluginVersion,
    /** The lowest host API ([dev.wplayer.core.ffi.linkPluginApi]) the plugin needs. */
    val api: Int,
    val description: String,
    /** Regular expressions over a page's address; any match means the plugin takes it. */
    val matches: List<String>,
    /** Hosts reached without SNI; handed to the core as is. */
    val withoutSni: List<String>,
) {
    private val patterns: List<Regex> by lazy { matches.map(::Regex) }

    fun takes(url: String): Boolean = patterns.any { it.containsMatchIn(url) }

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        fun isValidId(id: String): Boolean = ID.matches(id)

        /** Checks everything about [raw] but its API level, which [requireRunnableOn] checks. */
        internal fun from(raw: ManifestJson): LinkPluginManifest {
            if (!isValidId(raw.id)) throw InvalidPluginException("id \"${raw.id}\" is not lower-case letters, digits, - and _")
            if (raw.name.isBlank()) throw InvalidPluginException("${raw.id} has no name")
            if (raw.api < 1) throw InvalidPluginException("${raw.id} needs host API ${raw.api}")
            if (raw.matches.isEmpty()) throw InvalidPluginException("${raw.id} matches no address")
            raw.matches.forEach { pattern ->
                try {
                    Regex(pattern)
                } catch (e: IllegalArgumentException) {
                    throw InvalidPluginException("${raw.id} has a broken pattern: ${e.message}")
                }
            }
            return LinkPluginManifest(raw.id, raw.name.trim(), PluginVersion.parse(raw.version), raw.api, raw.description.trim(), raw.matches, raw.withoutSni)
        }

        fun parse(text: String): LinkPluginManifest = from(decode<ManifestJson>(text, "manifest.json"))
    }
}

/** Refuses a plugin that needs a newer host than [hostApi]. */
fun LinkPluginManifest.requireRunnableOn(hostApi: Int) {
    if (api > hostApi) throw InvalidPluginException("$name $version needs a newer Wplayer (plugin API $api, this app has $hostApi)")
}

/** A plugin the index offers: its manifest and where its package is. */
data class PluginOffer(val manifest: LinkPluginManifest, val file: String, val sha256: String)

/**
 * The offers in an `index.json`. Entries that do not validate are left out, so one
 * broken entry does not hide the rest; a broken index as a whole is refused.
 */
fun parseIndex(text: String): List<PluginOffer> {
    val index = decode<IndexJson>(text, "index.json")
    if (index.format != INDEX_FORMAT) throw InvalidPluginException("index format ${index.format} is not supported")
    return index.plugins.mapNotNull { entry ->
        runCatching { PluginOffer(LinkPluginManifest.from(entry.manifest), entry.file, entry.sha256.lowercase()) }.getOrNull()
    }
}

private const val INDEX_FORMAT = 1

private val json = Json { ignoreUnknownKeys = true }

private inline fun <reified T> decode(text: String, what: String): T = try {
    json.decodeFromString<T>(text)
} catch (e: SerializationException) {
    throw InvalidPluginException("$what: ${e.message}")
} catch (e: IllegalArgumentException) {
    throw InvalidPluginException("$what: ${e.message}")
}

/** Where a plugin stands against the index, for the settings screen. */
sealed interface PluginStatus {
    /** Installed, and the index has no newer version (or does not list it). */
    data object UpToDate : PluginStatus

    data class UpdateAvailable(val offer: PluginOffer) : PluginStatus

    /** The index's version needs a newer app than this one. */
    data class NeedsNewerApp(val offer: PluginOffer) : PluginStatus

    data class NotInstalled(val offer: PluginOffer) : PluginStatus
}

/**
 * The status of every installed or offered plugin, by id. An installed plugin is
 * up to date unless the index has a newer version; an offer this app cannot run
 * is shown as needing a newer app rather than as installable.
 */
fun pluginStatuses(installed: List<LinkPluginManifest>, offers: List<PluginOffer>, hostApi: Int): Map<String, PluginStatus> {
    val byId = installed.associateBy { it.id }
    val statuses = installed.associate { it.id to PluginStatus.UpToDate as PluginStatus }.toMutableMap()
    for (offer in offers) {
        val current = byId[offer.manifest.id]
        if (current != null && offer.manifest.version <= current.version) continue
        statuses[offer.manifest.id] = when {
            offer.manifest.api > hostApi -> PluginStatus.NeedsNewerApp(offer)
            current == null -> PluginStatus.NotInstalled(offer)
            else -> PluginStatus.UpdateAvailable(offer)
        }
    }
    return statuses
}

/** The first enabled plugin that takes [url], in the order given. */
fun pluginFor(url: String, installed: List<LinkPluginManifest>, disabled: Set<String>): LinkPluginManifest? =
    installed.firstOrNull { it.id !in disabled && it.takes(url) }

/**
 * The stream to play: [streams] come in the plugin's order of preference, so the
 * first one no taller than [maxHeight], or the first one when none fits or there is no limit.
 */
fun chooseStream(streams: List<LinkStream>, maxHeight: Int?): LinkStream? {
    if (maxHeight == null) return streams.firstOrNull()
    return streams.firstOrNull { stream -> stream.height?.let { it.toInt() <= maxHeight } == true } ?: streams.firstOrNull()
}

/**
 * The first `http(s)` address in [text], such as the page a browser shares after
 * its title. Sentence punctuation and an unopened closing bracket after it are left out.
 */
fun firstWebUrl(text: String): String? {
    val found = WEB_URL.find(text)?.value ?: return null
    var url = found.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
    while (url.endsWith(')') && url.count { it == '(' } < url.count { it == ')' }) url = url.dropLast(1)
    val scheme = url.substringBefore("://")
    return scheme.lowercase() + url.substring(scheme.length)
}

private val WEB_URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
