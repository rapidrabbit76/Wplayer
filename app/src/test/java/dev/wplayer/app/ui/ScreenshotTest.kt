package dev.wplayer.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import dev.wplayer.app.data.model.AppSettings
import dev.wplayer.app.data.model.BrowserViewMode
import dev.wplayer.app.data.model.Download
import dev.wplayer.app.data.model.DownloadStatus
import dev.wplayer.app.data.model.MediaEntry
import dev.wplayer.app.data.model.MediaKind
import dev.wplayer.app.data.model.MediaRef
import dev.wplayer.app.data.model.Playlist
import dev.wplayer.app.data.model.RepeatMode
import dev.wplayer.app.data.model.Source
import dev.wplayer.app.data.model.SourceProtocol
import dev.wplayer.app.data.model.SubtitleFont
import dev.wplayer.app.data.model.WatchProgress
import dev.wplayer.app.data.model.WhisperModel
import dev.wplayer.app.data.ImagePage
import dev.wplayer.app.data.pathSegments
import dev.wplayer.app.ui.browser.BrowserContent
import dev.wplayer.app.ui.browser.BrowserLayout
import dev.wplayer.app.ui.browser.BrowserUiState
import dev.wplayer.app.ui.browser.ParentEntry
import dev.wplayer.app.ui.common.UserError
import dev.wplayer.app.ui.downloads.DownloadActions
import dev.wplayer.app.ui.downloads.DownloadsContent
import dev.wplayer.app.ui.library.LibraryContent
import dev.wplayer.app.ui.navigation.ColumnRole
import dev.wplayer.app.ui.navigation.LocalColumnRole
import dev.wplayer.app.ui.player.AiSubtitleStatus
import dev.wplayer.app.ui.player.AspectRatio
import dev.wplayer.app.ui.player.ControlsConfig
import dev.wplayer.app.ui.player.LocalWallClock
import dev.wplayer.app.ui.player.LockedOverlay
import dev.wplayer.app.ui.player.PlayerControlActions
import dev.wplayer.app.ui.player.PlayerControls
import dev.wplayer.app.ui.player.PlayerSettingsSheet
import dev.wplayer.app.ui.player.PlayerSheet
import dev.wplayer.app.ui.player.PlayerUiState
import dev.wplayer.app.ui.player.QueueSheet
import dev.wplayer.app.ui.player.ResumePrompt
import dev.wplayer.app.ui.player.ScaleMode
import dev.wplayer.app.ui.player.SubtitlePanelActions
import dev.wplayer.app.ui.player.SubtitleSheet
import dev.wplayer.app.ui.player.VideoSheet
import dev.wplayer.app.ui.settings.SettingsCategory
import dev.wplayer.app.ui.settings.SettingsCategoryContent
import dev.wplayer.app.ui.settings.SettingsHomeContent
import dev.wplayer.app.ui.settings.SettingsMaintenance
import dev.wplayer.app.ui.sources.ConnectionTest
import dev.wplayer.app.ui.sources.ContinueWatching
import dev.wplayer.app.ui.sources.SourceEditorActions
import dev.wplayer.app.ui.sources.SourceEditorContent
import dev.wplayer.app.ui.sources.SourceEditorUiState
import dev.wplayer.app.ui.sources.SourcesContent
import dev.wplayer.app.ui.theme.WplayerTheme
import dev.wplayer.app.ui.viewer.ImageViewerActions
import dev.wplayer.app.ui.viewer.ImageViewerContent
import dev.wplayer.app.ui.viewer.ImageViewerUiState
import dev.wplayer.app.ui.viewer.PageImage
import dev.wplayer.core.ffi.Track
import dev.wplayer.core.ffi.TrackKind
import dev.wplayer.core.player.PlaybackState
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.wplayer.app.ui.player.SubtitleFileSheet

