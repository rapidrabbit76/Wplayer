package dev.wplayer.app.ui.viewer

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.ImageRequest
import dev.wplayer.app.R
import dev.wplayer.app.data.ImagePage
import dev.wplayer.app.ui.browser.LockedFolderMessage
import dev.wplayer.app.ui.common.CenteredMessage
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.common.message
import dev.wplayer.app.ui.common.rememberDeviceAuthentication
import dev.wplayer.app.ui.theme.WplayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.HardwareShortcutsSpec
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

interface ImageViewerActions {
    fun back()

    /** A page came on or next to the screen. */
    fun show(page: ImagePage)

    /** A page moved away. */
    fun hide(page: ImagePage)

    /** The pager came to rest on a page. */
    fun settle(page: ImagePage)

    fun retry(page: ImagePage)

    fun unreadable(page: ImagePage)

    /** Lists the folder again after it failed. */
    fun reload()

    fun unlock()
}

@Composable
fun ImageViewerScreen(viewModel: ImageViewerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auth = rememberDeviceAuthentication()
    val openLockedTitle = stringResource(R.string.locked_folder_open)
    val actions = remember(viewModel, onBack, auth, openLockedTitle) {
        object : ImageViewerActions {
            override fun back() = onBack()
            override fun show(page: ImagePage) = viewModel.show(page)
            override fun hide(page: ImagePage) = viewModel.hide(page)
            override fun settle(page: ImagePage) = viewModel.settle(page)
            override fun retry(page: ImagePage) = viewModel.retry(page)
            override fun unreadable(page: ImagePage) = viewModel.unreadable(page)
            override fun reload() = viewModel.load()
            override fun unlock() = auth.authenticate(openLockedTitle, viewModel::unlockSession)
        }
    }
    ImageViewerContent(state, actions)
}

/** Pictures on black, whatever the app's theme. */
@Composable
internal fun ImageViewerContent(state: ImageViewerUiState, actions: ImageViewerActions) {
    LightSystemBarIcons()
    WplayerTheme(darkTheme = true, pureBlack = true) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            val pages = state.pages.takeUnless { state.hiddenByLock }.orEmpty()
            if (pages.isNotEmpty()) {
                ImagePager(state, pages, actions)
            } else {
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.hiddenByLock -> LockedFolderMessage(onOpen = actions::unlock)
                        state.error != null -> CenteredMessage(
                            text = stringResource(R.string.browser_error, state.error.message()),
                            action = if (state.error.reason.isPermanent) {
                                null
                            } else {
                                { Button(onClick = actions::reload) { Text(stringResource(R.string.action_retry)) } }
                            },
                        )
                        state.pages == null -> when (val fraction = state.openingProgress) {
                            null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            else -> CircularProgressIndicator(progress = { fraction }, modifier = Modifier.align(Alignment.Center))
                        }
                        state.book != null -> CenteredMessage(stringResource(R.string.image_viewer_archive_empty))
                        else -> CenteredMessage(stringResource(R.string.image_viewer_empty))
                    }
                    ViewerTopBar(title = state.book, position = null, slideshow = null, onBack = actions::back, onSlideshowChange = {})
                }
            }
        }
    }
}

@Composable
private fun ImagePager(state: ImageViewerUiState, pages: List<ImagePage>, actions: ImageViewerActions) {
    val pagerState = rememberPagerState(initialPage = state.startPage) { pages.size }
    var overlay by rememberSaveable { mutableStateOf(true) }
    var slideshow by rememberSaveable { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    val toggleOverlay: () -> Unit = {
        if (slideshow) {
            slideshow = false
            overlay = true
        } else {
            overlay = !overlay
        }
    }
    val setSlideshow: (Boolean) -> Unit = { on ->
        slideshow = on
        overlay = !on
    }
    val go: (Int) -> Unit = { step ->
        slideshow = false
        scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + step).coerceIn(0, pages.lastIndex)) }
    }

    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) slideshow = false }
    Slideshow(slideshow, pagerState, pages, state, onEnd = { slideshow = false })
    KeepScreenOn(slideshow)
    HideSystemBars(!overlay)
    LaunchedEffect(Unit) { focus.requestFocus() }
    val currentPages by rememberUpdatedState(pages)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { index -> currentPages.getOrNull(index)?.let(actions::settle) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                val action = viewerKeyAction(event.key, state.rightToLeft, zoomed)
                if (event.type == KeyEventType.KeyDown) {
                    when (action) {
                        ViewerKeyAction.NEXT_PAGE -> go(1)
                        ViewerKeyAction.PREVIOUS_PAGE -> go(-1)
                        ViewerKeyAction.TOGGLE_OVERLAY -> toggleOverlay()
                        ViewerKeyAction.TOGGLE_SLIDESHOW -> setSlideshow(!slideshow)
                        null -> Unit
                    }
                }
                action != null
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { pages[it].path },
            beyondViewportPageCount = 1,
            // Right to left puts the first page on the right, so a swipe to the right turns to the next.
            reverseLayout = state.rightToLeft,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val page = pages[index]
            PictureSlot(
                page = page,
                image = state.images[page.path],
                isCurrent = index == pagerState.currentPage,
                actions = actions,
                onTap = toggleOverlay,
                onZoomedChange = { now ->
                    zoomed = now
                    if (now) slideshow = false
                },
            )
        }
        AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                title = state.book ?: pages.getOrNull(pagerState.currentPage)?.name,
                position = stringResource(R.string.image_viewer_position, pagerState.currentPage + 1, pages.size),
                slideshow = slideshow,
                onBack = actions::back,
                onSlideshowChange = setSlideshow,
            )
        }
    }
}

