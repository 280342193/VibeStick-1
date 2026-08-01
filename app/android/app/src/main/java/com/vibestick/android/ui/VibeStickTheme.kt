package com.vibestick.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val VibeBlue = Color(0xFF1D74E8)
val VibeGreen = Color(0xFF1F9D65)

private val LightColors = lightColorScheme(
    primary = VibeBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF49545C),
    onSecondary = Color.White,
    tertiary = VibeGreen,
    onTertiary = Color.White,
    error = Color(0xFFC43D3D),
    background = Color(0xFFF8FAFB),
    onBackground = Color(0xFF172026),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172026),
    surfaceVariant = Color(0xFFE9EEF1),
    onSurfaceVariant = Color(0xFF526068),
    outline = Color(0xFF9AA7AE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF70A9F4),
    onPrimary = Color(0xFF08294D),
    secondary = Color(0xFFBBC5CB),
    onSecondary = Color(0xFF263137),
    tertiary = Color(0xFF62CCA0),
    onTertiary = Color(0xFF073824),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF15191C),
    onBackground = Color(0xFFE7EAEC),
    surface = Color(0xFF1B2023),
    onSurface = Color(0xFFE7EAEC),
    surfaceVariant = Color(0xFF30373B),
    onSurfaceVariant = Color(0xFFC1C9CD),
    outline = Color(0xFF899399),
)

private val VibeTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
)

private val VibeShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun VibeStickTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VibeTypography,
        shapes = VibeShapes,
        content = content,
    )
}