/**
 * Renders screens at phone and tablet sizes to catch layout breakage without
 * a device. Run `./gradlew :app:recordRoborazziDebug`; images land in
 * `app/build/outputs/roborazzi`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = PHONE)
    fun sources_phone() = capture("sources_phone") { Sources() }

    @Test
    @Config(qualifiers = TABLET)
    fun sources_tablet() = capture("sources_tablet") { Sources() }

    @Test
    @Config(qualifiers = TABLET)
    fun column_view_tablet() = capture("column_view_tablet") {
        // Approximates ColumnSceneStrategy: grandparent, parent (child highlighted), current folder.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { Column(isCurrent = false) { Browser(rootState, selectedPath = "/video") } }
            Box(Modifier.weight(1f)) { Column(isCurrent = false) { Browser(browserState, selectedPath = "/video/드라마/시즌 1") } }
            Box(Modifier.weight(1.25f)) { Column(isCurrent = true) { Browser(seasonState) } }
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun browser_grid_tablet() = capture("browser_grid_tablet") { Browser(seasonState) }

    @Test
    @Config(qualifiers = TABLET)
    fun settings_two_pane_tablet() = capture("settings_two_pane_tablet") {
        // Approximates SettingsScreen on a wide window.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(360.dp)) { SettingsHomeContent(selected = SettingsCategory.SUBTITLES, onOpenCategory = {}) }
            Box(Modifier.weight(1f)) {
                SettingsCategoryContent(SettingsCategory.SUBTITLES, AppSettings(), onBack = null, onUpdate = {}, maintenance = NoOpMaintenance)
            }
        }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun browser_phone() = capture("browser_phone") { Browser(browserState) }

    @Test
    @Config(qualifiers = PHONE)
    fun library_phone() = capture("library_phone") { Library(BrowserViewMode.LIST) }

    @Test
    @Config(qualifiers = TABLET)
    fun library_grid_tablet() = capture("library_grid_tablet") { Library(BrowserViewMode.GRID) }

    @Test
    @Config(qualifiers = PHONE)
    fun browser_error_phone() = capture("browser_error_phone") {
        Browser(BrowserUiState(source = server, isLoading = false, error = UserError(UserError.Reason.CONNECTION, "timed out")))
    }

    @Test
    @Config(qualifiers = PHONE)
    fun source_editor_phone() = capture("source_editor_phone") { Editor() }

    @Test
    @Config(qualifiers = TABLET)
    fun source_editor_tablet() = capture("source_editor_tablet") { Editor() }

    @Test
    @Config(qualifiers = PHONE)
    fun settings_phone() = capture("settings_phone") {
        SettingsHomeContent(selected = null, onOpenCategory = {})
    }

    @Test
    @Config(qualifiers = PHONE)
    fun settings_network_phone() = capture("settings_network_phone") { SettingsPage(SettingsCategory.NETWORK) }

    @Test
    @Config(qualifiers = PHONE)
    fun settings_general_phone() = capture("settings_general_phone") { SettingsPage(SettingsCategory.GENERAL) }

    @Test
    @Config(qualifiers = PHONE)
    fun settings_screen_phone() = capture("settings_screen_phone") { SettingsPage(SettingsCategory.SCREEN) }

    @Test
    @Config(qualifiers = TABLET)
    fun downloads_tablet() = capture("downloads_tablet") { Downloads(BrowserViewMode.GRID) }

    @Test
    @Config(qualifiers = PHONE)
    fun downloads_list_phone() = capture("downloads_list_phone") { Downloads(BrowserViewMode.LIST) }

    @Test
    @Config(qualifiers = TABLET)
    fun subtitle_panel_tablet() = capture("player_subtitle_panel_tablet") { SubtitlePanel() }

    @Test
    @Config(qualifiers = PHONE)
    fun subtitle_panel_phone() = capture("player_subtitle_panel_phone") { SubtitlePanel() }

    @Test
    @Config(qualifiers = TABLET)
    fun subtitle_file_panel_tablet() = capture("player_subtitle_file_panel_tablet") {
        VideoBackground {
            val entries = listOf("Extras", "Season 2").map { MediaEntry(it, "/video/드라마/$it", MediaKind.DIRECTORY, 0, null) } +
                listOf("드라마 1화.smi", "드라마 1화.ko.srt", "드라마 2화.ass").map { MediaEntry(it, "/video/드라마/$it", MediaKind.SUBTITLE, 0, null) }
            SubtitleFileSheet(startPath = "/video/드라마", list = { entries }, onPick = {}, onBack = {}, onDismiss = {})
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun queue_panel_tablet() = capture("player_queue_panel_tablet") {
        VideoBackground {
            QueueSheet(
                queue = (1..8).map { MediaRef("source-1", "/video/드라마/$it.mkv", "드라마 ${it}화 - 부제가 긴 에피소드.mkv", false) },
                currentPath = "/video/드라마/3.mkv",
                repeat = RepeatMode.ALL,
                shuffle = false,
                onRepeatChange = {},
                onShuffleChange = {},
                onPlay = {},
                onDismiss = {},
            )
        }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun settings_panel_tablet() = capture("player_settings_panel_tablet") {
        VideoBackground { PlayerSettingsSheet(AppSettings(), onUpdate = {}, onDismiss = {}) }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun video_panel_tablet() = capture("player_video_panel_tablet") {
        VideoBackground {
            VideoSheet(
                aspect = AspectRatio.DEFAULT,
                scale = ScaleMode.FIT,
                hardwareDecoding = true,
                onAspectChange = {},
                onScaleChange = {},
                onHardwareDecodingChange = {},
                onDismiss = {},
            )
        }
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun resume_prompt_phone() = capture("player_resume_prompt_phone") {
        VideoBackground { ResumePrompt(position = 1834.0, onAnswer = {}) }
    }

    @Test
    @Config(qualifiers = PHONE)
    fun player_phone_portrait() = capture("player_phone_portrait") { Player() }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun player_phone_landscape() = capture("player_phone_landscape") { Player() }

    @Test
    @Config(qualifiers = TABLET)
    fun player_tablet() = capture("player_tablet") { Player() }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun player_locked_phone() = capture("player_locked_phone") {
        VideoBackground { LockedOverlay(onUnlock = {}) }
    }

    @Test
    @Config(qualifiers = TABLET)
    fun viewer_tablet() = captureViewer("viewer_tablet")

    @Test
    @Config(qualifiers = PHONE)
    fun viewer_phone() = captureViewer("viewer_phone")

    @Test
    @Config(qualifiers = TABLET)
    fun comic_viewer_tablet() = captureComicViewer("comic_viewer_tablet")

    @Test
    @Config(qualifiers = PHONE)
    fun comic_viewer_phone() = captureComicViewer("comic_viewer_phone")

    /** The viewer with its overlay, on the second of three pictures. */
    private fun captureViewer(name: String) {
        val pages = listOf("IMG_0001.jpg", "IMG_0002 바다.HEIC", "IMG_0003.png").map { ImagePage(it, "/사진/$it") }
        captureViewer(name, ImageViewerUiState(pages = pages, startPath = pages[1].path), ::writePicture)
    }

    /** A comic archive read right to left, on the second of three pages: the overlay counts from the first. */
    private fun captureComicViewer(name: String) {
        val pages = listOf("001.jpg", "002.jpg", "003.jpg").map { ImagePage(it, "원피스 01권/$it") }
        val state = ImageViewerUiState(pages = pages, startPath = pages[1].path, book = "원피스 01권.cbz", rightToLeft = true)
        captureViewer(name, state, ::writeComicPage)
    }

    /** Every page shows the picture [draw] writes; decoding runs on the test's main thread. */
    private fun captureViewer(name: String, state: ImageViewerUiState, draw: (File) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Main.immediate).build())
        try {
            val picture = File(context.cacheDir, "$name.png").apply(draw)
            val ready = state.copy(images = state.pages.orEmpty().associate { it.path to PageImage.Ready(picture) })
            capture(name) { ImageViewerContent(ready, NoOpViewerActions) }
        } finally {
            SingletonImageLoader.reset()
        }
    }

    /** A portrait comic page: white paper with three black-framed panels, in flat colors so every run draws the same. */
    private fun writeComicPage(file: File) {
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val frame = Paint().apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val tone = Paint().apply { color = 0xFFBDBDBD.toInt() }
        Canvas(bitmap).apply {
            drawColor(0xFFFFFFFF.toInt())
            drawRect(40f, 40f, 760f, 460f, tone)
            drawRect(40f, 40f, 760f, 460f, frame)
            drawRect(40f, 500f, 380f, 1160f, frame)
            drawCircle(210f, 760f, 90f, tone)
            drawRect(420f, 500f, 760f, 1160f, frame)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A landscape stand-in for a photo: sky, sea and a sun, in flat colors so every run draws the same. */
    private fun writePicture(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFF6FA8DC.toInt())
            drawRect(0f, 480f, 1200f, 800f, Paint().apply { color = 0xFF1C4E80.toInt() })
            drawCircle(900f, 260f, 110f, Paint().apply { color = 0xFFFFD966.toInt() })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Player screens use the dark theme, like PlayerActivity. */
    private fun capture(name: String, content: @Composable () -> Unit) {
        val dark = name.startsWith("player")
        compose.setContent {
            CompositionLocalProvider(LocalWallClock provides { FIXED_TIME }) {
                WplayerTheme(darkTheme = dark) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun Sources() = SourcesContent(
        sources = listOf(
            Source(key = "local", name = "", protocol = SourceProtocol.LOCAL),
            server,
            Source(key = "source-2", name = "Office WebDAV", protocol = SourceProtocol.WEBDAVS, host = "dav.example.com"),
            Source(key = "source-3", name = "Router FTP", protocol = SourceProtocol.FTP, host = "192.168.0.1"),
        ),
        continueWatching = listOf(
            ContinueWatching(MediaRef("source-1", "/video/드라마/2.mp4", "드라마 2화.mp4", false), WatchProgress(1500.0, 3600.0)),
            ContinueWatching(MediaRef("source-1", "/movie/긴 제목의 영화 [2160p HDR].mkv", "긴 제목의 영화 [2160p HDR].mkv", false), WatchProgress(5200.0, 7400.0)),
        ),
        onOpenSource = {},
        onAddSource = {},
        onEditSource = {},
        onResume = {},
    )

    /** Provides what ColumnScene provides to each column. */
    @Composable
    private fun Column(isCurrent: Boolean, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalColumnRole provides ColumnRole(isCurrent), content = content)

    @Composable
    private fun Library(viewMode: BrowserViewMode) = LibraryContent(
        playlists = listOf(Playlist(1, "주말에 볼 것", 12), Playlist(2, "애니 북마크", 3)),
        viewMode = viewMode,
        onViewModeChange = {},
        onOpenRecent = {},
        onOpenPlaylist = {},
        onCreatePlaylist = {},
    )

    @Composable
    private fun Downloads(viewMode: BrowserViewMode) {
        val ref = { name: String -> MediaRef("source-1", "/video/$name", name, isDirectory = false) }
        DownloadsContent(
            downloads = listOf(
                Download(1, ref("드라마 1화 [AV1 2160p HDR].mkv"), "/x/1.mkv", 3_421_000_000, 1_200_000_000, DownloadStatus.RUNNING, null),
                Download(2, ref("드라마 2화.mkv"), "/x/2.mkv", null, 0, DownloadStatus.QUEUED, null),
                Download(3, ref("드라마 3화.mkv"), "/x/3.mkv", 1_400_000_000, 1_400_000_000, DownloadStatus.DONE, null),
                Download(4, ref("드라마 4화.mkv"), "/x/4.mkv", 1_400_000_000, 300_000_000, DownloadStatus.FAILED, "HTTP 503"),
            ),
            viewMode = viewMode,
            folderLabel = "Download/Wplayer",
            actions = NoOpDownloadActions,
        )
    }

    @Composable
    private fun Browser(state: BrowserUiState, selectedPath: String? = null) = BrowserLayout(state, onBack = {}, onNavigateTo = {}) {
        val parent = state.parentFolder?.let { path -> ParentEntry(pathSegments(path).lastOrNull()?.name ?: server.name, onOpen = {}) }
        BrowserContent(state, selectedPath, onRefresh = {}, onEntryClick = {}, parentEntry = parent)
    }

    @Composable
    private fun SettingsPage(category: SettingsCategory) =
        SettingsCategoryContent(category, AppSettings(), onBack = {}, onUpdate = {}, maintenance = NoOpMaintenance)

    @Composable
    private fun SubtitlePanel() = VideoBackground {
        SubtitleSheet(
            tracks = listOf(
                Track(id = 1, kind = TrackKind.SUBTITLE, title = "테스트 영상 1화 [AV1].smi", language = null, codec = "sami", selected = true, external = true, isDefault = false),
                Track(id = 2, kind = TrackKind.SUBTITLE, title = null, language = "eng", codec = "subrip", selected = false, external = false, isDefault = false),
            ),
            visible = true,
            delay = -0.3,
            sizePercent = 120,
            position = 92,
            ai = AiSubtitleStatus.Ready(made = false),
            actions = NoOpSubtitleActions,
            onLoadFile = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun Editor() = SourceEditorContent(
        SourceEditorUiState(
            isNew = false,
            protocol = SourceProtocol.NFS,
            name = "Home server",
            host = "192.168.0.10",
            path = "/volume1/video",
            username = "1026:100",
            hasStoredPassword = true,
            connectionTest = ConnectionTest.Failed(UserError(UserError.Reason.AUTHENTICATION)),
        ),
        NoOpEditorActions,
    )

    @Composable
    private fun Player() = VideoBackground {
        PlayerControls(
            state = PlayerUiState(
                title = "[1080p] 아주 긴 한국어 영화 제목이 들어가는 경우 - 감독판 확장 에디션.mkv",
                playback = PlaybackState(
                    fileLoaded = true,
                    position = 1834.0,
                    duration = 7421.0,
                    paused = false,
                    hardwareDecoder = "mediacodec",
                    videoCodec = "av1",
                    abLoopStart = 1800.0,
                    abLoopEnd = 1860.0,
                ),
            ),
            config = ControlsConfig(seekBackSeconds = 10, seekForwardSeconds = 30, showClock = true, hasPrevious = true, hasNext = true),
            actions = NoOpPlayerActions,
        )
    }

    /** Stands in for video, with the same content color the real overlay sets. */
    @Composable
    private fun VideoBackground(content: @Composable () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF0B0F1A), Color(0xFF3A4A66), Color(0xFF0B0F1A)))),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
        }
    }

    private companion object {
        const val PHONE = "w360dp-h800dp-port-xxhdpi"
        const val PHONE_LANDSCAPE = "w800dp-h360dp-land-xxhdpi"
        const val TABLET = "w1280dp-h800dp-land-xhdpi"

        /** 21:30 in the machine's time zone, so the player clock reads the same on every run. */
        val FIXED_TIME = LocalDateTime.of(2026, 1, 1, 21, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val server = Source(key = "source-1", name = "Home server", protocol = SourceProtocol.SMB, host = "192.168.0.10")

        val browserState = BrowserUiState(
            source = server,
            path = "/video/드라마",
            isLoading = false,
            entries = listOf(
                MediaEntry("시즌 1", "/video/드라마/시즌 1", MediaKind.DIRECTORY, 0, null),
                MediaEntry("시즌 2", "/video/드라마/시즌 2", MediaKind.DIRECTORY, 0, null),
                MediaEntry("드라마 제목이 꽤 긴 경우 1화 - 처음 만난 날 [AV1 2160p HDR].mkv", "/video/드라마/1.mkv", MediaKind.VIDEO, 3_421_000_000, null),
                MediaEntry("드라마 2화.mp4", "/video/드라마/2.mp4", MediaKind.VIDEO, 1_200_000_000, null),
                MediaEntry("OST - 메인 테마.flac", "/video/드라마/ost.flac", MediaKind.AUDIO, 42_000_000, null),
            ),
            progress = mapOf("/video/드라마/2.mp4" to WatchProgress(1500.0, 3600.0)),
        )

        val rootState = BrowserUiState(
            source = server,
            path = "/",
            isLoading = false,
            entries = listOf("music", "photo", "video").map { MediaEntry(it, "/$it", MediaKind.DIRECTORY, 0, null) },
        )

        val seasonState = BrowserUiState(
            source = server,
            path = "/video/드라마/시즌 1",
            isLoading = false,
            entries = (1..6).map { MediaEntry("시즌1 ${it}화.mkv", "/video/드라마/시즌 1/$it.mkv", MediaKind.VIDEO, 1_400_000_000, null) },
            progress = mapOf("/video/드라마/시즌 1/1.mkv" to WatchProgress(3500.0, 3600.0)),
        )
    }
}

private object NoOpPlayerActions : PlayerControlActions {
    override fun onBack() = Unit
    override fun onTogglePause() = Unit
    override fun onSeekBy(seconds: Double) = Unit
    override fun onSeekTo(seconds: Double) = Unit
    override fun onSeekBarDragging(dragging: Boolean) = Unit
    override fun onSeekPreview(seconds: Double) = Unit
    override fun onSeekPreviewEnd() = Unit
    override fun onPrevious() = Unit
    override fun onNext() = Unit
    override fun onOpenSheet(panel: PlayerSheet) = Unit
    override fun onCycleAbRepeat() = Unit
    override fun onLock() = Unit
    override fun onPictureInPicture() = Unit
    override fun onSetSpeed(speed: Double) = Unit
    override fun onSetScale(scale: ScaleMode) = Unit
    override fun onSetAspect(aspect: AspectRatio) = Unit
    override fun onSetHardwareDecoding(enabled: Boolean) = Unit
    override fun onQuickMenu(open: Boolean) = Unit
}

private object NoOpDownloadActions : DownloadActions {
    override fun onPlay(download: Download) = Unit
    override fun onCancel(download: Download) = Unit
    override fun onRetry(download: Download) = Unit
    override fun onRemove(download: Download, deleteFile: Boolean) = Unit
    override fun onClearFinished() = Unit
    override fun onViewModeChange(mode: BrowserViewMode) = Unit
}

private object NoOpSubtitleActions : SubtitlePanelActions {
    override fun onSelectTrack(id: Long?) = Unit
    override fun onVisibleChange(visible: Boolean) = Unit
    override fun onDelayChange(seconds: Double) = Unit
    override fun onSizeChange(percent: Int, save: Boolean) = Unit
    override fun onPositionChange(position: Int, save: Boolean) = Unit
    override fun onStartAiSubtitles() = Unit
    override fun onCancelAiSubtitles() = Unit
    override fun onDownloadAiModel() = Unit
}

private object NoOpEditorActions : SourceEditorActions {
    override fun onProtocolChange(protocol: SourceProtocol) = Unit
    override fun onNameChange(name: String) = Unit
    override fun onHostChange(host: String) = Unit
    override fun onPortChange(port: String) = Unit
    override fun onPathChange(path: String) = Unit
    override fun onUsernameChange(username: String) = Unit
    override fun onPasswordChange(password: String) = Unit
    override fun onAllowUntrustedChange(allow: Boolean) = Unit
    override fun onTest() = Unit
    override fun onSave() = Unit
    override fun onDelete() = Unit
    override fun onDone() = Unit
}

private object NoOpViewerActions : ImageViewerActions {
    override fun back() = Unit
    override fun show(page: ImagePage) = Unit
    override fun hide(page: ImagePage) = Unit
    override fun settle(page: ImagePage) = Unit
    override fun retry(page: ImagePage) = Unit
    override fun unreadable(page: ImagePage) = Unit
    override fun reload() = Unit
    override fun unlock() = Unit
}

private object NoOpMaintenance : SettingsMaintenance {
    override fun clearHistory() = Unit
    override fun clearThumbnails() = Unit
    override fun clearSearchIndex() = Unit
    override fun downloadWhisperModel(model: WhisperModel) = Unit
    override fun cancelWhisperModel(model: WhisperModel) = Unit
    override fun deleteWhisperModel(model: WhisperModel) = Unit
    override fun add(uri: Uri) = Unit
    override fun delete(font: SubtitleFont) = Unit
    override fun dismissFailure() = Unit
}
