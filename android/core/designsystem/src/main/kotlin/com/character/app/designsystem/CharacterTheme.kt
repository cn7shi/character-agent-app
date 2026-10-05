package com.character.app.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Night = darkColorScheme(primary = Color(0xFFE3C393), secondary = Color(0xFFB5C9B6),
    background = Color(0xFF121315), surface = Color(0xFF1C1D20), surfaceVariant = Color(0xFF2A2B30))
private val Day = lightColorScheme(primary = Color(0xFF725630), secondary = Color(0xFF49644D),
    background = Color(0xFFF8F5EF), surface = Color(0xFFFFFBF5))

/** Theme changes only presentation; the same repository and screen state remain alive. */
@Composable
fun CharacterTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Night else Day, content = content)
}
