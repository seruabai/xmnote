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

// 小米笔记风主题：米黄纸底 + 白卡 + 米黄点缀，层级靠留白与暖灰分隔。
// 色值取自 MIUI 笔记（手机版 2.3.2.7）反编译资源 res/values/colors.xml：
//   paper_yellow = #fffaf0                              —— 米黄纸（笔记纸色 → 本主题的全屏底色）
//   paper_white  = #ffffff                              —— 白纸卡片
//   miuix_color_yellow_light_primary_default = #ffb21d  —— 黄色主题的按钮/强调色
//   link_card_bg_color_yellow_start = #fff0d3、miuix_color_yellow_solid_10 = #fff5ea —— 浅黄容器
//   黄色主题正文色 = #9d6802                            —— 次要文字/已完成（暖棕）
private val MiuiYellow = Color(0xFFFFB21D)
private val MiuiYellowDeep = Color(0xFF9D6802)
private val MiuiYellowNight = Color(0xFFFFBF0F)
private val PaperYellow = Color(0xFFFFFAF0)
private val PaperWhite = Color(0xFFFFFFFF)
private val Ink = Color(0xFF33291A)
private val SecondaryInk = Color(0xFF8A7A5E)
private val Separator = Color(0xFFD9CDB4)
private val OpaqueSeparator = Color(0xFFEEE5D2)

private val NightInk = Color(0xFFF0E8DA)
private val NightSecondaryInk = Color(0xFFA79B84)
private val NightSeparator = Color(0xFF4C4433)

private val LightColors = lightColorScheme(
    primary = MiuiYellow,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF0D3),    // link_card_bg_color_yellow_start
    onPrimaryContainer = Color(0xFF6B4700),
    secondary = MiuiYellowDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF5EA),  // miuix_color_yellow_solid_10
    onSecondaryContainer = Color(0xFF6B4700),
    tertiary = Color(0xFF4D8A5D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDAF4DA),
    onTertiaryContainer = Color(0xFF2C4A33),
    background = PaperYellow,                // paper_yellow：米黄纸底
    onBackground = Ink,
    surface = PaperWhite,                    // paper_white：白卡
    onSurface = Ink,
    surfaceVariant = Color(0xFFF7EFDF),
    onSurfaceVariant = SecondaryInk,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFDF7),
    surfaceContainer = Color(0xFFFDF6E7),
    surfaceContainerHigh = Color(0xFFF6ECDA),
    surfaceContainerHighest = Color(0xFFEFE3CC),
    outline = Separator,
    outlineVariant = OpaqueSeparator,
    error = Color(0xFFD94F38),               // MIUI 笔记红主题色
    onError = Color.White,
    errorContainer = Color(0xFFFFE2DC),
    onErrorContainer = Color(0xFF5C1A10),
)

private val DarkColors = darkColorScheme(
    primary = MiuiYellowNight,               // miuix_color_yellow_dark_level1
    onPrimary = Color(0xFF3A2A00),
    primaryContainer = Color(0xFF4A3A12),
    onPrimaryContainer = Color(0xFFFFE9A8),
    secondary = Color(0xFFD8C6A2),
    onSecondary = Color(0xFF33280E),
    secondaryContainer = Color(0xFF3A3222),
    onSecondaryContainer = Color(0xFFF0E3C8),
    tertiary = Color(0xFF9CC79F),
    onTertiary = Color(0xFF10321C),
    tertiaryContainer = Color(0xFF2A4A32),
    onTertiaryContainer = Color(0xFFBEE8C2),
    background = Color(0xFF17140E),          // 暖黑：米黄纸的深色收口
    onBackground = NightInk,
    surface = Color(0xFF211D15),
    onSurface = NightInk,
    surfaceVariant = Color(0xFF2C271C),
    onSurfaceVariant = NightSecondaryInk,
    surfaceContainerLowest = Color(0xFF100E09),
    surfaceContainerLow = Color(0xFF1B1811),
    surfaceContainer = Color(0xFF211D15),
    surfaceContainerHigh = Color(0xFF2C271C),
    surfaceContainerHighest = Color(0xFF373023),
    outline = NightSeparator,
    outlineVariant = Color(0xFF332E23),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF4A1008),
    errorContainer = Color(0xFF3A1712),
    onErrorContainer = Color(0xFFFFDAD5),
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
