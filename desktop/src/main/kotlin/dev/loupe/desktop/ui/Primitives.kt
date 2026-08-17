package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.loupe.desktop.theme.LoupeTheme

/**
 * The two rules the layout is drawn with.
 *
 * Here rather than in the theme because they are composables, but they carry no logic: a hairline in
 * the divider colour, horizontal or vertical.
 */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(LoupeTheme.colors.border))
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxHeight().width(1.dp).background(LoupeTheme.colors.border))
}
