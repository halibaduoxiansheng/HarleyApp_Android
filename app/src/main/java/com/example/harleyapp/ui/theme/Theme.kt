package com.example.harleyapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

/**
 * 为Harley生活助手提供统一的Material 3颜色和字体主题。
 *
 * 使用方法：
 * 在Activity的setContent中使用HarleyAppTheme包裹全部页面内容。
 * 默认跟随系统深色模式；可选择启用Android 12及以上的动态取色。
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统。
 * @param dynamicColor 是否允许使用系统壁纸动态颜色，默认启用。
 * @param content 需要应用主题的Compose页面内容。
 *
 * @return 无返回值，直接输出应用主题后的页面内容。
 */
@Composable
fun HarleyAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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

        darkTheme -> HarleyDarkColorScheme
        else -> HarleyLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HarleyTypography,
        content = content
    )
}
