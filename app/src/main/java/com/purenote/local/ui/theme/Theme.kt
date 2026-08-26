package com.purenote.local.ui.theme

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
import com.purenote.local.ThemeMode

// 小米笔记风格：暖纸底 + 标志性黄
private val PaperBg = Color(0xFFF7F5F0)
private val PaperSurface = Color(0xFFFFFFFF)
private val PaperVariant = Color(0xFFF0EDE5)
private val Ink = Color(0xFF201D17)
private val InkSoft = Color(0xFF8A8577)
private val MiYellow = Color(0xFFF7B500)
private val MiYellowDark = Color(0xFFFFD350)

private val NightBg = Color(0xFF141310)
private val NightSurface = Color(0xFF211F1A)
private val NightVariant = Color(0xFF2D2A23)
private val NightText = Color(0xFFECE8DE)
private val NightInkSoft = Color(0xFFABA495)

private val LightColors = lightColorScheme(
    primary = MiYellow,
    onPrimary = Color(0xFF221A00),
    primaryContainer = Color(0xFFFFEFC2),
    onPrimaryContainer = Color(0xFF221A00),
    secondary = Color(0xFF7C6427),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E9CF),
    onSecondaryContainer = Color(0xFF2B230C),
    tertiary = Color(0xFF55795B),
    background = PaperBg,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF9F5),
    surfaceContainer = Color(0xFFF5F2EB),
    surfaceContainerHigh = Color(0xFFEFEBE2),
    surfaceContainerHighest = Color(0xFFEAE5DB),
    outline = Color(0xFFB4AD9C),
    outlineVariant = Color(0xFFE8E3D7),
    error = Color(0xFFBA3B30),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
)

private val DarkColors = darkColorScheme(
    primary = MiYellowDark,
    onPrimary = Color(0xFF221A00),
    primaryContainer = Color(0xFF4A3B00),
    onPrimaryContainer = Color(0xFFFFE08C),
    secondary = Color(0xFFD6C08A),
    onSecondary = Color(0xFF241C00),
    secondaryContainer = Color(0xFF3A3220),
    onSecondaryContainer = Color(0xFFEFE0BC),
    tertiary = Color(0xFFA5C6A5),
    background = NightBg,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = Color(0xFF1A1814),
    surfaceContainerLow = Color(0xFF1E1C18),
    surfaceContainer = Color(0xFF232019),
    surfaceContainerHigh = Color(0xFF2E2B23),
    surfaceContainerHighest = Color(0xFF39362D),
    outline = Color(0xFF756F60),
    outlineVariant = Color(0xFF37342B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A14),
    onErrorContainer = Color(0xFFFFDAD4),
)

private val MiTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
)

private val MiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PureNoteTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MiTypography,
        shapes = MiShapes,
        content = content,
    )
}
