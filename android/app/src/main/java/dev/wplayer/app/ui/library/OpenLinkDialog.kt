package dev.wplayer.app.ui.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.wplayer.app.R
import dev.wplayer.app.data.link.firstWebUrl
import dev.wplayer.app.ui.common.RemoteOutlinedTextField

/** Asks for a web address to play or keep in a playlist; starts with the clipboard's, when it holds one. */
@Composable
fun OpenLinkDialog(onPlay: (String) -> Unit, onAddToPlaylist: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(clipboardLink(context).orEmpty()) }
    val url = firstWebUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_open)) },
        text = {
            RemoteOutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.link_address),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { url?.let(onPlay) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { url?.let(onPlay) }, enabled = url != null) { Text(stringResource(R.string.link_play)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { url?.let(onAddToPlaylist) }, enabled = url != null) { Text(stringResource(R.string.playlist_add_title)) }
            }
        },
    )
}

/** The web address on the clipboard, if it holds one. */
private fun clipboardLink(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.let(::firstWebUrl)
}
