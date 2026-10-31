package dev.wplayer.app.data

import android.os.Environment
import dev.wplayer.app.data.local.CredentialCipher
import dev.wplayer.app.data.local.SourceDao
import dev.wplayer.app.data.local.SourceEntity
import dev.wplayer.app.data.model.Source
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.di.IoDispatcher
import dev.wplayer.core.NativeCore
import dev.wplayer.core.ffi.CoreException
import dev.wplayer.core.ffi.Protocol
import dev.wplayer.core.ffi.SourceConfig
import dev.wplayer.core.ffi.listDirectory
import dev.wplayer.core.ffi.registerSource
import dev.wplayer.core.ffi.unregisterSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the list of sources and keeps the native source registry in sync with it. */
@Singleton
class SourceRepository @Inject constructor(
    private val dao: SourceDao,
    private val cipher: CredentialCipher,
    private val searchIndex: SearchIndex,
    private val lockedFolders: LockedFolders,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Keys currently registered with the native core. */
    private val connected = ConcurrentHashMap.newKeySet<String>()

    /** One lock per source, so a slow or offline server never blocks the others. */
    private val connectLocks = ConcurrentHashMap<String, Mutex>()

    val localStorage = Source(
        key = LOCAL_KEY,
        name = "",
        protocol = SourceProtocol.LOCAL,
        rootPath = Environment.getExternalStorageDirectory().absolutePath,
    )

    val sources: Flow<List<Source>> = dao.observeAll().map { stored ->
        listOf(localStorage) + stored.map { it.toModel() }
    }

    suspend fun hasStoredPassword(key: String): Boolean =
        key.storedId()?.let { dao.get(it) }?.encryptedPassword != null

    suspend fun find(key: String): Source? =
        if (key == LOCAL_KEY) localStorage else key.storedId()?.let { dao.get(it) }?.toModel()

    /**
     * Registers [key] with the native core once; later calls are no-ops.
     *
     * @throws CoreException.NotFound when the source no longer exists.
     */
    suspend fun ensureConnected(key: String): Unit = lockFor(key).withLock {
        if (key in connected) return
        val config = if (key == LOCAL_KEY) {
            localStorage.toConfig(password = null)
        } else {
            val entity = key.storedId()?.let { dao.get(it) } ?: throw CoreException.NotFound("source $key")
            entity.toModel().toConfig(entity.encryptedPassword?.let(cipher::decrypt))
        }
        withContext(io) {
            NativeCore.ensureLoaded()
            registerSource(key, config)
        }
        connected += key
    }

    /**
     * Runs [block] against a connected source. A connection failure (e.g. the
     * server session dropped while the device slept) reconnects once and retries.
     */
    suspend fun <T> withConnection(key: String, block: suspend () -> T): T {
        ensureConnected(key)
        return try {
            block()
        } catch (e: CoreException.Connection) {
            reconnect(key)
            block()
        }
    }

    /** Drops and re-establishes the native connection for [key]. */
    suspend fun reconnect(key: String) {
        disconnect(key)
        ensureConnected(key)
    }

    /** Inserts or updates [source]. A null [password] keeps the stored one; empty clears it. */
    suspend fun save(source: Source, password: String?): Source = withContext(io) {
        val id = source.key.storedId() ?: 0L
        val sealedPassword = when {
            password == null -> dao.get(id)?.encryptedPassword
            password.isEmpty() -> null
            else -> cipher.encrypt(password)
        }
        val entity = SourceEntity(
            id = id,
            name = source.name,
            protocol = source.protocol.name,
            host = source.host,
            port = source.port,
            rootPath = source.rootPath,
            username = source.username,
            allowUntrustedCertificates = source.allowUntrustedCertificates,
            encryptedPassword = sealedPassword,
        )
        val savedId = dao.upsert(entity).takeIf { it > 0 } ?: id
        disconnect(source.key)
        // Another path, server or account can mean other files under the same paths.
        searchIndex.forgetSource(source.key)
        entity.copy(id = savedId).toModel()
    }

    /**
     * Connects to [source] without saving it and lists its root.
     * A null [password] uses the stored one (when editing). Returns the number of entries.
     */
    suspend fun testConnection(source: Source, password: String?): Int = withContext(io) {
        val stored = source.key.storedId()?.let { dao.get(it) }?.encryptedPassword?.let(cipher::decrypt)
        val probeKey = "probe-${UUID.randomUUID()}"
        NativeCore.ensureLoaded()
        try {
            registerSource(probeKey, source.toConfig(password ?: stored))
            listDirectory(probeKey, "/").size
        } finally {
            unregisterSource(probeKey)
        }
    }

    suspend fun delete(key: String) {
        val id = key.storedId() ?: return
        dao.delete(id)
        disconnect(key)
        searchIndex.forgetSource(key)
        lockedFolders.forgetSource(key)
    }

    private suspend fun disconnect(key: String) = lockFor(key).withLock {
        if (connected.remove(key)) withContext(io) { unregisterSource(key) }
    }

    private fun lockFor(key: String): Mutex = connectLocks.getOrPut(key) { Mutex() }

    private fun SourceEntity.toModel() = Source(
        key = "$STORED_PREFIX$id",
        name = name,
        protocol = SourceProtocol.valueOf(protocol),
        host = host,
        port = port,
        rootPath = rootPath,
        username = username,
        allowUntrustedCertificates = allowUntrustedCertificates,
    )

    private fun String.storedId(): Long? = removePrefix(STORED_PREFIX).takeIf { startsWith(STORED_PREFIX) }?.toLongOrNull()

    private fun Source.toConfig(password: String?) = SourceConfig(
        protocol = when (protocol) {
            SourceProtocol.LOCAL -> Protocol.LOCAL
            SourceProtocol.SMB -> Protocol.SMB
            SourceProtocol.NFS -> Protocol.NFS
            SourceProtocol.FTP -> Protocol.FTP
            SourceProtocol.FTPS -> Protocol.FTPS
            SourceProtocol.WEBDAV -> Protocol.WEB_DAV
            SourceProtocol.WEBDAVS -> Protocol.WEB_DAVS
        },
        host = host,
        port = port?.toUShort(),
        rootPath = rootPath,
        username = username,
        password = password,
        allowUntrustedCertificates = allowUntrustedCertificates,
    )

    companion object {
        const val LOCAL_KEY = "local"

        /**
         * Web links in playlists, the recent list and resume positions, stored with the link as the path.
         * Never a registered source: see PlaybackRequest.FromLink and MediaRef.toPlaybackRequest.
         */
        const val LINK_KEY = "link"
        private const val STORED_PREFIX = "source-"
    }
}
