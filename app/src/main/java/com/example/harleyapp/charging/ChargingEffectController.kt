package com.example.harleyapp.charging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * 保存用户是否主动启用全天充电动画监控。
 *
 * 使用方法：
 * 设置页面通过[isEnabled]读取开关，通过[setEnabled]同步保存。开机接收器和前台服务只读取该
 * 明确选择，不会因为安装或升级应用而擅自启动全天监控。
 *
 * @param context Android上下文，内部使用Application Context创建独立SharedPreferences。
 */
class ChargingEffectPreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 查询用户是否已启用全天充电动画监控。
     *
     * @return 已启用返回true；首次安装或用户关闭后返回false。
     */
    fun isEnabled(): Boolean {
        return preferences.getBoolean(KEY_ENABLED, false)
    }

    /**
     * 同步保存全天充电动画监控开关。
     *
     * @param enabled true表示启用，false表示关闭。
     * @return 数据成功落盘返回true，保存失败返回false。
     */
    @SuppressLint("UseKtx")
    fun setEnabled(enabled: Boolean): Boolean {
        // 这里必须使用同步commit取得真实落盘结果，KTX edit扩展只返回Unit，无法向设置页报告保存失败。
        return preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    private companion object {
        const val PREFERENCE_NAME = "charging_effect"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * 统一处理充电动画开关、系统权限入口、监控服务和手动预览。
 *
 * 使用方法：
 * Compose设置卡片创建一个实例并先检查[hasOverlayPermission]与[hasNotificationPermission]；
 * 权限满足后调用[setEnabled]。MainActivity启动时调用[ensureMonitoring]，用于恢复被系统回收但
 * 用户仍保持启用的监控服务。
 *
 * @param context Android上下文，内部仅持有Application Context。
 */
class ChargingEffectController(context: Context) {

    private val applicationContext = context.applicationContext
    private val preferences = ChargingEffectPreferences(applicationContext)

    /**
     * 查询用户保存的充电动画总开关。
     *
     * @return 开关已启用返回true，否则返回false。
     */
    fun isEnabled(): Boolean = preferences.isEnabled()

    /**
     * 查询“显示在其他应用上层”特殊权限。
     *
     * @return Android 6.0以下或系统已经授权时返回true，否则返回false。
     */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(applicationContext)
    }

    /**
     * 查询前台监控服务通知所需的运行时通知权限。
     *
     * @return Android 13以下或POST_NOTIFICATIONS已经授权时返回true，否则返回false。
     */
    fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 创建“显示在其他应用上层”系统设置Intent。
     *
     * @return 优先指向当前应用权限页的Intent；部分新系统可能退化为悬浮窗应用列表。
     */
    fun createOverlayPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${applicationContext.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 创建当前应用系统详情页Intent，供用户配置小米自启动和后台电池策略。
     *
     * @return 指向HarleyApp系统应用详情页的Intent。
     */
    fun createAppDetailsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${applicationContext.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 保存开关并同步启动或停止常驻充电监控服务。
     *
     * @param enabled true表示启用全天监控，false表示停止服务并移除常驻通知。
     * @return 设置与服务操作均成功返回true；缺少悬浮窗权限、保存失败或启动被系统拒绝时返回false。
     */
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && !hasOverlayPermission()) {
            return false
        }
        if (!preferences.setEnabled(enabled)) {
            Log.e(TAG, "Failed to persist charging effect setting")
            return false
        }

        if (!enabled) {
            ChargingMonitorService.stop(applicationContext)
            return true
        }

        if (ChargingMonitorService.start(applicationContext)) {
            return true
        }

        preferences.setEnabled(false)
        return false
    }

    /**
     * 在应用正常启动时恢复用户已经启用的全天监控。
     *
     * @return 开关关闭时返回false；开关开启且系统接受服务启动请求时返回true。
     */
    fun ensureMonitoring(): Boolean {
        if (!preferences.isEnabled()) {
            return false
        }
        return ChargingMonitorService.start(applicationContext)
    }

    /**
     * 从当前可见页面手动播放一次2.5秒充电效果，便于用户在插电前确认视觉样式。
     *
     * @return 系统接受预览页面启动请求返回true，异常时返回false。
     */
    fun showPreview(): Boolean {
        return runCatching {
            applicationContext.startActivity(
                ChargingEffectActivity.createIntent(
                    context = applicationContext,
                    preview = true
                )
            )
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to show charging effect preview", error)
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "ChargingEffectControl"
    }
}
