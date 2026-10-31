package dev.wplayer.app.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.VisualTransformation

/** Whether this is a TV, used with a remote's D-pad rather than touch. */
val Configuration.isTelevision: Boolean
    get() = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTelevision(): Boolean = LocalConfiguration.current.isTelevision

/**
 * Moves focus up or down on the D-pad's up and down keys. A single-line text
 * field keeps those keys for its cursor, so a remote could not leave the field.
 */
fun Modifier.leaveOnVerticalKeys(focusManager: FocusManager): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> return@onPreviewKeyEvent false
    }
    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
    true
}

/**
 * A text field used with a TV remote. An editable field opens the on-screen
 * keyboard as soon as it gets focus, and the keyboard then takes the D-pad, so
 * focus could never pass through a form. On a TV the field stays [readOnly]
 * until select is pressed on it, and again once focus leaves it; up and down
 * move focus out of it. Elsewhere it changes nothing.
 */
@Stable
class RemoteTextField internal constructor(
    private val television: Boolean,
    private val focusManager: FocusManager,
    private val keyboard: SoftwareKeyboardController?,
) {
    private var editing by mutableStateOf(false)

    /** For the field's `readOnly`. */
    val readOnly: Boolean get() = television && !editing

    /** For the field's modifier. */
    val modifier: Modifier = if (!television) {
        Modifier
    } else {
        Modifier
            .onFocusChanged { if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { event ->
                if (!editing && event.key in SELECT_KEYS) {
                    if (event.type == KeyEventType.KeyUp) {
                        editing = true
                        keyboard?.show()
                    }
                    true
                } else {
                    false
                }
            }
            .leaveOnVerticalKeys(focusManager)
    }
}

@Composable
fun rememberRemoteTextField(): RemoteTextField {
    val television = isTelevision()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(television, focusManager, keyboard) { RemoteTextField(television, focusManager, keyboard) }
}

/** A single-line [OutlinedTextField] that a TV remote can pass through (see [RemoteTextField]). */
@Composable
fun RemoteOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val remote = rememberRemoteTextField()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = remote.readOnly,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier.then(remote.modifier),
    )
}

private val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
