package com.example.messenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.messenger.R

/*
 * В веб-версии заголовки/кнопки/лейблы используют декоративный геометричный шрифт
 * Michroma (Google Fonts) в верхнем регистре с letter-spacing.
 *
 * Шрифт michroma.ttf уже подключен ниже через R.font.michroma.
 */
// val DisplayFontFamily: FontFamily = FontFamily.Default
val DisplayFontFamily: FontFamily = FontFamily(Font(R.font.michroma))

val BodyFontFamily: FontFamily = FontFamily.Default // системный (как -apple-system в вебе)

val displayLabelStyle = TextStyle(
    fontFamily = DisplayFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    letterSpacing = 1.sp
)

val Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp,
        textAlign = TextAlign.Center
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = displayLabelStyle,
    bodyLarge = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    )
)