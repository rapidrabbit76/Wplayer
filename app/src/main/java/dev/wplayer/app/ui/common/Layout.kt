package dev.wplayer.app.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Centers a list at a readable width on wide screens instead of stretching rows edge to edge. */
fun Modifier.readableWidth(): Modifier = fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)
