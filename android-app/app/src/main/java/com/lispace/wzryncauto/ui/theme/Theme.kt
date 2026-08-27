package com.lispace.wzryncauto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val IceLightColors = lightColorScheme(
    primary = Color(0xFF2F8FDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF3FF),
    onPrimaryContainer = Color(0xFF173B57),
    secondary = Color(0xFF55B9E8),
    onSecondary = Color(0xFF082F47),
    secondaryContainer = Color(0xFFE2F5FF),
    onSecondaryContainer = Color(0xFF173B57),
    tertiary = Color(0xFF6AA6C7),
    background = Color(0xFFF7FCFF),
    onBackground = Color(0xFF173B57),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF173B57),
    surfaceVariant = Color(0xFFEAF7FC),
    onSurfaceVariant = Color(0xFF53728A),
    outline = Color(0xFFB7D8E8),
    outlineVariant = Color(0xFFD4EAF4),
)

private val IceDarkColors = darkColorScheme(
    primary = Color(0xFF86D5FF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF174F70),
    onPrimaryContainer = Color(0xFFDDF3FF),
    secondary = Color(0xFF6FC9EE),
    secondaryContainer = Color(0xFF244C61),
    background = Color(0xFF0B1E2B),
    surface = Color(0xFF102735),
    surfaceVariant = Color(0xFF1A3748),
)

private val IceTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun WzryFarmTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) IceDarkColors else IceLightColors,
        typography = IceTypography,
        content = content,
    )
}
