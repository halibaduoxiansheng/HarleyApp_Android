package com.example.harleyapp.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.harleyapp.data.ReminderRepository

/**
 * 手机重启后恢复尚未结束的通用通知计划。
 *
 * 使用方法：
 * 在AndroidManifest中监听BOOT_COMPLETED，由系统自动创建并调用，不需要页面主动执行。
 */
class ReminderBootReceiver : BroadcastReceiver() {

    /**
     * 读取本机全部通知计划，并重新提交未来计划或尽快处理关机期间错过的计划。
     *
     * @param context Android广播上下文。
     * @param intent 系统启动完成广播Intent。
     *
     * @return 无返回值；收到其他广播时直接忽略。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val applicationContext = context.applicationContext
        val reminders = ReminderRepository(applicationContext).getReminders()
        val restoredCount = ReminderScheduler(applicationContext).reschedule(reminders)
        Log.i(TAG, "Restored $restoredCount scheduled reminders after boot")
    }

    private companion object {
        const val TAG = "ReminderBootReceiver"
    }
}
