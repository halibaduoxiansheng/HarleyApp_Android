package com.example.harleyapp.reminder

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.notification.WechatNotificationListenerService
import com.example.harleyapp.notification.WechatReminderScheduler

/**
 * 在系统重启、应用升级或重新获得精确闹钟权限后恢复通知计划。
 *
 * 使用方法：
 * 在AndroidManifest中监听BOOT_COMPLETED、MY_PACKAGE_REPLACED和精确闹钟授权广播，由系统
 * 自动创建并调用，不需要用户先打开页面。恢复范围同时包含通用定时提醒和微信等待提醒。
 */
class ReminderBootReceiver : BroadcastReceiver() {

    /**
     * 读取本机全部通知计划，并重新提交未来计划或尽快处理中断期间错过的计划。
     *
     * @param context Android广播上下文。
     * @param intent 系统启动完成、应用升级完成或精确闹钟重新授权广播Intent。
     *
     * @return 无返回值；收到其他广播时直接忽略。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) {
            return
        }

        val applicationContext = context.applicationContext
        val reminders = ReminderRepository(applicationContext).getReminders()
        val restoredCount = ReminderScheduler(applicationContext).reschedule(reminders)
        val wechatReminderRestored = restoreWechatReminder(applicationContext)

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            requestWechatNotificationListenerRebind(applicationContext)
        }

        Log.i(
            TAG,
            "Restored reminders after system event: action=$action, " +
                "scheduledCount=$restoredCount, wechatRestored=$wechatReminderRestored"
        )
    }

    /**
     * 根据持久化配置恢复微信未查看消息的下一次提醒。
     *
     * @param context Application Context，用于读取微信提醒仓库并访问AlarmManager。
     *
     * @return 功能已开启、存在待查看通知且Alarm恢复成功时返回true；无需恢复或失败时返回false。
     */
    private fun restoreWechatReminder(context: Context): Boolean {
        val repository = WechatReminderRepository(context)
        val settings = repository.getSettings()
        val status = repository.getStatus()
        val scheduler = WechatReminderScheduler(context)

        if (!settings.enabled) {
            scheduler.cancel()
            return false
        }
        if (status.pendingNotificationCount <= 0) {
            scheduler.cancelPendingAlarm()
            return false
        }

        return scheduler.restore(
            intervalMinutes = settings.intervalMinutes,
            savedTriggerAtMillis = status.nextReminderAtMillis
        )
    }

    /**
     * 请求Android系统重新绑定微信通知监听服务。
     *
     * 使用方法：
     * 仅在开机或应用覆盖安装完成后调用，修复更新期间服务连接被中断但用户授权仍有效的情况。
     * 用户没有授予通知使用权时系统会保持未连接状态，不会绕过系统授权。
     *
     * @param context 用于定位本应用WechatNotificationListenerService的Android上下文。
     *
     * @return 无返回值；系统拒绝或暂时无法重绑时仅记录英文错误日志，不影响Alarm恢复。
     */
    private fun requestWechatNotificationListenerRebind(context: Context) {
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context, WechatNotificationListenerService::class.java)
            )
        }.onSuccess {
            Log.i(TAG, "Requested WeChat notification listener rebind")
        }.onFailure { error ->
            Log.e(TAG, "Failed to request WeChat notification listener rebind", error)
        }
    }

    private companion object {
        const val TAG = "ReminderBootReceiver"
        const val ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
