package dev.wplayer.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.wplayer.app.ui.WplayerApp
import dev.wplayer.app.ui.common.LocalMediaScale
import dev.wplayer.app.ui.common.MAX_MEDIA_SCALE
import dev.wplayer.app.ui.common.MIN_MEDIA_SCALE
import dev.wplayer.app.ui.player.PlayerActivity
import dev.wplayer.app.ui.settings.UpdateDialog
import dev.wplayer.app.ui.theme.WplayerTheme
import dev.wplayer.app.ui.theme.isDark

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Settings are read in a few milliseconds; drawing nothing until then avoids a theme flash.
            val shell by viewModel.shell.collectAsStateWithLifecycle()
            val current = shell ?: return@setContent
            val dark = current.appearance.themeMode.isDark()
            // Status bar icons follow the app theme, which may differ from the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            WplayerTheme(darkTheme = dark, pureBlack = current.appearance.pureBlack, dynamicColor = current.appearance.dynamicColor) {
                CompositionLocalProvider(LocalMediaScale provides current.thumbnailScale.coerceIn(MIN_MEDIA_SCALE, MAX_MEDIA_SCALE)) {
                    WplayerApp(
                        columnView = current.columnView,
                        onPlay = { request -> startActivity(PlayerActivity.createIntent(this, request)) },
                    )
                }
                val updateOffer by viewModel.updateOffer.collectAsStateWithLifecycle()
                updateOffer?.let { offer ->
                    UpdateDialog(
                        state = offer,
                        onInstall = viewModel::installUpdate,
                        onAllowInstall = { startActivity(viewModel.installPermissionIntent()) },
                        onDismiss = viewModel::dismissUpdate,
                    )
                }
            }
        }
    }
}
