package com.example.harleyapp.browser

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.util.Log

/**
 * 为设置页面提供浏览器收藏悬浮球开关、授权状态和系统入口。
 *
 * 使用方法：
 * Compose设置卡片创建一个实例，先用[isEnabled]与[isAccessibilityServiceEnabled]展示状态。
 * 用户同意显著权限说明后调用[setEnabled]，无障碍服务尚未开启时再启动
 * [createAccessibilitySettingsIntent]。本控制器不能替用户授予无障碍权限。
 *
 * @param context Android上下文，内部只持有Application Context。
 */
class BrowserBookmarkController(context: Context) {

    private val applicationContext = context.applicationContext
    private val preferences = BrowserBookmarkPreferences(applicationContext)

    /**
     * 查询用户保存的悬浮球总开关。
     *
     * @return 用户已主动启用返回true，否则返回false。
     */
    fun isEnabled(): Boolean = preferences.isEnabled()

    /**
     * 保存悬浮球总开关，并通知已经运行的无障碍服务立即刷新显示状态。
     *
     * @param enabled true表示启用，false表示关闭并隐藏悬浮球。
     * @return 设置成功落盘返回true，否则返回false。
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val saved = preferences.setEnabled(enabled)
        if (!saved) {
            Log.e(TAG, "Failed to persist browser bookmark ball setting")
            return false
        }

        BrowserBookmarkAccessibilityService.refreshRunningService()
        return true
    }

    /**
     * 检查Android系统是否已经启用HarleyApp浏览器收藏无障碍服务。
     *
     * @return 系统已启用并能绑定该服务时返回true，否则返回false。
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = applicationContext.getSystemService(
            AccessibilityManager::class.java
        ) ?: return false
        val expectedComponent = ComponentName(
            applicationContext,
            BrowserBookmarkAccessibilityService::class.java
        )

        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                val systemServiceInfo = serviceInfo.resolveInfo.serviceInfo
                ComponentName(systemServiceInfo.packageName, systemServiceInfo.name) ==
                    expectedComponent
            }
    }

    /**
     * 创建Android无障碍服务设置页Intent。
     *
     * @return 指向系统无障碍设置的Intent；用户仍需亲自找到HarleyApp并确认授权。
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val TAG = "BrowserBookmarkControl"
    }
}
