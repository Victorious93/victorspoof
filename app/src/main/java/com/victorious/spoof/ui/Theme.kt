package com.victorious.spoof.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cyan = Color(0xFF22E6FF)
val Magenta = Color(0xFFFF3BD4)
val Charcoal = Color(0xFF14161B)

@Composable
fun VictorSpoofTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Cyan, secondary = Magenta, background = Charcoal, surface = Color(0xFF1E2128)),
    content = content,
)
