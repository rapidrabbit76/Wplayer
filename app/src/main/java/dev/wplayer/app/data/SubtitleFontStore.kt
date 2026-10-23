package dev.wplayer.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wplayer.app.data.model.SubtitleFont
import dev.wplayer.app.di.IoDispatcher
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The file is not a TrueType or OpenType font. */
class NotAFontException(name: String) : IOException("not a font: $name")

/**
 * Fonts for subtitles, in the folder mpv gives libass as `sub-fonts-dir`:
 * libass has no system font provider on Android, so it only sees fonts there.
 * The system's Korean font is linked in, and the user can add font files,
 * which then become the subtitle font.
 */
@Singleton
class SubtitleFontStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val directory: File = File(context.filesDir, "mpv/fonts")

    private val changes = MutableStateFlow(0)

    /** The fonts the user added, by name. */
    val fonts: Flow<List<SubtitleFont>> = changes.map { withContext(io) { addedFonts() } }

    /**
     * Makes [directory] ready for an engine. Links the system CJK font instead
     * of scanning all of /system/fonts, which would slow every file open.
     */
    fun prepare(): File {
        directory.mkdirs()
        val systemFont = SYSTEM_CJK_FONTS.map(::File).firstOrNull(File::exists) ?: return directory
        val link = File(directory, systemFont.name)
        if (!link.exists()) {
            try {
                Os.symlink(systemFont.absolutePath, link.absolutePath)
            } catch (e: ErrnoException) {
                Log.w(TAG, "cannot link subtitle font ${systemFont.name}", e)
            }
        }
        return directory
    }

    /** Adds the font file at [uri], e.g. from the system's file picker, and makes it the subtitle font. */
    suspend fun add(uri: Uri): SubtitleFont {
        val resolver = context.contentResolver
        val name = withContext(io) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: DEFAULT_NAME
        return add(name) { target ->
            val input = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.use { source -> target.outputStream().use(source::copyTo) }
        }
    }

    /**
     * Adds a font file named [name], which [write] writes to the file it is
     * given, and makes it the subtitle font. A font of the same name is replaced.
     */
    suspend fun add(name: String, write: suspend (File) -> Unit): SubtitleFont {
        val fileName = safeFileName(name)
        val staged = File(context.cacheDir, "font-$fileName")
        val font = try {
            // A copy left by an earlier attempt would be continued rather than replaced.
            withContext(io) { staged.delete() }
            write(staged)
            withContext(io) {
                val family = fontFamilyName(staged.readBytes()) ?: throw NotAFontException(name)
                directory.mkdirs()
                val target = File(directory, fileName)
                if (!staged.renameTo(target)) {
                    staged.copyTo(target, overwrite = true)
                }
                SubtitleFont(fileName, family)
            }
        } finally {
            withContext(io) { staged.delete() }
        }
        settings.update { it.copy(subtitle = it.subtitle.copy(font = font)) }
        changes.value++
        return font
    }

    /** Deletes an added font; subtitles that used it go back to the system font. */
    suspend fun delete(font: SubtitleFont) {
        withContext(io) { File(directory, font.file).delete() }
        settings.update { if (it.subtitle.font == font) it.copy(subtitle = it.subtitle.copy(font = null)) else it }
        changes.value++
    }

    /** The font file of [font], for drawing subtitles without mpv; null when it is gone. */
    fun fileOf(font: SubtitleFont): File? = File(directory, font.file).takeIf(File::isFile)

    private fun addedFonts(): List<SubtitleFont> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.extension.lowercase() in FONT_EXTENSIONS }
            .mapNotNull { file -> fontFamilyName(file.readBytes())?.let { SubtitleFont(file.name, it) } }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.family })

    companion object {
        private const val TAG = "SubtitleFontStore"
        private const val DEFAULT_NAME = "font.ttf"

        /** What a font file may be named; also how the browser tells fonts from other files. */
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

        private val SYSTEM_CJK_FONTS = listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJKkr-Regular.otf",
            "/system/fonts/NotoSansKR-Regular.otf",
        )

        /** [name] without folders or characters a file name cannot have, keeping a font extension. */
        internal fun safeFileName(name: String): String {
            val base = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifEmpty { DEFAULT_NAME }
            return if (base.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS) base else "$base.ttf"
        }

        fun isFontFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS
    }
}
