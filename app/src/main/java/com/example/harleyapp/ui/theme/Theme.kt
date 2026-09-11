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

/** 当前页面可读取的角色风格主题，用于页头、导航选中态和主题预览等少量品牌点缀。 */
val LocalAppVisualTheme = staticCompositionLocalOf { AppVisualTheme.CLASSIC_BLUE }

private val HarleyDarkColorScheme = darkColorScheme(
    primary = HarleyBlueDark,
    onPrimary = HarleyBlueDarkOn,
    primaryContainer = HarleyBlueDarkContainer,
    onPrimaryContainer = HarleyBlueDarkContainerText,
    secondary = HarleyTealDark,
    onSecondary = HarleyTealDarkOn,
    secondaryContainer = HarleyTealDarkContainer,
    onSecondaryContainer = HarleyTealDarkContainerText,
    tertiary = HarleyOrangeDark,
    onTertiary = HarleyOrangeDarkOn,
    tertiaryContainer = HarleyOrangeDarkContainer,
    onTertiaryContainer = HarleyOrangeDarkContainerText,
    background = HarleyDarkBackground,
    onBackground = HarleyDarkText,
    surface = HarleyDarkSurface,
    onSurface = HarleyDarkText,
    surfaceVariant = HarleyDarkSurfaceContainer,
    onSurfaceVariant = HarleyDarkMutedText,
    surfaceTint = HarleyBlueDark,
    inverseSurface = HarleyDarkText,
    inverseOnSurface = HarleyDarkSurface,
    inversePrimary = HarleyBlue,
    outline = HarleyDarkOutline,
    outlineVariant = HarleyDarkOutlineVariant,
    error = HarleyErrorDark,
    onError = HarleyOnErrorDark,
    errorContainer = HarleyErrorContainerDark,
    onErrorContainer = HarleyOnErrorContainerDark,
    scrim = Color.Black,
    surfaceDim = HarleyDarkBackground,
    surfaceBright = HarleyDarkSurfaceHighest,
    surfaceContainerLowest = HarleyDarkBackground,
    surfaceContainerLow = HarleyDarkSurfaceLow,
    surfaceContainer = HarleyDarkSurfaceContainer,
    surfaceContainerHigh = HarleyDarkSurfaceHigh,
    surfaceContainerHighest = HarleyDarkSurfaceHighest
)

private val HarleyLightColorScheme = lightColorScheme(
    primary = HarleyBlue,
    onPrimary = Color.White,
    primaryContainer = HarleyBlueLight,
    onPrimaryContainer = HarleyBlueContainerText,
    secondary = HarleyTeal,
    onSecondary = Color.White,
    secondaryContainer = HarleyTealLight,
    onSecondaryContainer = HarleyTealContainerText,
    tertiary = HarleyOrange,
    onTertiary = Color.White,
    tertiaryContainer = HarleyOrangeLight,
    onTertiaryContainer = HarleyOrangeContainerText,
    background = HarleyBackground,
    onBackground = HarleyText,
    surface = HarleySurface,
    onSurface = HarleyText,
    surfaceVariant = HarleySurfaceContainer,
    onSurfaceVariant = HarleyMutedText,
    surfaceTint = HarleyBlue,
    inverseSurface = HarleyDarkSurfaceHighest,
    inverseOnSurface = HarleyDarkText,
    inversePrimary = HarleyBlueDark,
    outline = HarleyOutline,
    outlineVariant = HarleyOutlineVariant,
    error = HarleyError,
    onError = HarleyOnError,
    errorContainer = HarleyErrorContainer,
    onErrorContainer = HarleyOnErrorContainer,
    scrim = Color.Black,
    surfaceDim = HarleySurfaceHigh,
    surfaceBright = HarleySurface,
    surfaceContainerLowest = HarleySurface,
    surfaceContainerLow = HarleySurfaceLow,
    surfaceContainer = HarleySurfaceContainer,
    surfaceContainerHigh = HarleySurfaceHigh,
    surfaceContainerHighest = HarleySurfaceHighest
)

