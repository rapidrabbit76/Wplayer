package dev.wplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.wplayer.app.R
import dev.wplayer.app.data.folderOf
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.ui.browser.BrowserScreen
import dev.wplayer.app.ui.browser.BrowserViewModel
import dev.wplayer.app.ui.common.isTelevision
import dev.wplayer.app.ui.downloads.DownloadsScreen
import dev.wplayer.app.ui.library.LibraryScreen
import dev.wplayer.app.ui.library.PlaylistScreen
import dev.wplayer.app.ui.library.PlaylistViewModel
import dev.wplayer.app.ui.library.RecentScreen
import dev.wplayer.app.ui.navigation.BrowserRoute
import dev.wplayer.app.ui.navigation.ColumnSceneStrategy
import dev.wplayer.app.ui.navigation.DownloadsRoute
import dev.wplayer.app.ui.navigation.ImageViewerRoute
import dev.wplayer.app.ui.navigation.LibraryRoute
import dev.wplayer.app.ui.navigation.PlaylistRoute
import dev.wplayer.app.ui.navigation.RecentRoute
import dev.wplayer.app.ui.navigation.SettingsCategoryRoute
import dev.wplayer.app.ui.navigation.SettingsRoute
import dev.wplayer.app.ui.navigation.SourceEditorRoute
import dev.wplayer.app.ui.navigation.SourcesRoute
import dev.wplayer.app.ui.player.PlaybackRequest
import dev.wplayer.app.ui.settings.SettingsCategoryScreen
import dev.wplayer.app.ui.settings.SettingsScreen
import dev.wplayer.app.ui.sources.SourceEditorScreen
import dev.wplayer.app.ui.sources.SourceEditorViewModel
import dev.wplayer.app.ui.sources.SourcesScreen
import dev.wplayer.app.ui.viewer.ImageViewerScreen
import dev.wplayer.app.ui.viewer.ImageViewerViewModel

