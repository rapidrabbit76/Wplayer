package dev.wplayer.app.data.link

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.BuildConfig
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.reason
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.di.IoDispatcher
import dev.wplayer.core.ffi.LinkPlugin
import dev.wplayer.core.ffi.linkPluginApi
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The last look at the plugin index. */
sealed interface IndexCheck {
    data object Idle : IndexCheck
    data object Checking : IndexCheck
    data class Checked(val offers: List<PluginOffer>) : IndexCheck
    data class Failed(val reason: String) : IndexCheck
}

/**
 * Link plugins: installed ones in `filesDir/link-plugins` (see [LinkPluginStore]),
 * offered ones from the index the user set (see LinkSettings.indexUrl). Packages
 * from the index install only when their SHA-256 matches the index's.
 */
@Singleton
class LinkPluginRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val hostApi: Int = linkPluginApi().toInt()

    private val store = LinkPluginStore(File(context.filesDir, "link-plugins"), hostApi)

    /** Every store call holds it: installs and removals of one folder must not interleave. */
    private val storeLock = Mutex()
    private var loaded = false

    private val _installed = MutableStateFlow<List<LinkPluginManifest>>(emptyList())

    /** By name; empty until the plugin folder has been read. */
    val installed: StateFlow<List<LinkPluginManifest>> = _installed.asStateFlow()

    private val _index = MutableStateFlow<IndexCheck>(IndexCheck.Idle)
    val index: StateFlow<IndexCheck> = _index.asStateFlow()

    init {
        scope.launch { withStore { } }
    }

    /** The installed plugins, once read from storage. */
    suspend fun current(): List<LinkPluginManifest> = withStore { _installed.value }

    /** What the core runs for [manifest]. */
    suspend fun plugin(manifest: LinkPluginManifest): LinkPlugin =
        withStore { LinkPlugin(manifest.id, store.script(manifest.id), manifest.withoutSni) }

    /** Installs a package the user picked; throws [InvalidPluginException] when it is not a usable plugin. */
    suspend fun install(zip: ByteArray): LinkPluginManifest = withStore(changes = true) { store.install(zip) }

    /** Installs the package at [uri], a file the user picked. */
    suspend fun install(uri: Uri): LinkPluginManifest {
        val zip = withContext(io) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.use { it.readAtMost(MAX_PACKAGE_BYTES, "the file") }
        }
        return install(zip)
    }

    /** Downloads [offer] from beside the index, checks it against the index and installs it. */
    suspend fun install(offer: PluginOffer): LinkPluginManifest {
        val indexUrl = settings.settings.first().links.indexUrl
        val zip = withContext(io) { fetch(URL(URL(indexUrl), offer.file), MAX_PACKAGE_BYTES) }
        requireOffered(offer, zip)
        return install(zip)
    }

    suspend fun remove(id: String) {
        withStore(changes = true) { store.remove(id) }
        settings.update { it.copy(links = it.links.copy(disabledPlugins = it.links.disabledPlugins - id)) }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        settings.update {
            val disabled = it.links.disabledPlugins
            it.copy(links = it.links.copy(disabledPlugins = if (enabled) disabled - id else disabled + id))
        }
    }

    /** Reads the index again, unless a check is running. */
    fun check() {
        scope.launch { refresh() }
    }

    /**
     * At start-up: at most once a day, and only with automatic updates on, installs
     * newer versions of installed plugins. New plugins are only installed by the user.
     */
    fun updateIfDue() {
        scope.launch {
            val links = settings.settings.first().links
            if (!links.autoUpdate || links.indexUrl.isBlank()) return@launch
            if (System.currentTimeMillis() - links.lastCheckedAtMillis < AUTO_UPDATE_INTERVAL_MILLIS) return@launch
            val offers = refresh() ?: return@launch
            pluginStatuses(current(), offers, hostApi).values.filterIsInstance<PluginStatus.UpdateAvailable>().forEach { update ->
                try {
                    install(update.offer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "cannot update ${update.offer.manifest.id}", e)
                }
            }
        }
    }

    /** The index's offers, or null when it could not be read (see [index]) or a check was already running. */
    private suspend fun refresh(): List<PluginOffer>? {
        if (_index.getAndUpdate { IndexCheck.Checking } == IndexCheck.Checking) return null
        return try {
            val indexUrl = settings.settings.first().links.indexUrl
            if (indexUrl.isBlank()) throw IOException("no index address set")
            val offers = withContext(io) { parseIndex(fetch(URL(indexUrl), MAX_INDEX_BYTES).decodeToString()) }
            settings.update { it.copy(links = it.links.copy(lastCheckedAtMillis = System.currentTimeMillis())) }
            _index.value = IndexCheck.Checked(offers)
            offers
        } catch (e: CancellationException) {
            _index.value = IndexCheck.Idle
            throw e
        } catch (e: Exception) {
            _index.value = IndexCheck.Failed(e.reason())
            null
        }
    }

    /**
     * Runs [block] on the store, reading the installed plugins first if no call has
     * yet, and again afterwards when it [changes] them (even when it failed halfway).
     */
    private suspend fun <T> withStore(changes: Boolean = false, block: () -> T): T = storeLock.withLock {
        withContext(io) {
            if (!loaded) {
                _installed.value = store.readAll()
                loaded = true
            }
            try {
                block()
            } finally {
                if (changes) _installed.value = store.readAll()
            }
        }
    }

    /** The body of a plain GET of [url], which must answer 200 with at most [limit] bytes. */
    private fun fetch(url: URL, limit: Int): ByteArray {
        if (url.protocol != "https" && url.protocol != "http") throw IOException("${url.protocol} addresses are not supported")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("User-Agent", "Wplayer/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} for ${url.path.substringAfterLast('/')}")
            return connection.inputStream.use { it.readAtMost(limit, url.path.substringAfterLast('/')) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "LinkPluginRepository"
        const val AUTO_UPDATE_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
        const val MAX_INDEX_BYTES = 1024 * 1024
        const val MAX_PACKAGE_BYTES = 4 * 1024 * 1024
        const val TIMEOUT_MILLIS = 15_000
    }
}
