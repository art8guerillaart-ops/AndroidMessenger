package com.example.messenger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val MessengerColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = White,
    background = BgPage,
    surface = White,
    onSurface = InkBlack,
    surfaceVariant = PanelBg,
    onSurfaceVariant = MutedText,
    error = ErrorRed
)

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MessengerColorScheme,
        typography = Typography,
        content = content
    )
}
