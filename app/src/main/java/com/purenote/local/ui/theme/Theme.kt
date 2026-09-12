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

// iOS 系统风主题：中性灰白分层、系统蓝点缀、层级靠字重与留白表达。
// 色值对齐 Apple HIG：浅色 grouped 背景 #F2F2F7 + 白卡，深色纯黑 + #1C1C1E 卡。
private val IosBlue = Color(0xFF007AFF)           // systemBlue（浅色主色）
private val IosBlueDark = Color(0xFF0A84FF)       // systemBlue（深色主色）
private val IosGreen = Color(0xFF34C759)          // systemGreen（第三色，克制使用）
private val IosRed = Color(0xFFFF3B30)            // systemRed
private val Label = Color(0xFF000000)             // label
private val SecondaryLabel = Color(0xFF8E8E93)    // secondaryLabel ≈ systemGray
private val Separator = Color(0xFFC7C7CC)         // separator
private val OpaqueSeparator = Color(0xFFE5E5EA)   // opaqueSeparator

private val NightLabel = Color(0xFFFFFFFF)
private val NightSecondaryLabel = Color(0xFF98989F)
private val NightSeparator = Color(0xFF3A3A3C)

private val LightColors = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EFFF),
    onPrimaryContainer = Color(0xFF0A4A82),
    secondary = SecondaryLabel,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = IosGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F4E1),
    onTertiaryContainer = Color(0xFF0A4F28),
    background = Color(0xFFF2F2F7),          // systemGroupedBackground
    onBackground = Label,
    surface = Color.White,                   // secondarySystemGroupedBackground（卡面/栏面）
    onSurface = Label,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = SecondaryLabel,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE9E9EE),
    surfaceContainerHighest = Color(0xFFE0E0E5),
    outline = Separator,
    outlineVariant = OpaqueSeparator,
    error = IosRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE2E0),
    onErrorContainer = Color(0xFF5C0F0B),
)

private val DarkColors = darkColorScheme(
    primary = IosBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF103F6E),
    onPrimaryContainer = Color(0xFF9ECDFF),
    secondary = NightSecondaryLabel,
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFF30D158),
    onTertiary = Color(0xFF003914),
    tertiaryContainer = Color(0xFF0E4A22),
    onTertiaryContainer = Color(0xFFB7F0C6),
    background = Color.Black,                // systemGroupedBackground（深色）
    onBackground = NightLabel,
    surface = Color(0xFF1C1C1E),             // secondarySystemGroupedBackground（深色）
    onSurface = NightLabel,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = NightSecondaryLabel,
    surfaceContainerLowest = Color(0xFF0F0F10),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = NightSeparator,
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFFF453A),
    onError = Color(0xFF4A0002),
    errorContainer = Color(0xFF3A0E0C),
    onErrorContainer = Color(0xFFFFD9D7),
)

// SF 风字阶：Large Title 34 Bold / Title2 22 Bold / Headline 17 Semibold / Body 17 /
// Subheadline 15 / Footnote 13 / Caption 12-11，层级靠字重而非颜色。
private val IosTypography = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
)

private val IosShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
        typography = IosTypography,
        shapes = IosShapes,
        content = content,
    )
}
