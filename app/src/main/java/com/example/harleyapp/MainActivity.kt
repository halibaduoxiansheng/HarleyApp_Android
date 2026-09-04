package com.example.harleyapp

import android.content.Intent
import android.app.NotificationManager
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
import com.example.harleyapp.charging.ChargingEffectController
import com.example.harleyapp.data.AppearanceRepository
import com.example.harleyapp.data.AppLockRepository
import com.example.harleyapp.data.CompanionRepository
import com.example.harleyapp.model.AppVisualTheme
import com.example.harleyapp.notification.NotificationAlertChannels
import com.example.harleyapp.ui.HarleyApp
import com.example.harleyapp.ui.screens.AppUnlockScreen
import com.example.harleyapp.ui.screens.HalibaduoStartupScreen
import com.example.harleyapp.ui.theme.HarleyAppTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    // 小组件既可能冷启动Activity，也可能把新Intent交给现有Activity，因此使用Compose状态统一驱动导航。
    private var openTodayRequest by mutableStateOf(false)

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

        openTodayRequest = intent?.getBooleanExtra(
            com.example.harleyapp.widget.TodayWeatherWidgetProvider.EXTRA_OPEN_TODAY,
            false
        ) == true

        // 让Compose自行处理状态栏、导航栏与内容区域之间的安全边距。
        enableEdgeToEdge()

        // 提前创建两个强提醒渠道，用户首次收到通知前即可在系统设置中检查声音、振动和横幅选项。
        NotificationAlertChannels.createAll(
            getSystemService(NotificationManager::class.java)
        )

        // 用户已经开启充电动画时，正常进入App也补充恢复常驻服务，兼容厂商重启后未放行自启动的情况。
        ChargingEffectController(applicationContext).ensureMonitoring()

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val appearanceRepository = remember {
                AppearanceRepository(applicationContext)
            }
            val appLockRepository = remember {
                AppLockRepository(applicationContext)
            }
            val startupCompanionProgress = remember {
                CompanionRepository(applicationContext).getProgress(LocalDate.now().toEpochDay())
            }
            var darkThemeEnabled by rememberSaveable {
                mutableStateOf(
                    appearanceRepository.isDarkTheme(systemDarkTheme)
                )
            }
            var visualThemeName by rememberSaveable {
                mutableStateOf(appearanceRepository.getVisualTheme().name)
            }
            var startupAnimationEnabled by rememberSaveable {
                mutableStateOf(appearanceRepository.isStartupAnimationEnabled())
            }
            val visualTheme = AppVisualTheme.fromStoredName(visualThemeName)
            val initialUnlockRequired = remember {
                if (appLockRepository.completeRecoveryIfDue()) {
                    false
                } else {
                    appLockRepository.getState().enabled
                }
            }
            var startupFinished by rememberSaveable {
                // 关闭动画时直接进入后续密码锁或主页；开关只在本次Activity创建时决定是否播放。
                mutableStateOf(!startupAnimationEnabled)
            }
            var unlockedForThisSession by rememberSaveable {
                mutableStateOf(!initialUnlockRequired)
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
                visualTheme = visualTheme,
                dynamicColor = false
            ) {
                when {
                    !startupFinished -> HalibaduoStartupScreen(
                        companionCategory = startupCompanionProgress.category,
                        companionLevel = startupCompanionProgress.level,
                        onFinished = {
                            startupFinished = true
                        }
                    )

                    !unlockedForThisSession -> AppUnlockScreen(
                        repository = appLockRepository,
                        onUnlocked = {
                            unlockedForThisSession = true
                        }
                    )

                    else -> HarleyApp(
                        isDarkTheme = darkThemeEnabled,
                        visualTheme = visualTheme,
                        startupAnimationEnabled = startupAnimationEnabled,
                        openTodayRequest = openTodayRequest,
                        onOpenTodayRequestConsumed = {
                            openTodayRequest = false
                        },
                        onSetDarkTheme = { enabled ->
                            val saved = appearanceRepository.setDarkTheme(enabled)
                            if (saved) {
                                darkThemeEnabled = enabled
                            }
                            saved
                        },
                        onSetVisualTheme = { theme ->
                            val saved = appearanceRepository.setVisualTheme(theme)
                            if (saved) {
                                visualThemeName = theme.name
                            }
                            saved
                        },
                        onSetStartupAnimationEnabled = { enabled ->
                            val saved = appearanceRepository.setStartupAnimationEnabled(enabled)
                            if (saved) {
                                startupAnimationEnabled = enabled
                            }
                            saved
                        },
                        onExitApp = {
                            finish()
                        }
                    )
                }
            }
        }
    }

    /**
     * 接收桌面小组件在Activity已经存在时发送的新导航请求。
     *
     * 使用方法：
     * 由Android系统在singleTop或CLEAR_TOP复用当前Activity时自动调用。函数读取小组件约定的
     * 布尔参数，并通过Compose状态让现有界面进入“今日总览”。
     *
     * @param intent 系统交付的新Intent，可能包含打开今日总览的请求。
     *
     * @return 无返回值。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(
                com.example.harleyapp.widget.TodayWeatherWidgetProvider.EXTRA_OPEN_TODAY,
                false
            )
        ) {
            openTodayRequest = true
        }
    }
}
