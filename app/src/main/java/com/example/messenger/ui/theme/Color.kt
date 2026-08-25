package com.example.messenger.ui.theme

import androidx.compose.ui.graphics.Color

// Один в один с :root { ... } из index.html веб-версии
val BgPage = Color(0xFFDDDDDD)
val CardBg = Color(0xFFE6E6E6)
val White = Color(0xFFFFFFFF)
val InkBlack = Color(0xFF1D1D1C)
val SidebarBg = Color(0xFFD9D9D9)
val PanelBg = Color(0xFFF4F4F4)   // фон входящего пузыря сообщения — не зависит от темы, см. Theme.kt
val FooterBg = Color(0xFFF5F5F5)
val MutedText = Color(0xFF9D9D9D)
val BorderSoft = Color(0x14000000) // rgba(0,0,0,0.08)
val ErrorRed = Color(0xFFB23B3B)

// Тёмная тема: фон и поверхности темнеют, текст светлеет — тот же визуальный язык.
// Пузыри сообщений (InkBlack/PanelBg/White выше) в тёмную палитру не входят: см.
// MessageBubble в ChatScreen.kt, где они используются напрямую, а не через MaterialTheme.
val DarkBgPage = Color(0xFF121212)
val DarkCardBg = Color(0xFF1C1C1E)
val DarkSurface = Color(0xFF1E1E1E)
val DarkInkText = Color(0xFFECECEC)
val DarkSidebarBg = Color(0xFF161616)
val DarkFooterBg = Color(0xFF1A1A1A)
val DarkMutedText = Color(0xFFABABAB)
val DarkBorderSoft = Color(0x1FFFFFFF) // rgba(255,255,255,0.12)