private val SakuraDarkColorScheme = darkColorScheme(
    primary = SakuraPinkDark,
    onPrimary = SakuraPinkDarkOn,
    primaryContainer = SakuraPinkDarkContainer,
    onPrimaryContainer = SakuraPinkDarkContainerText,
    secondary = SakuraPurpleDark,
    onSecondary = SakuraPurpleDarkOn,
    secondaryContainer = SakuraPurpleDarkContainer,
    onSecondaryContainer = SakuraPurpleDarkContainerText,
    tertiary = SakuraOrangeDark,
    onTertiary = SakuraOrangeDarkOn,
    tertiaryContainer = SakuraOrangeDarkContainer,
    onTertiaryContainer = SakuraOrangeDarkContainerText,
    background = SakuraDarkBackground,
    onBackground = SakuraDarkText,
    surface = SakuraDarkSurface,
    onSurface = SakuraDarkText,
    surfaceVariant = SakuraDarkSurfaceContainer,
    onSurfaceVariant = SakuraDarkMutedText,
    surfaceTint = SakuraPinkDark,
    inverseSurface = SakuraDarkText,
    inverseOnSurface = SakuraDarkSurface,
    inversePrimary = SakuraPink,
    outline = SakuraDarkOutline,
    outlineVariant = SakuraDarkOutlineVariant,
    error = HarleyErrorDark,
    onError = HarleyOnErrorDark,
    errorContainer = HarleyErrorContainerDark,
    onErrorContainer = HarleyOnErrorContainerDark,
    scrim = Color.Black,
    surfaceDim = SakuraDarkBackground,
    surfaceBright = SakuraDarkSurfaceHighest,
    surfaceContainerLowest = SakuraDarkBackground,
    surfaceContainerLow = SakuraDarkSurfaceLow,
    surfaceContainer = SakuraDarkSurfaceContainer,
    surfaceContainerHigh = SakuraDarkSurfaceHigh,
    surfaceContainerHighest = SakuraDarkSurfaceHighest
)

private val SakuraLightColorScheme = lightColorScheme(
    primary = SakuraPink,
    onPrimary = Color.White,
    primaryContainer = SakuraPinkLight,
    onPrimaryContainer = SakuraPinkContainerText,
    secondary = SakuraPurple,
    onSecondary = Color.White,
    secondaryContainer = SakuraPurpleLight,
    onSecondaryContainer = SakuraPurpleContainerText,
    tertiary = SakuraOrange,
    onTertiary = Color.White,
    tertiaryContainer = SakuraOrangeLight,
    onTertiaryContainer = SakuraOrangeContainerText,
    background = SakuraBackground,
    onBackground = SakuraText,
    surface = SakuraSurface,
    onSurface = SakuraText,
    surfaceVariant = SakuraSurfaceContainer,
    onSurfaceVariant = SakuraMutedText,
    surfaceTint = SakuraPink,
    inverseSurface = SakuraDarkSurfaceHighest,
    inverseOnSurface = SakuraDarkText,
    inversePrimary = SakuraPinkDark,
    outline = SakuraOutline,
    outlineVariant = SakuraOutlineVariant,
    error = HarleyError,
    onError = HarleyOnError,
    errorContainer = HarleyErrorContainer,
    onErrorContainer = HarleyOnErrorContainer,
    scrim = Color.Black,
    surfaceDim = SakuraSurfaceHigh,
    surfaceBright = SakuraSurface,
    surfaceContainerLowest = SakuraSurface,
    surfaceContainerLow = SakuraSurfaceLow,
    surfaceContainer = SakuraSurfaceContainer,
    surfaceContainerHigh = SakuraSurfaceHigh,
    surfaceContainerHighest = SakuraSurfaceHighest
)

