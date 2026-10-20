package dev.wplayer.app.data.model

enum class SourceProtocol {
    LOCAL, SMB, NFS, FTP, FTPS, WEBDAV, WEBDAVS;

    val usesTls: Boolean get() = this == FTPS || this == WEBDAVS
}

/**
 * A place media can be browsed from.
 *
 * [key] identifies the source in the native registry and in playback history;
 * it is stable for the lifetime of the source.
 */
data class Source(
    val key: String,
    val name: String,
    val protocol: SourceProtocol,
    val host: String = "",
    val port: Int? = null,
    val rootPath: String = "/",
    val username: String? = null,
    /** Accept self-signed TLS certificates (FTPS/WebDAVS on a home server). */
    val allowUntrustedCertificates: Boolean = false,
)
