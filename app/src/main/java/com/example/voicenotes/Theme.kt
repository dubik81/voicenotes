package com.example.voicenotes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Палитра приложения. Акцент (зелёный/красный) задаётся по зоне отдельно. */
object Palette {
    val Ink = Color(0xFF241B3A)        // глубокий сине-фиолетовый «чернила»
    val InkSoft = Color(0xFF3A2F55)
    val Green = Color(0xFF2E9E6B)
    val Red = Color(0xFFD8574B)
    val Amber = Color(0xFFE0A458)

    // Светлая
    val LightBg = Color(0xFFF6F4EF)     // тёплая бумага
    val LightCard = Color(0xFFFFFFFF)
    val LightText = Color(0xFF241B3A)
    val LightMuted = Color(0xFF8A8497)

    // Тёмная
    val DarkBg = Color(0xFF16121F)
    val DarkCard = Color(0xFF221C30)
    val DarkText = Color(0xFFF1EEF6)
    val DarkMuted = Color(0xFF9A93A8)
}

private val LightScheme = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Color.White,
    background = Palette.LightBg,
    onBackground = Palette.LightText,
    surface = Palette.LightCard,
    onSurface = Palette.LightText,
    surfaceVariant = Palette.LightBg,
    onSurfaceVariant = Palette.LightMuted
)

private val DarkScheme = darkColorScheme(
    primary = Palette.DarkText,
    onPrimary = Palette.Ink,
    background = Palette.DarkBg,
    onBackground = Palette.DarkText,
    surface = Palette.DarkCard,
    onSurface = Palette.DarkText,
    surfaceVariant = Palette.DarkCard,
    onSurfaceVariant = Palette.DarkMuted
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
