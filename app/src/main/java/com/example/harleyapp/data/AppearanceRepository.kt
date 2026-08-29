package com.example.harleyapp.data

import android.content.Context
import android.util.Log

/**
 * 使用SharedPreferences保存应用外观设置。
 *
 * 使用方法：
 * 使用Application Context创建仓库。首次读取时把系统当前主题作为defaultValue传入；用户点击
 * “切换到白天模式”或“切换到黑夜模式”后调用setDarkTheme保存，后续启动会继续使用用户选择。
 *
 * @param context Android上下文，内部会转换为Application Context，避免持有Activity。
 */
class AppearanceRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取用户保存的主题模式。
     *
     * @param defaultValue 尚未保存设置时使用的默认值，通常传入系统当前是否为深色主题。
     *
     * @return true表示黑夜模式，false表示白天模式。
     */
    fun isDarkTheme(defaultValue: Boolean): Boolean {
        return preferences.getBoolean(KEY_DARK_THEME, defaultValue)
    }

    /**
     * 同步保存用户选择的主题模式。
     *
     * @param enabled true切换为黑夜模式，false切换为白天模式。
     *
     * @return 成功写入本机存储返回true，失败返回false。
     */
    fun setDarkTheme(enabled: Boolean): Boolean {
        val success = preferences.edit()
            .putBoolean(KEY_DARK_THEME, enabled)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist app appearance")
        }

        return success
    }

    private companion object {
        const val TAG = "AppearanceRepository"
        const val PREFERENCE_NAME = "harley_appearance"
        const val KEY_DARK_THEME = "dark_theme"
    }
}
