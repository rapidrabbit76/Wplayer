package dev.wplayer.app.ui.common

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/** The whole row is the toggle, so a remote stops on it once; the switch only shows the state. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            // A switch without its own handler drops the touch-target size, which would shift it in taller rows.
            Switch(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
        },
        colors = colors,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}
