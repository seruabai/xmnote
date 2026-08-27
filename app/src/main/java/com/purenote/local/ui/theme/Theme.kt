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

// 小米笔记浅色界面：中性灰背景、纯白卡片和标志性黄色强调色。
private val PaperBg = Color(0xFFF7F7F7)
private val PaperSurface = Color(0xFFFFFFFF)
private val PaperVariant = Color(0xFFEDEDED)
private val Ink = Color(0xFF111111)
private val InkSoft = Color(0xFF8F8F8F)
private val MiYellow = Color(0xFFFFB800)
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
    secondary = Color(0xFFB57D00),
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
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE7E7E7),
    outline = Color(0xFFAAAAAA),
    outlineVariant = Color(0xFFD8D8D8),
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
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal),
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
