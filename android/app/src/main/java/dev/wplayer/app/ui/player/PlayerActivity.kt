package dev.wplayer.app.ui.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.wplayer.app.R
import dev.wplayer.app.data.model.OrientationMode
import dev.wplayer.app.ui.common.isTelevision
import dev.wplayer.app.ui.player.PlaybackRequest.Companion.writeTo
import dev.wplayer.app.ui.theme.WplayerTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Full-screen player. A separate activity (own task) so picture-in-picture
 * shrinks only the video, not the browser.
 */
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {
    private val viewModel: PlayerViewModel by viewModels()
    private var inPictureInPicture by mutableStateOf(false)

    /** Where the video is drawn, so the PiP animation starts from it. */
    private val videoBounds = MutableStateFlow<Rect?>(null)

    private val keys = PlayerKeys()

    /** Focus is on the controls or a panel: the D-pad moves it instead of seeking. */
    private var keysNavigate = false
    private val keyCommands = MutableSharedFlow<PlayerKeyCommand>(extraBufferCapacity = KEY_COMMAND_BUFFER)

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TOGGLE_PAUSE) viewModel.togglePause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_TOGGLE_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (savedInstanceState == null) {
            val request = PlaybackRequest.from(intent) ?: return finish()
            viewModel.open(request)
        }

        setContent {
            WplayerTheme(darkTheme = true) {
                PlayerScreen(
                    viewModel = viewModel,
                    inPictureInPicture = inPictureInPicture,
                    canPictureInPicture = supportsPip,
                    onEnterPictureInPicture = ::enterPip,
                    onVideoBoundsChanged = { videoBounds.value = it },
                    keyCommands = keyCommands,
                    onKeysNavigateChange = { keysNavigate = it },
                    onClose = ::finish,
                )
            }
        }
        observePlayback()
    }

    /** Remote and keyboard keys reach the player before the views, which would move focus with them. */
    // Activity.dispatchKeyEvent is public API; androidx.core's override only marks its own copy restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = !inPictureInPicture &&
            keys.handle(event.keyCode, event.action, event.repeatCount, event.isCanceled, keysNavigate) { keyCommands.tryEmit(it) }
        return consumed || super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        PlaybackRequest.from(intent)?.let(viewModel::open)
    }

    /** Keeps the screen awake, PiP parameters and phone orientation in sync with playback. */
    private fun observePlayback() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(viewModel.uiState, viewModel.settings, videoBounds) { ui, settings, bounds ->
                    PipInputs(
                        playing = !ui.playback.paused,
                        width = ui.playback.videoWidth,
                        height = ui.playback.videoHeight,
                        autoEnter = settings.playback.autoPictureInPicture,
                        bounds = bounds,
                        orientation = settings.screen.orientation,
                    )
                }.distinctUntilChanged().collect { inputs ->
                    if (inputs.playing) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    updatePipParams(inputs)
                    applyOrientation(inputs.orientation, inputs.width, inputs.height)
                }
            }
        }
    }

    private data class PipInputs(
        val playing: Boolean,
        val width: Int,
        val height: Int,
        val autoEnter: Boolean,
        val bounds: Rect?,
        val orientation: OrientationMode,
    )

    private fun pipParams(inputs: PipInputs): PictureInPictureParams {
        val toggle = RemoteAction(
            Icon.createWithResource(this, if (inputs.playing) R.drawable.ic_pause else R.drawable.ic_play),
            getString(if (inputs.playing) R.string.player_pause else R.string.player_play),
            getString(if (inputs.playing) R.string.player_pause else R.string.player_play),
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_TOGGLE_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return PictureInPictureParams.Builder()
            .setActions(listOf(toggle))
            .apply {
                if (inputs.width > 0 && inputs.height > 0) {
                    setAspectRatio(clampedAspectRatio(inputs.width, inputs.height))
                }
                inputs.bounds?.let(::setSourceRectHint)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(inputs.autoEnter && inputs.playing)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private val supportsPip: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun enterPip() {
        if (!supportsPip) return
        // Throws when the user turned PiP off for this app in system settings.
        runCatching { enterPictureInPictureMode(pipParams(currentPipInputs())) }
            .onFailure { Log.w(TAG, "cannot enter picture-in-picture", it) }
    }

    private fun updatePipParams(inputs: PipInputs) {
        if (!supportsPip) return
        runCatching { setPictureInPictureParams(pipParams(inputs)) }
            .onFailure { Log.w(TAG, "cannot update picture-in-picture params", it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically via setAutoEnterEnabled.
        val inputs = currentPipInputs()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && inputs.autoEnter && inputs.playing) enterPip()
    }

    private fun currentPipInputs(): PipInputs {
        val ui = viewModel.uiState.value
        return PipInputs(
            playing = !ui.playback.paused,
            width = ui.playback.videoWidth,
            height = ui.playback.videoHeight,
            autoEnter = viewModel.settings.value.playback.autoPictureInPicture,
            bounds = videoBounds.value,
            orientation = viewModel.settings.value.screen.orientation,
        )
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        // Leaving PiP while not started means the user dismissed the window (not expanded it).
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            finishAndRemoveTask()
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.enterForeground()
    }

    override fun onStop() {
        super.onStop()
        // A PiP window keeps the activity started; stopping means nothing shows the video.
        // Leaving the player (back) always pauses; otherwise the sound may continue.
        if (viewModel.settings.value.playback.backgroundPlay && !isFinishing && !isChangingConfigurations) {
            viewModel.enterBackground()
        } else {
            viewModel.pause()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(pipActionReceiver)
        super.onDestroy()
    }

    /** By default phones follow the video's orientation; tablets keep whatever the user holds, TVs their own. */
    private fun applyOrientation(mode: OrientationMode, width: Int, height: Int) {
        requestedOrientation = when (mode) {
            OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.AUTO -> when {
                isTablet() || resources.configuration.isTelevision -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                width == 0 || height == 0 -> return
                width >= height -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    /** Judged by the whole display, so split screen or PiP on a tablet still counts as a tablet. */
    private fun isTablet(): Boolean {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { minOf(it.width(), it.height()) }
        } else {
            resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) }
        }
        return bounds / resources.displayMetrics.density >= TABLET_MIN_WIDTH_DP
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val ACTION_TOGGLE_PAUSE = "dev.wplayer.action.TOGGLE_PAUSE"
        private const val TAG = "PlayerActivity"
        private const val TABLET_MIN_WIDTH_DP = 600
        private const val KEY_COMMAND_BUFFER = 16

        fun createIntent(context: Context, request: PlaybackRequest): Intent {
            val intent = Intent(context, PlayerActivity::class.java)
            return when (request) {
                is PlaybackRequest.FromSource -> request.writeTo(intent)
                is PlaybackRequest.FromUri -> intent.setData(request.uri)
            }
        }

        /** PiP only accepts aspect ratios between 1:2.39 and 2.39:1. */
        private fun clampedAspectRatio(width: Int, height: Int): Rational {
            // Rounded to 1/10000 and kept just inside the limits so rounding never exceeds them.
            val ratio = (width.toFloat() / height).coerceIn(MIN_PIP_RATIO, MAX_PIP_RATIO)
            return Rational((ratio * 10_000).roundToInt(), 10_000)
        }

        private const val MIN_PIP_RATIO = 0.4185f
        private const val MAX_PIP_RATIO = 2.389f
    }
}
