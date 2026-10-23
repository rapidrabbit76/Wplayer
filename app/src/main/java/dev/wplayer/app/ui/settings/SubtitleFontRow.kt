package dev.wplayer.app.ui.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.wplayer.app.R
import dev.wplayer.app.data.model.SubtitleFont

/** The fonts the user added, and whether adding the last one failed. */
data class SubtitleFontsState(val added: List<SubtitleFont> = emptyList(), val addFailed: Boolean = false)

/** Adding and deleting subtitle font files; choosing one is a settings update. */
interface SubtitleFontActions {
    fun add(uri: Uri)
    fun delete(font: SubtitleFont)
    /** Forgets that adding a font failed, once the user has seen it. */
    fun dismissFailure()
}

/**
 * The subtitle font: the system's or one of the font files the user added.
 * Files come from the system's file picker here; TVs often have none, so a
 * font file's menu in the browser adds it as well.
 */
@Composable
internal fun SubtitleFontRow(
    selected: SubtitleFont?,
    fonts: SubtitleFontsState,
    actions: SubtitleFontActions,
    onSelect: (SubtitleFont?) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_subtitle_font)) },
        trailingContent = {
            Text(
                text = selected?.family ?: stringResource(R.string.settings_subtitle_font_default),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.clickable { choosing = true },
    )
    if (choosing) {
        SubtitleFontDialog(
            selected = selected,
            fonts = fonts,
            actions = actions,
            onSelect = onSelect,
            onDismiss = {
                choosing = false
                actions.dismissFailure()
            },
        )
    }
}

@Composable
private fun SubtitleFontDialog(
    selected: SubtitleFont?,
    fonts: SubtitleFontsState,
    actions: SubtitleFontActions,
    onSelect: (SubtitleFont?) -> Unit,
    onDismiss: () -> Unit,
) {
    var noPicker by remember { mutableStateOf(false) }
    // Font files rarely carry a font MIME type, so any file can be picked; adding checks it is a font.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(actions::add) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_subtitle_font)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FontOption(stringResource(R.string.settings_subtitle_font_default), selected == null, onClick = { onSelect(null) })
                fonts.added.forEach { font ->
                    FontOption(font.family, font == selected, onClick = { onSelect(font) }) {
                        IconButton(onClick = { actions.delete(font) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
                val note = when {
                    fonts.addFailed -> R.string.subtitle_font_invalid
                    noPicker -> R.string.settings_subtitle_font_no_picker
                    else -> R.string.settings_subtitle_font_hint
                }
                Text(
                    text = stringResource(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fonts.addFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    actions.dismissFailure()
                    try {
                        picker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        noPicker = true
                    }
                },
            ) { Text(stringResource(R.string.settings_subtitle_font_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

@Composable
private fun FontOption(label: String, selected: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}
