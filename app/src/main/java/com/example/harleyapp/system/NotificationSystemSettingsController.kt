package com.example.harleyapp.system

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.example.harleyapp.notification.NotificationAlertChannels

/**
 * 查询并打开提醒通知所需的系统渠道展示权限与电池后台设置。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面用[isBatteryOptimizationIgnored]展示后台限制状态；用户
 * 主动点击后调用[createBatteryOptimizationIntent]；需要检查横幅、锁屏或振动权限时，可调用
 * [createScheduledReminderChannelSettingsIntent]或[createWechatReminderChannelSettingsIntent]。
 * HarleyApp独立提示音不在本类选择，由App内提示音选择器负责，本类也不会静默修改系统权限。
 *
 * @param context Android上下文，内部转换为Application Context。
 */
class NotificationSystemSettingsController(context: Context) {

    private val applicationContext = context.applicationContext
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)

    /**
     * 判断系统是否已经允许本应用忽略Doze电池优化。
     *
     * @return 已加入不优化名单返回true，否则返回false。
     */
    fun isBatteryOptimizationIgnored(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)
    }

    /**
     * 创建本应用通知设置Intent。
     *
     * @return 优先打开本应用通知渠道总页；系统不支持时回退应用详情页。
     */
    fun createNotificationSettingsIntent(): Intent {
        val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
        return resolveOrAppDetails(notificationIntent)
    }

    /**
     * 创建普通定时提醒渠道的系统展示设置Intent。
     *
     * 使用方法：
     * 需要检查普通提醒是否允许横幅、锁屏和振动时启动本Intent。HarleyApp提示音选择在App内
     * 完成，本页面不再负责选择来电或系统通知铃声。系统不支持直接打开渠道时回退通知总页。
     *
     * @return 指向普通定时强提醒渠道设置页的Intent。
     */
    fun createScheduledReminderChannelSettingsIntent(): Intent {
        return createChannelSettingsIntent(
            NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
        )
    }

    /**
     * 创建微信未查看提醒渠道的系统展示设置Intent。
     *
     * 使用方法：
     * 需要检查微信提醒是否允许横幅、锁屏和振动时启动本Intent。微信与普通提醒的App内提示音
     * 由各自选择器分别保存；系统不支持直接打开渠道时自动回退到本App通知总页。
     *
     * @return 指向微信未查看强提醒渠道设置页的Intent。
     */
    fun createWechatReminderChannelSettingsIntent(): Intent {
        return createChannelSettingsIntent(
            NotificationAlertChannels.WECHAT_REMINDER_CHANNEL_ID
        )
    }

    /**
     * 创建电池优化管理或应用详情Intent。
     *
     * 使用方法：
     * 只能响应用户点击后启动。尚未放行时打开Android电池优化列表，由用户手动把HarleyApp设为
     * “不优化”；已经放行时打开应用详情，便于继续检查小米“自启动”和后台省电设置。本App不
     * 申请可直接弹出豁免确认框的特殊权限，避免不必要的系统与应用商店策略风险。
     *
     * @return 可交给ActivityResultLauncher启动的系统设置Intent。
     */
    fun createBatteryOptimizationIntent(): Intent {
        val packageUri = "package:${applicationContext.packageName}".toUri()
        val targetIntent = if (isBatteryOptimizationIgnored()) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        return resolveOrAppDetails(targetIntent)
    }

    /**
     * 创建指定通知渠道的Android系统设置Intent。
     *
     * @param channelId 已由NotificationManager创建的稳定通知渠道ID。
     *
     * @return 系统支持时打开指定渠道，不支持时回退到本App通知总页。
     */
    private fun createChannelSettingsIntent(channelId: String): Intent {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        return if (channelIntent.resolveActivity(applicationContext.packageManager) != null) {
            channelIntent
        } else {
            createNotificationSettingsIntent()
        }
    }

    /**
     * 确保定制系统缺少目标设置页时仍可打开当前应用详情。
     *
     * @param preferredIntent 首选系统设置Intent。
     *
     * @return 可处理首选Intent时原样返回，否则返回应用详情Intent。
     */
    private fun resolveOrAppDetails(preferredIntent: Intent): Intent {
        if (preferredIntent.resolveActivity(applicationContext.packageManager) != null) {
            return preferredIntent
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${applicationContext.packageName}".toUri()
        )
    }
}
