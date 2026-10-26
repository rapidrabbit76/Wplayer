@file:OptIn(ExperimentalReadiumApi::class)

package dev.wplayer.app.ui.reader

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wplayer.app.data.EpubBooks
import dev.wplayer.app.data.ImageCache
import dev.wplayer.app.data.LockedFolders
import dev.wplayer.app.data.PlaybackHistoryRepository
import dev.wplayer.app.data.SettingsRepository
import dev.wplayer.app.data.UnreadableDocumentException
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.mediaKey
import dev.wplayer.app.data.savedLocator
import dev.wplayer.app.data.toSaved
import dev.wplayer.app.di.ApplicationScope
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.catchingFailures
import dev.wplayer.app.ui.navigation.ReaderRoute
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.navigator.web.fixedlayout.FixedWebConfiguration
import org.readium.navigator.web.fixedlayout.FixedWebGoLocation
import org.readium.navigator.web.fixedlayout.FixedWebRenditionFactory
import org.readium.navigator.web.fixedlayout.FixedWebRenditionState
import org.readium.navigator.web.fixedlayout.preferences.FixedWebPreferences
import org.readium.navigator.web.reflowable.ReflowableWebGoLocation
import org.readium.navigator.web.reflowable.ReflowableWebRenditionFactory
import org.readium.navigator.web.reflowable.ReflowableWebRenditionState
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse

/** What the reader shows. */
sealed interface ReaderContent {
    /** [fraction] of the book has been copied from its source; null while unknown. */
    data class Opening(val fraction: Float?) : ReaderContent

    data class Failed(val error: UserError) : ReaderContent

    /** A book whose text flows into pages of the screen's size. */
    data class Reflowable(val rendition: ReflowableWebRenditionState) : ReaderContent

    /** A book of fixed pages, such as a comic or a picture book. */
    data class Fixed(val rendition: FixedWebRenditionState) : ReaderContent
}

/** One entry of the table of contents, [depth] levels in. */
data class ContentsEntry(val title: String, val href: Url, val depth: Int)

data class ReaderUiState(
    val title: String,
    val content: ReaderContent = ReaderContent.Opening(null),
    val contents: List<ContentsEntry> = emptyList(),
    /** The book is (inside) a locked folder that has not been unlocked. */
    val hiddenByLock: Boolean = false,
    val fontScale: Double = 1.0,
)

/**
 * The EPUB reader: copies the book from its source, opens it with Readium on
 * the place it was left at, and keeps that place as the reader moves on.
 */
@HiltViewModel(assistedFactory = ReaderViewModel.Factory::class)
class ReaderViewModel @AssistedInject constructor(
    @Assisted private val route: ReaderRoute,
    private val application: Application,
    private val books: EpubBooks,
    private val imageCache: ImageCache,
    private val locks: LockedFolders,
    private val history: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val content = MutableStateFlow<ReaderContent>(ReaderContent.Opening(null))
    private val contents = MutableStateFlow<List<ContentsEntry>>(emptyList())
    private var publication: Publication? = null
    private val mediaKey = mediaKey(route.sourceKey, route.path)

    val uiState: StateFlow<ReaderUiState> = combine(
        content,
        contents,
        locks.state.map { it.isHidden(route.sourceKey, route.path) },
        settingsRepository.settings.map { it.pictures.bookFontScale },
    ) { content, contents, hidden, fontScale ->
        ReaderUiState(title(), content, contents, hidden, fontScale)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState(title()))

    init {
        // A book in a locked folder stays unopened until the folders are unlocked.
        viewModelScope.launch {
            locks.state.first { !it.isHidden(route.sourceKey, route.path) }
            load()
        }
    }

    private fun title() = publication?.metadata?.title ?: route.path.substringAfterLast('/')

    fun load() {
        if (content.value is ReaderContent.Reflowable || content.value is ReaderContent.Fixed) return
        viewModelScope.launch {
            content.value = ReaderContent.Opening(null)
            content.value = catchingFailures { open() }.getOrElse { e ->
                Log.w(TAG, "cannot open ${route.path}", e)
                ReaderContent.Failed(UserError.from(e))
            }
        }
    }

    private suspend fun open(): ReaderContent {
        val book = publication ?: run {
            val file = imageCache.file(route.sourceKey, route.path) { fraction -> content.value = ReaderContent.Opening(fraction) }
            books.open(file).also { publication = it }
        }
        contents.value = book.tableOfContents.flatten(depth = 0)
        history.recordPlayed(MediaRef(route.sourceKey, route.path, route.path.substringAfterLast('/'), isDirectory = false))
        val leftAt = history.readingPage(mediaKey)?.let(::savedLocator)
        // A book says whether its pages are fixed; each factory takes only its own kind.
        FixedWebRenditionFactory(application, book, FixedWebConfiguration())?.let { factory ->
            val rendition = factory.createRenditionState(initialPreferences = FixedWebPreferences(), initialLocation = leftAt?.let(::FixedWebGoLocation))
                .getOrElse { throw UnreadableDocumentException("cannot lay out ${route.path}: ${it.message}") }
            return ReaderContent.Fixed(rendition)
        }
        val factory = ReflowableWebRenditionFactory(application, book)
            ?: throw UnreadableDocumentException("not a book Readium lays out: ${route.path}")
        val rendition = factory.createRenditionState(initialLocation = leftAt?.let(::ReflowableWebGoLocation))
            .getOrElse { throw UnreadableDocumentException("cannot lay out ${route.path}: ${it.message}") }
        return ReaderContent.Reflowable(rendition)
    }

    /** The reader moved to [locator]: the book opens there next time. */
    fun leftAt(locator: Locator) {
        appScope.launch { history.saveReadingPage(mediaKey, locator.toSaved()) }
    }

    fun setFontScale(scale: Double) {
        viewModelScope.launch {
            // Steps of a tenth, without the drift of adding 0.1 over and over.
            val rounded = (scale * 10).roundToInt() / 10.0
            settingsRepository.update { it.copy(pictures = it.pictures.copy(bookFontScale = rounded.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))) }
        }
    }

    /** Call after the device authenticated the user. */
    fun unlockSession() = locks.unlockSession()

    override fun onCleared() {
        publication?.close()
        appScope.launch { imageCache.forgetLocked() }
    }

    private fun List<Link>.flatten(depth: Int): List<ContentsEntry> = flatMap { link ->
        listOf(ContentsEntry(link.title.orEmpty().ifBlank { link.href.toString() }, link.url(), depth)) + link.children.flatten(depth + 1)
    }

    companion object {
        private const val TAG = "ReaderViewModel"
        const val MIN_FONT_SCALE = 0.5
        const val MAX_FONT_SCALE = 3.0
        const val FONT_SCALE_STEP = 0.1
    }

    @AssistedFactory
    interface Factory {
        fun create(route: ReaderRoute): ReaderViewModel
    }
}