private val StarryDarkColorScheme = darkColorScheme(
    primary = StarryIndigoDark,
    onPrimary = StarryIndigoDarkOn,
    primaryContainer = StarryIndigoDarkContainer,
    onPrimaryContainer = StarryIndigoDarkContainerText,
    secondary = StarryCyanDark,
    onSecondary = StarryCyanDarkOn,
    secondaryContainer = StarryCyanDarkContainer,
    onSecondaryContainer = StarryCyanDarkContainerText,
    tertiary = StarryGoldDark,
    onTertiary = StarryGoldDarkOn,
    tertiaryContainer = StarryGoldDarkContainer,
    onTertiaryContainer = StarryGoldDarkContainerText,
    background = StarryDarkBackground,
    onBackground = StarryDarkText,
    surface = StarryDarkSurface,
    onSurface = StarryDarkText,
    surfaceVariant = StarryDarkSurfaceContainer,
    onSurfaceVariant = StarryDarkMutedText,
    surfaceTint = StarryIndigoDark,
    inverseSurface = StarryDarkText,
    inverseOnSurface = StarryDarkSurface,
    inversePrimary = StarryIndigo,
    outline = StarryDarkOutline,
    outlineVariant = StarryDarkOutlineVariant,
    error = HarleyErrorDark,
    onError = HarleyOnErrorDark,
    errorContainer = HarleyErrorContainerDark,
    onErrorContainer = HarleyOnErrorContainerDark,
    scrim = Color.Black,
    surfaceDim = StarryDarkBackground,
    surfaceBright = StarryDarkSurfaceHighest,
    surfaceContainerLowest = StarryDarkBackground,
    surfaceContainerLow = StarryDarkSurfaceLow,
    surfaceContainer = StarryDarkSurfaceContainer,
    surfaceContainerHigh = StarryDarkSurfaceHigh,
    surfaceContainerHighest = StarryDarkSurfaceHighest
)

private val StarryLightColorScheme = lightColorScheme(
    primary = StarryIndigo,
    onPrimary = Color.White,
    primaryContainer = StarryIndigoLight,
    onPrimaryContainer = StarryIndigoContainerText,
    secondary = StarryCyan,
    onSecondary = Color.White,
    secondaryContainer = StarryCyanLight,
    onSecondaryContainer = StarryCyanContainerText,
    tertiary = StarryGold,
    onTertiary = Color.White,
    tertiaryContainer = StarryGoldLight,
    onTertiaryContainer = StarryGoldContainerText,
    background = StarryBackground,
    onBackground = StarryText,
    surface = StarrySurface,
    onSurface = StarryText,
    surfaceVariant = StarrySurfaceContainer,
    onSurfaceVariant = StarryMutedText,
    surfaceTint = StarryIndigo,
    inverseSurface = StarryDarkSurfaceHighest,
    inverseOnSurface = StarryDarkText,
    inversePrimary = StarryIndigoDark,
    outline = StarryOutline,
    outlineVariant = StarryOutlineVariant,
    error = HarleyError,
    onError = HarleyOnError,
    errorContainer = HarleyErrorContainer,
    onErrorContainer = HarleyOnErrorContainer,
    scrim = Color.Black,
    surfaceDim = StarrySurfaceHigh,
    surfaceBright = StarrySurface,
    surfaceContainerLowest = StarrySurface,
    surfaceContainerLow = StarrySurfaceLow,
    surfaceContainer = StarrySurfaceContainer,
    surfaceContainerHigh = StarrySurfaceHigh,
    surfaceContainerHighest = StarrySurfaceHighest
)

