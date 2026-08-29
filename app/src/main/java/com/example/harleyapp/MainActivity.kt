package com.example.harleyapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            HarleyAppTheme(dynamicColor = false) {
                HarleyApp()
            }
        }
    }
}
