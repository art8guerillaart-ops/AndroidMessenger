package com.example.messenger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightMessengerColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = White,
    background = BgPage,
    surface = White,
    onSurface = InkBlack,
    surfaceVariant = SidebarBg,
    onSurfaceVariant = MutedText,
    surfaceContainerHigh = CardBg,
    surfaceContainerHighest = FooterBg,
    outlineVariant = BorderSoft,
    error = ErrorRed
)

// Тот же визуальный язык, что и в светлой теме, просто фон и поверхности темнеют,
// а текст светлеет. Акцентные пузыри сообщений (см. ChatScreen.MessageBubble) в эту
// палитру не входят — они всегда используют InkBlack/PanelBg/White напрямую, а не
// токены темы, поэтому не должны меняться местами при переключении темы.
private val DarkMessengerColorScheme = darkColorScheme(
    primary = DarkInkText,
    onPrimary = InkBlack,
    background = DarkBgPage,
    surface = DarkSurface,
    onSurface = DarkInkText,
    surfaceVariant = DarkSidebarBg,
    onSurfaceVariant = DarkMutedText,
    surfaceContainerHigh = DarkCardBg,
    surfaceContainerHighest = DarkFooterBg,
    outlineVariant = DarkBorderSoft,
    error = ErrorRed
)

@Composable
fun MessengerTheme(isDarkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme) DarkMessengerColorScheme else LightMessengerColorScheme,
        typography = Typography,
        content = content
    )
}
