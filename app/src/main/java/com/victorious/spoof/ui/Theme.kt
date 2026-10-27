package com.victorious.spoof.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.victorious.spoof.theme.FleetThemeClient
import com.victorious.spoof.theme.FleetThemeState

val Cyan = Color(0xFF22E6FF)
val Magenta = Color(0xFFFF3BD4)
val Charcoal = Color(0xFF14161B)

private val BuiltInScheme = darkColorScheme(primary = Cyan, secondary = Magenta, background = Charcoal, surface = Color(0xFF1E2128))

@Composable
fun VictorSpoofTheme(content: @Composable () -> Unit) {
    val state by FleetThemeClient.state.collectAsState()
    val scheme = when (val s = state) {
        is FleetThemeState.BuiltIn -> BuiltInScheme
        is FleetThemeState.Fleet -> {
            val p = s.snapshot.palette
            val base = if (s.snapshot.mode == "LIGHT") lightColorScheme() else darkColorScheme()
            base.copy(
                primary = Color(p.primary), secondary = Color(p.secondary), tertiary = Color(p.stale),
                background = Color(p.background), surface = Color(p.surface), surfaceVariant = Color(p.tile),
                onBackground = Color(p.onBackground), onSurface = Color(p.onBackground),
                onSurfaceVariant = Color(p.muted), outline = Color(p.muted), error = Color(p.danger),
            )
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
