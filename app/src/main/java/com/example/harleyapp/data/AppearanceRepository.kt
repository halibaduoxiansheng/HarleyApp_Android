package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.AppVisualTheme

/**
 * 使用SharedPreferences保存应用外观设置。
 *
 * 使用方法：
 * 使用Application Context创建仓库。首次读取时把系统当前主题作为defaultValue传入；用户点击
 * “切换到白天模式”或“切换到黑夜模式”后调用setDarkTheme保存；选择原创角色风格主题后调用
 * [setVisualTheme]保存。后续启动会同时恢复日夜模式和主题配色。
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

    /**
     * 读取用户选择的整体视觉主题。
     *
     * 使用方法：
     * MainActivity创建Compose内容时读取一次，并把结果传给HarleyAppTheme。旧版本没有保存值或
     * 枚举名称已失效时由模型安全回退到经典蓝。
     *
     * @return 当前可用的App视觉主题。
     */
    fun getVisualTheme(): AppVisualTheme {
        return AppVisualTheme.fromStoredName(
            preferences.getString(KEY_VISUAL_THEME, null)
        )
    }

    /**
     * 同步保存用户选择的整体视觉主题。
     *
     * @param theme 需要立即应用并在下次启动恢复的主题。
     *
     * @return 成功写入本机存储返回true，失败返回false。
     */
    fun setVisualTheme(theme: AppVisualTheme): Boolean {
        val success = preferences.edit()
            .putString(KEY_VISUAL_THEME, theme.name)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist app visual theme")
        }
        return success
    }

    private companion object {
        const val TAG = "AppearanceRepository"
        const val PREFERENCE_NAME = "harley_appearance"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_VISUAL_THEME = "visual_theme"
    }
}
