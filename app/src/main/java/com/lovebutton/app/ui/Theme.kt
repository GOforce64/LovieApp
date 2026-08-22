package com.lovebutton.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pink = Color(0xFFD81B60)
private val PalePink = Color(0xFFF8BBD0)

private val Scheme = lightColorScheme(
    primary = Pink,
    secondary = PalePink,
)

@Composable
fun LoveButtonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
