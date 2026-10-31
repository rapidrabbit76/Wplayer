package dev.wplayer.app.data.link

import android.util.Log
import dev.wplayer.app.data.forEachChunk
import dev.wplayer.app.data.toHex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A plugin package taken apart and checked: its manifest and its script. */
class PluginPackage(val manifest: LinkPluginManifest, val manifestText: String, val script: String)

/**
 * Takes a plugin zip apart. It must hold exactly `manifest.json` and `main.js` at
 * its root and nothing else, so no entry can name a path outside the plugin's folder.
 */
fun readPackage(zip: ByteArray): PluginPackage {
    val files = mutableMapOf<String, String>()
    try {
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val limit = PACKAGE_FILES[entry.name]
                if (entry.isDirectory || limit == null) throw InvalidPluginException("the package holds \"${entry.name}\"; only manifest.json and main.js belong in it")
                if (entry.name in files) throw InvalidPluginException("the package holds ${entry.name} twice")
                files[entry.name] = input.readAtMost(limit, entry.name).decodeToString()
            }
        }
    } catch (e: ZipException) {
        throw InvalidPluginException("not a zip file: ${e.message}")
    }
    val manifestText = files[MANIFEST] ?: throw InvalidPluginException("the package has no $MANIFEST")
    val script = files[SCRIPT] ?: throw InvalidPluginException("the package has no $SCRIPT")
    return PluginPackage(LinkPluginManifest.parse(manifestText), manifestText, script)
}

/** Refuses a download that is not the package [offer] describes: another checksum, plugin or version. */
fun requireOffered(offer: PluginOffer, zip: ByteArray) {
    val name = offer.manifest.name
    if (MessageDigest.getInstance("SHA-256").digest(zip).toHex() != offer.sha256) throw InvalidPluginException("$name: the download does not match the index's checksum")
    val manifest = readPackage(zip).manifest
    if (manifest.id != offer.manifest.id || manifest.version != offer.manifest.version) {
        throw InvalidPluginException("$name: the package is ${manifest.id} ${manifest.version}, not what the index lists")
    }
}

/** All of this stream, refused as soon as it passes [limit] bytes; [name] says what it is. */
internal fun InputStream.readAtMost(limit: Int, name: String): ByteArray {
    val out = ByteArrayOutputStream()
    forEachChunk(BUFFER_BYTES) { buffer, n ->
        out.write(buffer, 0, n)
        if (out.size() > limit) throw InvalidPluginException("$name is larger than ${limit / 1024} KB")
    }
    return out.toByteArray()
}

private const val BUFFER_BYTES = 16 * 1024

/**
 * The installed plugins, one folder each under [root]: `<id>/manifest.json` and
 * `<id>/main.js`. Not thread-safe; [LinkPluginRepository] serializes every call.
 *
 * An install is written to a staging folder and renamed into place, the old
 * version moved aside first, so a crash leaves either version whole: [readAll]
 * puts a set-aside version back when the new one never arrived.
 */
class LinkPluginStore(private val root: File, private val hostApi: Int) {
    /** Installs [zip] over any version of the same plugin; the same zip twice leaves the same state. */
    fun install(zip: ByteArray): LinkPluginManifest {
        val pkg = readPackage(zip)
        val manifest = pkg.manifest
        manifest.requireRunnableOn(hostApi)
        root.mkdirs()
        val staging = File(root, STAGING_PREFIX + manifest.id)
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create $staging")
        File(staging, MANIFEST).writeText(pkg.manifestText)
        File(staging, SCRIPT).writeText(pkg.script)

        val target = folderOf(manifest.id)
        val aside = File(root, ASIDE_PREFIX + manifest.id)
        aside.deleteRecursively()
        if (target.exists() && !target.renameTo(aside)) throw IOException("cannot replace ${manifest.id}")
        if (!staging.renameTo(target)) {
            aside.renameTo(target)
            throw IOException("cannot install ${manifest.id}")
        }
        aside.deleteRecursively()
        return manifest
    }

    fun remove(id: String) {
        folderOf(id).deleteRecursively()
    }

    /** The installed plugins this app can run, by name; finishes an install a crash interrupted. */
    fun readAll(): List<LinkPluginManifest> {
        recover()
        val folders = root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }.orEmpty()
        return folders.mapNotNull { folder ->
            try {
                val manifest = LinkPluginManifest.parse(File(folder, MANIFEST).readText())
                manifest.requireRunnableOn(hostApi)
                if (manifest.id != folder.name || !File(folder, SCRIPT).isFile) throw InvalidPluginException("${folder.name} is incomplete")
                manifest
            } catch (e: IOException) {
                Log.w(TAG, "skipping plugin folder ${folder.name}", e)
                null
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun script(id: String): String = File(folderOf(id), SCRIPT).readText()

    private fun recover() {
        root.listFiles().orEmpty().forEach { file ->
            when {
                file.name.startsWith(STAGING_PREFIX) -> file.deleteRecursively()
                file.name.startsWith(ASIDE_PREFIX) -> {
                    val target = File(root, file.name.removePrefix(ASIDE_PREFIX))
                    if (target.exists() || !file.renameTo(target)) file.deleteRecursively()
                }
            }
        }
    }

    /** Ids cannot start with a dot, so a plugin folder never collides with the staging ones. */
    private fun folderOf(id: String): File {
        require(LinkPluginManifest.isValidId(id)) { "not a plugin id: $id" }
        return File(root, id)
    }

    private companion object {
        const val TAG = "LinkPluginStore"
        const val STAGING_PREFIX = ".staging-"
        const val ASIDE_PREFIX = ".aside-"
    }
}

private const val MANIFEST = "manifest.json"
private const val SCRIPT = "main.js"

/** The files a package holds, with the most each may weigh. */
private val PACKAGE_FILES = mapOf(MANIFEST to 64 * 1024, SCRIPT to 2 * 1024 * 1024)
