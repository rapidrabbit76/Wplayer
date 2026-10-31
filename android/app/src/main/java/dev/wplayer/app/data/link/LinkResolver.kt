package dev.wplayer.app.data.link

import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.core.ffi.CoreException
import dev.wplayer.core.ffi.resolveLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** What mpv opens for a link: the stream, the headers its server wants, and the page's title when a plugin named it. */
data class PlayableLink(val streamUrl: String, val headers: Map<String, String>, val title: String?)

/** Turns a link into a stream with the first enabled plugin that takes it; without one, mpv opens the link itself. */
@Singleton
class LinkResolver @Inject constructor(
    private val plugins: LinkPluginRepository,
    private val settings: SettingsRepository,
) {
    suspend fun resolve(url: String): PlayableLink {
        val links = settings.settings.first().links
        val manifest = pluginFor(url, plugins.current(), links.disabledPlugins) ?: return PlayableLink(url, emptyMap(), title = null)
        val resolved = resolveLink(plugins.plugin(manifest), url)
        // mpv would open any protocol it is handed, local files included; a plugin may only point at the web.
        val webStreams = resolved.streams.filter { stream -> WEB_SCHEMES.any { stream.url.startsWith(it, ignoreCase = true) } }
        val stream = chooseStream(webStreams, links.maxHeight) ?: throw CoreException.Plugin("${manifest.name} found no stream")
        return PlayableLink(stream.url, stream.headers, resolved.title?.takeIf { it.isNotBlank() })
    }
}

private val WEB_SCHEMES = listOf("https://", "http://")
