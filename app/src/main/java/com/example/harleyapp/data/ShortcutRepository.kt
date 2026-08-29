package com.example.harleyapp.data

import android.content.Context
import android.util.Log

/**
 * 保存用户选择的快捷应用包名。
 *
 * 使用方法：
 * 使用Application Context创建实例，通过getSelectedPackages读取，通过saveSelectedPackages覆盖保存。
 *
 * @param context Android上下文，内部会转换为Application Context。
 */
class ShortcutRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取用户已选择的快捷应用。
     *
     * @return 包名集合；尚未选择时返回空集合。
     */
    fun getSelectedPackages(): Set<String> {
        return preferences.getStringSet(KEY_SELECTED_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()
    }

    /**
     * 覆盖保存用户选择的快捷应用。
     *
     * @param packageNames 需要显示在首页的应用包名集合。
     *
     * @return 写入成功返回true，否则返回false。
     */
    fun saveSelectedPackages(packageNames: Set<String>): Boolean {
        val success = preferences.edit()
            .putStringSet(KEY_SELECTED_PACKAGES, packageNames.toSet())
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist shortcut packages")
        }

        return success
    }

    private companion object {
        const val TAG = "ShortcutRepository"
        const val PREFERENCE_NAME = "harley_shortcuts"
        const val KEY_SELECTED_PACKAGES = "selected_packages"
    }
}