/**
 * Moves on every [ImageViewerUiState.slideshowSeconds] while [running], timed
 * from when the shown picture has arrived (or failed); [onEnd] at the last one.
 */
@Composable
private fun Slideshow(running: Boolean, pagerState: PagerState, pages: List<ImagePage>, state: ImageViewerUiState, onEnd: () -> Unit) {
    val settled = pages.getOrNull(pagerState.settledPage)
    val arrived = settled?.let { state.images[it.path] }.let { it != null && it !is PageImage.Arriving }
    LaunchedEffect(running, settled, arrived) {
        if (!running || !arrived) return@LaunchedEffect
        delay(state.slideshowSeconds * 1_000L)
        if (pagerState.settledPage < pages.lastIndex) pagerState.animateScrollToPage(pagerState.settledPage + 1) else onEnd()
    }
}

/** One page: the picture once it has arrived, its progress until then, or why it cannot be shown. */
@Composable
private fun PictureSlot(
    page: ImagePage,
    image: PageImage?,
    isCurrent: Boolean,
    actions: ImageViewerActions,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
) {
    DisposableEffect(page.path) {
        actions.show(page)
        onDispose { actions.hide(page) }
    }
    val tapModifier = Modifier.pointerInput(onTap) { detectTapGestures { onTap() } }
    when (image) {
        null, is PageImage.Arriving -> Box(Modifier.fillMaxSize().then(tapModifier), contentAlignment = Alignment.Center) {
            val fraction = image?.fraction
            if (fraction != null) CircularProgressIndicator(progress = { fraction }) else CircularProgressIndicator()
        }
        is PageImage.Ready -> Picture(page, image, isCurrent, actions, onTap, onZoomedChange)
        is PageImage.Failed -> CenteredMessage(stringResource(R.string.image_viewer_failed, image.error.message()), tapModifier) {
            Button(onClick = { actions.retry(page) }) { Text(stringResource(R.string.action_retry)) }
        }
        PageImage.Unreadable -> CenteredMessage(stringResource(R.string.image_viewer_unreadable), tapModifier)
    }
}

@Composable
private fun Picture(
    page: ImagePage,
    image: PageImage.Ready,
    isCurrent: Boolean,
    actions: ImageViewerActions,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val request = remember(image.file) {
        ImageRequest.Builder(context)
            .data(image.file)
            .listener(onError = { _, _ -> actions.unreadable(page) })
            .build()
    }
    // A remote's arrows page through the folder, so they are not given to the picture for panning.
    val zoomState = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM_FACTOR),
        hardwareShortcutsSpec = HardwareShortcutsSpec.Disabled,
    )
    val imageState = rememberZoomableImageState(zoomState)
    if (isCurrent) {
        LaunchedEffect(zoomState) {
            snapshotFlow { (zoomState.zoomFraction ?: 0f) > ZOOMED_FRACTION }.collect(onZoomedChange)
        }
    } else {
        LaunchedEffect(zoomState) { zoomState.resetZoom(snap()) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = page.name,
            state = imageState,
            onClick = { onTap() },
            modifier = Modifier.fillMaxSize(),
        )
        if (!imageState.isImageDisplayed) CircularProgressIndicator()
    }
}

@Composable
private fun ViewerTopBar(
    title: String?,
    position: String?,
    /** Whether the slideshow runs; null leaves its button out. */
    slideshow: Boolean?,
    onBack: () -> Unit,
    onSlideshowChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = SCRIM_ALPHA), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            position?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (slideshow != null) {
            IconButton(onClick = { onSlideshowChange(!slideshow) }) {
                Icon(
                    imageVector = if (slideshow) Icons.Filled.Stop else Icons.Outlined.Slideshow,
                    contentDescription = stringResource(if (slideshow) R.string.image_viewer_slideshow_stop else R.string.image_viewer_slideshow_start),
                )
            }
        }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Light status and navigation bar icons over the black viewer, whatever the app's theme. */
@Composable
private fun LightSystemBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}

/** Hides the status and navigation bars while [hidden]; a swipe from the edge shows them for a moment. */
@Composable
internal fun HideSystemBars(hidden: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view, hidden) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private const val MAX_ZOOM_FACTOR = 8f

/** Zoomed further in than this (of the way to the maximum) counts as zoomed. */
private const val ZOOMED_FRACTION = 0.01f
private const val SCRIM_ALPHA = 0.6f
