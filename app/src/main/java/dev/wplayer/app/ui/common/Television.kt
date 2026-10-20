package dev.wplayer.app.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Whether this is a TV, used with a remote's D-pad rather than touch. */
val Configuration.isTelevision: Boolean
    get() = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTelevision(): Boolean = LocalConfiguration.current.isTelevision
