package dev.wplayer.app.data.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The parts of a GitHub release this app reads. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubAsset> = emptyList(),
) {
    fun asset(name: String): GitHubAsset =
        assets.firstOrNull { it.name == name } ?: throw IOException("$tagName has no $name")
}

@Serializable
data class GitHubAsset(
    val name: String,
    /** The API address of the asset, which answers with a redirect to the file. */
    val url: String,
    val size: Long = 0,
)

/**
 * Reads the releases of one GitHub repository. [token] is a fine-grained
 * token that may only read the repository's contents, needed while it is
 * private; empty for a public repository.
 */
class GitHubReleases(private val repository: String, private val token: String, private val userAgent: String) {
    fun latest(): GitHubRelease {
        val body = open(URL("$API/repos/$repository/releases/latest"), JSON_TYPE, followsRedirect = false).inputStream.use { it.readBytes() }
        return json.decodeFromString(String(body))
    }

    /** Opens [asset] for reading; fails on any answer but the file itself. */
    fun download(asset: GitHubAsset): HttpURLConnection {
        val redirect = open(URL(asset.url), BINARY_TYPE, followsRedirect = true)
        if (redirect.responseCode == HttpURLConnection.HTTP_OK) return redirect
        val location = redirect.getHeaderField("Location") ?: throw IOException("no download address for ${asset.name}")
        redirect.disconnect()
        // A signed address on GitHub's file host: it must not receive the token.
        return connect(URL(location)).also { it.expect(HttpURLConnection.HTTP_OK, asset.name) }
    }

    /**
     * An API request, answered with 200 or, when [followsRedirect], a redirect
     * the caller follows itself so the token never leaves api.github.com.
     * A refused token (revoked once the repository went public) is dropped,
     * since public releases need none.
     */
    private fun open(url: URL, accept: String, followsRedirect: Boolean, withToken: Boolean = token.isNotEmpty()): HttpURLConnection {
        val connection = connect(url).apply {
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            if (withToken) setRequestProperty("Authorization", "Bearer $token")
        }
        if (withToken && connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            connection.disconnect()
            return open(url, accept, followsRedirect, withToken = false)
        }
        if (!followsRedirect || connection.responseCode !in REDIRECTS) {
            connection.expect(HttpURLConnection.HTTP_OK, url.path.substringAfterLast('/'))
        }
        return connection
    }

    private fun connect(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MILLIS
        readTimeout = TIMEOUT_MILLIS
        setRequestProperty("User-Agent", userAgent)
    }

    private fun HttpURLConnection.expect(code: Int, what: String) {
        val actual = responseCode
        if (actual == code) return
        disconnect()
        throw IOException(
            when (actual) {
                // GitHub hides a private repository from a missing or invalid token behind 404.
                HttpURLConnection.HTTP_NOT_FOUND -> "$what not found (no release yet, or the update token cannot read the repository)"
                HttpURLConnection.HTTP_FORBIDDEN -> "GitHub refused the request (rate limit?)"
                else -> "HTTP $actual for $what"
            },
        )
    }

    private companion object {
        const val API = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val JSON_TYPE = "application/vnd.github+json"
        const val BINARY_TYPE = "application/octet-stream"
        const val TIMEOUT_MILLIS = 15_000
        val REDIRECTS = setOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 303, 307, 308)
        val json = Json { ignoreUnknownKeys = true }
    }
}
