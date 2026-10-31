@file:OptIn(ExperimentalReadiumApi::class)

package dev.wplayer.app.ui.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wplayer.app.R
import dev.wplayer.app.ui.browser.LockedFolderMessage
import dev.wplayer.app.ui.common.CenteredMessage
import dev.wplayer.app.ui.common.message
import dev.wplayer.app.ui.common.rememberDeviceAuthentication
import dev.wplayer.app.ui.viewer.HideSystemBars
import dev.wplayer.app.ui.viewer.ViewerKeyAction
import dev.wplayer.app.ui.viewer.viewerKeyAction
import kotlinx.coroutines.launch
import org.readium.navigator.common.InputListener
import org.readium.navigator.common.NavigationController
import org.readium.navigator.common.OverflowController
import org.readium.navigator.common.TapContext
import org.readium.navigator.common.TapEvent
import org.readium.navigator.common.defaultHyperlinkListener
import org.readium.navigator.common.defaultInputListener
import org.readium.navigator.web.fixedlayout.FixedWebRendition
import org.readium.navigator.web.reflowable.ReflowableWebRendition
import org.readium.navigator.web.reflowable.preferences.ReflowableWebPreferences
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auth = rememberDeviceAuthentication()
    val openLockedTitle = stringResource(R.string.locked_folder_open)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (val content = state.content.takeUnless { state.hiddenByLock }) {
            is ReaderContent.Reflowable, is ReaderContent.Fixed -> Book(state, content, viewModel, onBack)
            else -> Box(Modifier.fillMaxSize()) {
                when {
                    state.hiddenByLock -> LockedFolderMessage(onOpen = { auth.authenticate(openLockedTitle, viewModel::unlockSession) })
                    content is ReaderContent.Failed -> CenteredMessage(
                        text = stringResource(R.string.browser_error, content.error.message()),
                        action = if (content.error.reason.isPermanent) {
                            null
                        } else {
                            { Button(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) } }
                        },
                    )
                    content is ReaderContent.Opening -> when (val fraction = content.fraction) {
                        null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> CircularProgressIndicator(progress = { fraction }, modifier = Modifier.align(Alignment.Center))
                    }
                }
                ReaderTopBar(state.title, onBack, contents = null, fontScale = null, onFontScale = {})
            }
        }
    }
}

/** The open book, with its bar over the top while [ReaderUiState] says so. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Book(state: ReaderUiState, content: ReaderContent, viewModel: ReaderViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var barShown by rememberSaveable { mutableStateOf(true) }
    HideSystemBars(!barShown)
    var contentsShown by rememberSaveable { mutableStateOf(false) }
    val controller = when (content) {
        is ReaderContent.Reflowable -> content.rendition.controller
        is ReaderContent.Fixed -> content.rendition.controller
        else -> null
    }
    val navigation = controller as? NavigationController<*, *>
    val overflow = controller as? OverflowController

    if (navigation != null) {
        LaunchedEffect(navigation) {
            snapshotFlow { navigation.location }.collect { viewModel.leftAt(it.toLocator()) }
        }
    }
    // The app's theme, which the settings may set apart from the system's.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (content is ReaderContent.Reflowable) {
        val reflowable = content.rendition.controller
        LaunchedEffect(reflowable, dark, state.fontScale) {
            reflowable ?: return@LaunchedEffect
            val theme = if (dark) ReflowableWebPreferences.DarkTheme else ReflowableWebPreferences.LightTheme
            reflowable.preferences = reflowable.preferences + theme + ReflowableWebPreferences(fontSize = state.fontScale)
        }
    }
    val rightToLeft = when (content) {
        is ReaderContent.Reflowable -> content.rendition.controller?.settings?.readingProgression == ReadingProgression.RTL
        is ReaderContent.Fixed -> content.rendition.controller?.settings?.readingProgression == ReadingProgression.RTL
        else -> false
    }

    val toggleBar = remember { object : InputListener {
        override fun onTap(event: TapEvent, context: TapContext) {
            barShown = !barShown
        }
    } }
    val inputListener = defaultInputListener(controller = overflow, fallbackListener = toggleBar)
    val hyperlinkListener = defaultHyperlinkListener(
        controller = navigation,
        onExternalLinkActivated = { url: AbsoluteUrl, _ ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()))
            } catch (_: ActivityNotFoundException) {
                // Nothing on the device opens web links; the book stays where it is.
            }
        },
    )
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (viewerKeyAction(event.key, rightToLeft, zoomed = false)) {
                    ViewerKeyAction.NEXT_PAGE -> scope.launch { overflow?.moveForward() }
                    ViewerKeyAction.PREVIOUS_PAGE -> scope.launch { overflow?.moveBackward() }
                    ViewerKeyAction.TOGGLE_OVERLAY -> barShown = !barShown
                    ViewerKeyAction.TOGGLE_SLIDESHOW, null -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        when (content) {
            is ReaderContent.Reflowable -> ReflowableWebRendition(
                state = content.rendition,
                inputListener = inputListener,
                hyperlinkListener = hyperlinkListener,
            )
            is ReaderContent.Fixed -> FixedWebRendition(
                state = content.rendition,
                inputListener = inputListener,
                hyperlinkListener = hyperlinkListener,
            )
            else -> Unit
        }
        AnimatedVisibility(visible = barShown, enter = fadeIn(), exit = fadeOut()) {
            ReaderTopBar(
                title = state.title,
                onBack = onBack,
                contents = { contentsShown = true }.takeIf { state.contents.isNotEmpty() },
                fontScale = state.fontScale.takeIf { content is ReaderContent.Reflowable },
                onFontScale = viewModel::setFontScale,
            )
        }
    }

    if (contentsShown) {
        ModalBottomSheet(onDismissRequest = { contentsShown = false }) {
            LazyColumn {
                items(state.contents) { entry ->
                    Text(
                        text = entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                contentsShown = false
                                scope.launch { navigation?.goTo(entry.href) }
                            }
                            .padding(start = 24.dp + 16.dp * entry.depth, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    /** Opens the table of contents; null leaves its button out. */
    contents: (() -> Unit)?,
    /** The text size, for books whose text flows; null leaves the size buttons out. */
    fontScale: Double?,
    onFontScale: (Double) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fontScale != null) {
            IconButton(onClick = { onFontScale(fontScale - ReaderViewModel.FONT_SCALE_STEP) }, enabled = fontScale > ReaderViewModel.MIN_FONT_SCALE) {
                Icon(Icons.Outlined.TextDecrease, contentDescription = stringResource(R.string.reader_text_smaller))
            }
            IconButton(onClick = { onFontScale(fontScale + ReaderViewModel.FONT_SCALE_STEP) }, enabled = fontScale < ReaderViewModel.MAX_FONT_SCALE) {
                Icon(Icons.Outlined.TextIncrease, contentDescription = stringResource(R.string.reader_text_larger))
            }
        }
        if (contents != null) {
            IconButton(onClick = contents) {
                Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.reader_contents))
            }
        }
    }
}
