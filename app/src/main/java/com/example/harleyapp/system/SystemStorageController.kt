package com.example.harleyapp.system

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 打开Android或手机厂商提供的系统存储管理页面。
 *
 * 使用方法：
 * 使用Application Context创建实例，用户主动点击“系统存储管理”后调用open。
 * 本类只负责导航，不会代替系统删除文件或结束其他应用。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class SystemStorageController(context: Context) {

    private val applicationContext = context.applicationContext

    /**
     * 优先打开内部存储管理页，不支持时回退到系统设置首页。
     *
     * @return 成功启动系统设置Activity返回true，否则返回false。
     */
    fun open(): Boolean {
        val storageIntent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val targetIntent = if (
            storageIntent.resolveActivity(applicationContext.packageManager) != null
        ) {
            storageIntent
        } else {
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            applicationContext.startActivity(targetIntent)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to open system storage settings", error)
            false
        }
    }

    private companion object {
        const val TAG = "SystemStorage"
    }
}
