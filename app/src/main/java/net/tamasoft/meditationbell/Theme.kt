package net.tamasoft.meditationbell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Brass = Color(0xFFB8893A)
private val Teal = Color(0xFF1F3A3D)

@Composable
fun BellTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFE2BE78), background = Color(0xFF121A1B), surface = Color(0xFF121A1B))
    } else {
        lightColorScheme(primary = Brass, secondary = Teal)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