private val MintDarkColorScheme = darkColorScheme(
    primary = MintGreenDark,
    onPrimary = MintGreenDarkOn,
    primaryContainer = MintGreenDarkContainer,
    onPrimaryContainer = MintGreenDarkContainerText,
    secondary = MintOrangeDark,
    onSecondary = MintOrangeDarkOn,
    secondaryContainer = MintOrangeDarkContainer,
    onSecondaryContainer = MintOrangeDarkContainerText,
    tertiary = MintPurpleDark,
    onTertiary = MintPurpleDarkOn,
    tertiaryContainer = MintPurpleDarkContainer,
    onTertiaryContainer = MintPurpleDarkContainerText,
    background = MintDarkBackground,
    onBackground = MintDarkText,
    surface = MintDarkSurface,
    onSurface = MintDarkText,
    surfaceVariant = MintDarkSurfaceContainer,
    onSurfaceVariant = MintDarkMutedText,
    surfaceTint = MintGreenDark,
    inverseSurface = MintDarkText,
    inverseOnSurface = MintDarkSurface,
    inversePrimary = MintGreen,
    outline = MintDarkOutline,
    outlineVariant = MintDarkOutlineVariant,
    error = HarleyErrorDark,
    onError = HarleyOnErrorDark,
    errorContainer = HarleyErrorContainerDark,
    onErrorContainer = HarleyOnErrorContainerDark,
    scrim = Color.Black,
    surfaceDim = MintDarkBackground,
    surfaceBright = MintDarkSurfaceHighest,
    surfaceContainerLowest = MintDarkBackground,
    surfaceContainerLow = MintDarkSurfaceLow,
    surfaceContainer = MintDarkSurfaceContainer,
    surfaceContainerHigh = MintDarkSurfaceHigh,
    surfaceContainerHighest = MintDarkSurfaceHighest
)

private val MintLightColorScheme = lightColorScheme(
    primary = MintGreen,
    onPrimary = Color.White,
    primaryContainer = MintGreenLight,
    onPrimaryContainer = MintGreenContainerText,
    secondary = MintOrange,
    onSecondary = Color.White,
    secondaryContainer = MintOrangeLight,
    onSecondaryContainer = MintOrangeContainerText,
    tertiary = MintPurple,
    onTertiary = Color.White,
    tertiaryContainer = MintPurpleLight,
    onTertiaryContainer = MintPurpleContainerText,
    background = MintBackground,
    onBackground = MintText,
    surface = MintSurface,
    onSurface = MintText,
    surfaceVariant = MintSurfaceContainer,
    onSurfaceVariant = MintMutedText,
    surfaceTint = MintGreen,
    inverseSurface = MintDarkSurfaceHighest,
    inverseOnSurface = MintDarkText,
    inversePrimary = MintGreenDark,
    outline = MintOutline,
    outlineVariant = MintOutlineVariant,
    error = HarleyError,
    onError = HarleyOnError,
    errorContainer = HarleyErrorContainer,
    onErrorContainer = HarleyOnErrorContainer,
    scrim = Color.Black,
    surfaceDim = MintSurfaceHigh,
    surfaceBright = MintSurface,
    surfaceContainerLowest = MintSurface,
    surfaceContainerLow = MintSurfaceLow,
    surfaceContainer = MintSurfaceContainer,
    surfaceContainerHigh = MintSurfaceHigh,
    surfaceContainerHighest = MintSurfaceHighest
)

/**
 * 为Harley生活助手提供统一的Material 3颜色和字体主题。
 *
 * 使用方法：
 * 在Activity的setContent中使用HarleyAppTheme包裹全部页面内容。
 * 默认跟随系统深色模式，并保持App自己的品牌配色；只有调用方明确开启时才使用系统动态取色。
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统。
 * @param visualTheme 用户选择的角色风格主题，默认使用经典蓝。
 * @param dynamicColor 是否允许使用系统壁纸动态颜色，默认关闭，避免角色主题被系统颜色覆盖。
 * @param content 需要应用主题的Compose页面内容。
 *
 * @return 无返回值，直接输出应用主题后的页面内容。
 */
@Composable
fun HarleyAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    visualTheme: AppVisualTheme = AppVisualTheme.CLASSIC_BLUE,
    dynamicColor: Boolean = false,
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
            shapes = HarleyShapes,
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
