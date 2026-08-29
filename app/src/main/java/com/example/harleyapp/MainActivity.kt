package com.example.harleyapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.example.harleyapp.data.AppearanceRepository
import com.example.harleyapp.ui.HarleyApp
import com.example.harleyapp.ui.theme.HarleyAppTheme

class MainActivity : ComponentActivity() {

    /**
     * 创建应用主界面并启用沉浸式边到边显示。
     *
     * 使用方法：
     * 该函数由Android系统在启动MainActivity时自动调用，不需要业务代码手动调用。
     *
     * @param savedInstanceState Activity重建时由系统恢复的状态，首次启动时通常为null。
     *
     * @return 无返回值。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让Compose自行处理状态栏、导航栏与内容区域之间的安全边距。
        enableEdgeToEdge()

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val appearanceRepository = remember {
                AppearanceRepository(applicationContext)
            }
            var darkThemeEnabled by rememberSaveable {
                mutableStateOf(
                    appearanceRepository.isDarkTheme(systemDarkTheme)
                )
            }

            // Compose主题和系统栏图标同步切换，避免白色背景使用白色图标或黑色背景使用黑色图标。
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkThemeEnabled
                    isAppearanceLightNavigationBars = !darkThemeEnabled
                }
            }

            // 主题状态放在MaterialTheme外层，保存成功后立即重组整个页面，实现无需重启的一键切换。
            HarleyAppTheme(
                darkTheme = darkThemeEnabled,
                dynamicColor = false
            ) {
                HarleyApp(
                    isDarkTheme = darkThemeEnabled,
                    onSetDarkTheme = { enabled ->
                        val saved = appearanceRepository.setDarkTheme(enabled)
                        if (saved) {
                            darkThemeEnabled = enabled
                        }
                        saved
                    }
                )
            }
        }
    }
}
