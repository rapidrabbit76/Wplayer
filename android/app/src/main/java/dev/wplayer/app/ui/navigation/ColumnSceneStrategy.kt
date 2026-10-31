package dev.wplayer.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/** Where a screen sits when shown as a column; null outside column view. */
data class ColumnRole(val isCurrent: Boolean)

/**
 * Lets a screen shown as a column drop its own chrome: only the current
 * (rightmost) column needs a back button and actions.
 */
val LocalColumnRole = staticCompositionLocalOf<ColumnRole?> { null }

/**
 * Finder-style columns for wide windows: the last folders opened, up to
 * [maxColumns], side by side so the parent folder stays visible. Applies when
 * at least two trailing entries are [column]s; otherwise the default
 * single-pane scene is used.
 */
class ColumnSceneStrategy<T : Any>(private val maxColumns: Int) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (maxColumns < 2) return null
        val columns = entries.takeLastWhile { it.metadata[COLUMN] == true }.takeLast(maxColumns)
        if (columns.size < 2) return null
        return ColumnScene(columns, previousEntries = entries.dropLast(1))
    }

    companion object {
        private const val COLUMN = "ColumnSceneStrategy.column"

        /** Marks a folder that can be shown as a column. */
        fun column(): Map<String, Any> = mapOf(COLUMN to true)
    }
}

/** A data class so its identity is stable across recompositions, as navigation transitions require. */
private data class ColumnScene<T : Any>(
    override val entries: List<NavEntry<T>>,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    // Stable while browsing so moving between folders swaps columns instead of animating the whole scene.
    override val key: Any = ColumnScene::class
    override val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize()) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) VerticalDivider()
                // The current (rightmost) folder gets a little more room for long file names.
                val isCurrent = index == entries.lastIndex
                Box(Modifier.weight(if (isCurrent) CURRENT_WEIGHT else 1f)) {
                    CompositionLocalProvider(LocalColumnRole provides ColumnRole(isCurrent)) { entry.Content() }
                }
            }
        }
    }
}

private const val CURRENT_WEIGHT = 1.25f
