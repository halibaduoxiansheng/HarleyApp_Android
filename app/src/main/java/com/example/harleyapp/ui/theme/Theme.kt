package com.example.harleyapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.harleyapp.model.AppVisualTheme

/** 当前页面可读取的角色风格主题，用于功能卡片展示离线人物图。 */
val LocalAppVisualTheme = staticCompositionLocalOf { AppVisualTheme.CLASSIC_BLUE }

private val HarleyDarkColorScheme = darkColorScheme(
    primary = HarleyBlueDark,
    secondary = HarleyTealDark,
    tertiary = HarleyOrangeDark,
    background = HarleyDarkBackground,
    surface = HarleyDarkSurface
)

private val HarleyLightColorScheme = lightColorScheme(
    primary = HarleyBlue,
    onPrimary = Color.White,
    primaryContainer = HarleyBlueLight,
    onPrimaryContainer = Color(0xFF0D245F),
    secondary = HarleyTeal,
    secondaryContainer = HarleyTealLight,
    tertiary = HarleyOrange,
    tertiaryContainer = HarleyOrangeLight,
    background = HarleyBackground,
    surface = HarleySurface,
    onBackground = HarleyText,
    onSurface = HarleyText
)

private val SakuraDarkColorScheme = darkColorScheme(
    primary = SakuraPinkDark,
    secondary = SakuraPurpleDark,
    tertiary = SakuraOrangeDark,
    background = SakuraDarkBackground,
    surface = SakuraDarkSurface
)

private val SakuraLightColorScheme = lightColorScheme(
    primary = SakuraPink,
    onPrimary = Color.White,
    primaryContainer = SakuraPinkLight,
    onPrimaryContainer = Color(0xFF4A002B),
    secondary = SakuraPurple,
    secondaryContainer = SakuraPurpleLight,
    tertiary = SakuraOrange,
    tertiaryContainer = SakuraOrangeLight,
    background = SakuraBackground,
    surface = Color.White,
    onBackground = SakuraText,
    onSurface = SakuraText
)

private val StarryDarkColorScheme = darkColorScheme(
    primary = StarryIndigoDark,
    secondary = StarryCyanDark,
    tertiary = StarryGoldDark,
    background = StarryDarkBackground,
    surface = StarryDarkSurface
)

private val StarryLightColorScheme = lightColorScheme(
    primary = StarryIndigo,
    onPrimary = Color.White,
    primaryContainer = StarryIndigoLight,
    onPrimaryContainer = Color(0xFF11195A),
    secondary = StarryCyan,
    secondaryContainer = StarryCyanLight,
    tertiary = StarryGold,
    tertiaryContainer = StarryGoldLight,
    background = StarryBackground,
    surface = Color.White,
    onBackground = StarryText,
    onSurface = StarryText
)

private val MintDarkColorScheme = darkColorScheme(
    primary = MintGreenDark,
    secondary = MintOrangeDark,
    tertiary = MintPurpleDark,
    background = MintDarkBackground,
    surface = MintDarkSurface
)

private val MintLightColorScheme = lightColorScheme(
    primary = MintGreen,
    onPrimary = Color.White,
    primaryContainer = MintGreenLight,
    onPrimaryContainer = Color(0xFF00201C),
    secondary = MintOrange,
    secondaryContainer = MintOrangeLight,
    tertiary = MintPurple,
    tertiaryContainer = MintPurpleLight,
    background = MintBackground,
    surface = Color.White,
    onBackground = MintText,
    onSurface = MintText
)

/**
 * 为Harley生活助手提供统一的Material 3颜色和字体主题。
 *
 * 使用方法：
 * 在Activity的setContent中使用HarleyAppTheme包裹全部页面内容。
 * 默认跟随系统深色模式；可选择启用Android 12及以上的动态取色。
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统。
 * @param visualTheme 用户选择的角色风格主题，默认使用经典蓝。
 * @param dynamicColor 是否允许使用系统壁纸动态颜色，默认启用。
 * @param content 需要应用主题的Compose页面内容。
 *
 * @return 无返回值，直接输出应用主题后的页面内容。
 */
@Composable
fun HarleyAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    visualTheme: AppVisualTheme = AppVisualTheme.CLASSIC_BLUE,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }

        visualTheme in SAKURA_VISUAL_THEMES && darkTheme -> SakuraDarkColorScheme
        visualTheme in SAKURA_VISUAL_THEMES -> SakuraLightColorScheme
        visualTheme in STARRY_VISUAL_THEMES && darkTheme -> StarryDarkColorScheme
        visualTheme in STARRY_VISUAL_THEMES -> StarryLightColorScheme
        visualTheme in MINT_VISUAL_THEMES && darkTheme -> MintDarkColorScheme
        visualTheme in MINT_VISUAL_THEMES -> MintLightColorScheme
        darkTheme -> HarleyDarkColorScheme
        else -> HarleyLightColorScheme
    }

    CompositionLocalProvider(LocalAppVisualTheme provides visualTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HarleyTypography,
            content = content
        )
    }
}

/** 共用樱粉配色、但使用不同离线人物图的主题组。 */
private val SAKURA_VISUAL_THEMES = setOf(
    AppVisualTheme.SAKURA_GIRL,
    AppVisualTheme.PEACH_TWIN_TAIL,
    AppVisualTheme.ROSE_PRINCESS,
    AppVisualTheme.AMBER_SHORT_HAIR
)

/** 共用星海配色、偏冷色或夜间氛围的人物主题组。 */
private val STARRY_VISUAL_THEMES = setOf(
    AppVisualTheme.STARRY_TRAVELER,
    AppVisualTheme.NIGHT_MUSIC,
    AppVisualTheme.CORAL_HOODIE
)

/** 共用薄荷配色、偏清爽和晴空氛围的人物主题组。 */
private val MINT_VISUAL_THEMES = setOf(
    AppVisualTheme.MINT_CAT,
    AppVisualTheme.CYAN_SHORT_HAIR,
    AppVisualTheme.SKY_ADVENTURER
)
