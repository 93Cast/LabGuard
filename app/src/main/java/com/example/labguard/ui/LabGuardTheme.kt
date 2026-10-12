package com.example.labguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF102A43)
private val SteelBlue = Color(0xFF356A8A)
private val IceBlue = Color(0xFFEAF3F8)
private val Teal = Color(0xFF168A8A)
private val Background = Color(0xFFF5F8FA)
private val TextPrimary = Color(0xFF243746)
private val Border = Color(0xFFD8E3EA)

private val LabColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = SteelBlue,
    onSecondary = Color.White,
    tertiary = Teal,
    background = Background,
    onBackground = TextPrimary,
    surface = Color.White,
    onSurface = TextPrimary,
    surfaceVariant = IceBlue,
    outline = Border,
    error = Color(0xFFB42318)
)

@Composable
fun LabGuardTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LabColors,
        content = content
    )
}