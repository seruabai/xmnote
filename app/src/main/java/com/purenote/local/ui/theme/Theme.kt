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

// 奶油暖黄 + 奶油纸主题：柔和暖黄强调色、温暖奶油纸背景，营造像笔记本的安心感。
// 不用莫兰迪（偏灰）、不用强冷蓝、不用高饱和刺眼黄。
private val PaperBg = Color(0xFFFAF6EE)          // 暖奶油纸背景
private val PaperSurface = Color(0xFFFFFDF7)     // 暖白纸卡面
private val PaperVariant = Color(0xFFF2EDE0)     // 暖纸浅变体
private val Ink = Color(0xFF3A2E20)              // 暖墨棕文字（柔和）
private val InkSoft = Color(0xFF8C7E68)          // 暖灰文字
private val CreamYellow = Color(0xFFE3A33E)      // 奶油暖黄（主）
private val CreamYellowSoft = Color(0xFFEDB458)  // 奶油暖黄（亮/深色用）
private val WarmBrown = Color(0xFF8A6D3B)        // 暖棕（次要）
private val WarmTerra = Color(0xFFB6785A)        // 暖陶土（第三）

private val NightBg = Color(0xFF211B14)          // 暖深棕咖背景
private val NightSurface = Color(0xFF2B251C)     // 暖奶咖卡面
private val NightVariant = Color(0xFF37301F)     // 暖深浅变体
private val NightText = Color(0xFFF2E9D8)        // 暖米白文字
private val NightInkSoft = Color(0xFFABA08C)     // 暖灰文字

private val LightColors = lightColorScheme(
    primary = CreamYellow,
    onPrimary = Color(0xFF33260B),
    primaryContainer = Color(0xFFFCEBC9),
    onPrimaryContainer = Color(0xFF3A2C0E),
    secondary = WarmBrown,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E4C6),
    onSecondaryContainer = Color(0xFF2E2410),
    tertiary = WarmTerra,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7E1D4),
    onTertiaryContainer = Color(0xFF3A2113),
    background = PaperBg,
    onBackground = Ink,
    surface = PaperSurface,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color(0xFFFFFDFA),
    surfaceContainerLow = Color(0xFFFFFCF2),
    surfaceContainer = Color(0xFFFAF3E4),
    surfaceContainerHigh = Color(0xFFF2ECDB),
    surfaceContainerHighest = Color(0xFFE9E2CF),
    outline = Color(0xFFB5A98D),
    outlineVariant = Color(0xFFDCD2BC),
    error = Color(0xFFBA3B30),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
)

private val DarkColors = darkColorScheme(
    primary = CreamYellowSoft,
    onPrimary = Color(0xFF3A2C0E),
    primaryContainer = Color(0xFF57401A),
    onPrimaryContainer = Color(0xFFFFE7B8),
    secondary = Color(0xFFDCBD8A),
    onSecondary = Color(0xFF2C1F08),
    secondaryContainer = Color(0xFF463512),
    onSecondaryContainer = Color(0xFFF3E0C8),
    tertiary = Color(0xFFDFB89C),
    onTertiary = Color(0xFF3A1B0C),
    tertiaryContainer = Color(0xFF5E3720),
    onTertiaryContainer = Color(0xFFF7E1D4),
    background = NightBg,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = Color(0xFF1B1610),
    surfaceContainerLow = Color(0xFF232019),
    surfaceContainer = Color(0xFF2B2520),
    surfaceContainerHigh = Color(0xFF37302A),
    surfaceContainerHighest = Color(0xFF454030),
    outline = Color(0xFF8A7E6A),
    outlineVariant = Color(0xFF4A422F),
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