/** The bottom bar's tabs, each with its own back stack. */
private enum class Tab(val root: NavKey, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    SOURCES(SourcesRoute, R.string.tab_sources, Icons.Outlined.Folder, Icons.Filled.Folder),
    LIBRARY(LibraryRoute, R.string.tab_library, Icons.AutoMirrored.Outlined.PlaylistPlay, Icons.AutoMirrored.Filled.PlaylistPlay),
    DOWNLOADS(DownloadsRoute, R.string.tab_downloads, Icons.Outlined.Download, Icons.Filled.Download),
    SETTINGS(SettingsRoute, R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * App navigation: a bottom bar switches between sources, playlists and
 * settings. Each screen uses the full width; with [columnView] on a wide
 * window, parent folders stay visible as columns beside the current one.
 */
@Composable
fun WplayerApp(columnView: Boolean, onPlay: (PlaybackRequest) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.SOURCES) }
    val stacks = Tab.entries.associateWith { rememberNavBackStack(it.root) }
    val backStack = stacks.getValue(tab)

    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val maxColumns = when {
        !columnView -> 1
        windowWidth >= THREE_COLUMN_MIN_WIDTH -> 3
        windowWidth >= TWO_COLUMN_MIN_WIDTH -> 2
        else -> 1
    }
    val columnStrategy = remember(maxColumns) { ColumnSceneStrategy<NavKey>(maxColumns) }

    // At another tab's root, back returns to the sources tab before leaving the app.
    BackHandler(enabled = tab != Tab.SOURCES && backStack.size == 1) { tab = Tab.SOURCES }

    // TVs get a rail on the left, the remote's way in; tablets a slim bar with inline labels;
    // phones the standard short bar (icon above label). The image viewer covers the whole screen.
    val television = isTelevision()
    val wide = windowWidth >= WIDE_BAR_MIN_WIDTH
    val fullScreen = backStack.lastOrNull() is ImageViewerRoute
    // Pictures open in the viewer, in this tab; everything else plays in the player.
    val openMedia: (PlaybackRequest) -> Unit = { request ->
        if (request is PlaybackRequest.FromSource && MediaEntry.kindOf(request.path, isDirectory = false) == MediaKind.IMAGE) {
            backStack.add(ImageViewerRoute(request.sourceKey, request.path))
        } else {
            onPlay(request)
        }
    }
    val onTabClick: (Tab) -> Unit = { item ->
        // Tapping the current tab again goes back to its first screen.
        if (item == tab) stacks.getValue(item).popToRoot() else tab = item
    }
    Scaffold(
        contentWindowInsets = if (fullScreen) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (television || fullScreen) {
                Unit
            } else if (wide) {
                SlimTabBar(selected = tab, onClick = onTabClick)
            } else {
                ShortNavigationBar(arrangement = ShortNavigationBarArrangement.EqualWeight) {
                    Tab.entries.forEach { item ->
                        val selected = item == tab
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { onTabClick(item) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).consumeWindowInsets(padding)) {
            if (television && !fullScreen) TabRail(selected = tab, onClick = onTabClick)
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                sceneStrategies = listOf(columnStrategy),
                modifier = Modifier.weight(1f),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<SourcesRoute> {
                        SourcesScreen(
                            onOpenSource = { key -> backStack.add(BrowserRoute(key)) },
                            onShowInFolder = backStack::showInFolder,
                            onAddSource = { backStack.add(SourceEditorRoute()) },
                            onEditSource = { key -> backStack.add(SourceEditorRoute(key)) },
                            onPlay = openMedia,
                        )
                    }
                    entry<SourceEditorRoute> { route ->
                        SourceEditorScreen(
                            viewModel = hiltViewModel<SourceEditorViewModel, SourceEditorViewModel.Factory>(
                                creationCallback = { it.create(route) },
                            ),
                            onDone = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<BrowserRoute>(metadata = ColumnSceneStrategy.column()) { route ->
                        BrowserScreen(
                            viewModel = hiltViewModel<BrowserViewModel, BrowserViewModel.Factory>(
                                creationCallback = { it.create(route) },
                            ),
                            selectedPath = backStack.childOf(route)?.path,
                            focusPath = route.focusPath,
                            onOpenFolder = { path -> backStack.openFolder(from = route, path = path) },
                            onShowInFolder = { path -> backStack.openFolder(from = route, path = folderOf(path), focusPath = path) },
                            onNavigateTo = { path -> backStack.goToFolder(from = route, path = path) },
                            onPlay = openMedia,
                            onBack = { backStack.closeFolder(route) },
                        )
                    }
                    entry<ImageViewerRoute> { route ->
                        ImageViewerScreen(
                            viewModel = hiltViewModel<ImageViewerViewModel, ImageViewerViewModel.Factory>(
                                creationCallback = { it.create(route) },
                            ),
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<LibraryRoute> {
                        LibraryScreen(
                            onOpenRecent = { backStack.add(RecentRoute) },
                            onOpenPlaylist = { id -> backStack.add(PlaylistRoute(id)) },
                        )
                    }
                    entry<PlaylistRoute> { route ->
                        PlaylistScreen(
                            viewModel = hiltViewModel<PlaylistViewModel, PlaylistViewModel.Factory>(
                                creationCallback = { it.create(route.playlistId) },
                            ),
                            onPlay = openMedia,
                            onOpenFolder = { sourceKey, path -> backStack.add(BrowserRoute(sourceKey, path)) },
                            onShowInFolder = backStack::showInFolder,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<RecentRoute> {
                        RecentScreen(
                            viewModel = hiltViewModel(),
                            onPlay = openMedia,
                            onShowInFolder = backStack::showInFolder,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<SettingsRoute> {
                        SettingsScreen(onOpenCategory = { category -> backStack.add(SettingsCategoryRoute(category)) })
                    }
                    entry<SettingsCategoryRoute> { route ->
                        SettingsCategoryScreen(route.category, onBack = { backStack.removeLastOrNull() })
                    }
                    entry<DownloadsRoute> {
                        DownloadsScreen(onPlay = openMedia)
                    }
                },
            )
        }
    }
}

/**
 * The tabs on a TV, down the left edge. Coming back from the content, focus
 * lands on the current tab rather than the one nearest on screen.
 */
@Composable
private fun TabRail(selected: Tab, onClick: (Tab) -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .focusProperties { onEnter = { selectedFocus.requestFocus() } }
            .focusGroup(),
    ) {
        Spacer(Modifier.weight(1f))
        Tab.entries.forEach { item ->
            val isSelected = item == selected
            NavigationRailItem(
                selected = isSelected,
                onClick = { onClick(item) },
                icon = { Icon(if (isSelected) item.selectedIcon else item.icon, contentDescription = null) },
                label = { Text(stringResource(item.label)) },
                modifier = if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The tab bar for wide windows: 48dp tall, icon and label side by side in a
 * pill, grouped in the middle. The standard bars are 64-80dp, which looks
 * oversized and sparse across a tablet.
 */
@Composable
private fun SlimTabBar(selected: Tab, onClick: (Tab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { item ->
                val isSelected = item == selected
                // The whole 48dp height is the touch target; the pill is only its visual.
                Box(
                    modifier = Modifier
                        .height(SLIM_BAR_HEIGHT)
                        .selectable(selected = isSelected, onClick = { onClick(item) }, role = Role.Tab),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(item.label),
                            style = MaterialTheme.typography.labelLarge,
                            color = tint,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun NavBackStack<NavKey>.popToRoot() {
    while (size > 1) removeAt(lastIndex)
}

/**
 * Opens a subfolder of [from]. In column view [from] may be a left column,
 * so the folders opened after it are replaced rather than stacked.
 */
private fun NavBackStack<NavKey>.openFolder(from: BrowserRoute, path: String, focusPath: String? = null) {
    val index = lastIndexOf(from)
    if (index >= 0) {
        while (lastIndex > index) removeAt(lastIndex)
    }
    add(BrowserRoute(from.sourceKey, path, focusPath))
}

/** Opens the folder holding [ref] with [ref] scrolled to and highlighted. */
private fun NavBackStack<NavKey>.showInFolder(ref: MediaRef) {
    add(BrowserRoute(ref.sourceKey, folderOf(ref.path), focusPath = ref.path))
}

/**
 * Goes from the folder [from] up to [path]: back to that folder when it is open
 * before [from], or in place of [from] when [from] was opened directly (from a bookmark).
 */
private fun NavBackStack<NavKey>.goToFolder(from: BrowserRoute, path: String) {
    val index = lastIndexOf(from)
    if (index < 0) return
    val target = BrowserRoute(from.sourceKey, path)
    val open = (0 until index).lastOrNull { get(it) == target }
    if (open != null) {
        while (lastIndex > open) removeAt(lastIndex)
    } else {
        while (lastIndex >= index) removeAt(lastIndex)
        add(target)
    }
}

/** Back from the folder [route] closes it and anything opened after it. */
private fun NavBackStack<NavKey>.closeFolder(route: BrowserRoute) {
    val index = lastIndexOf(route)
    if (index < 0) return
    while (lastIndex >= index) removeAt(lastIndex)
}

/** The folder opened from [route] in the same source, shown to its right in column view. */
private fun NavBackStack<NavKey>.childOf(route: BrowserRoute): BrowserRoute? {
    val index = lastIndexOf(route)
    return (getOrNull(index + 1) as? BrowserRoute)?.takeIf { it.sourceKey == route.sourceKey }
}

private val WIDE_BAR_MIN_WIDTH = 600.dp
private val SLIM_BAR_HEIGHT = 48.dp

/** Narrower windows would make each column too cramped for file names. */
private val TWO_COLUMN_MIN_WIDTH = 840.dp
private val THREE_COLUMN_MIN_WIDTH = 1200.dp
