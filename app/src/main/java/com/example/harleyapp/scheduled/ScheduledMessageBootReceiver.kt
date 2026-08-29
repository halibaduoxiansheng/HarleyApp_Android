package com.example.harleyapp.scheduled

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.harleyapp.data.ScheduledMessageRepository

/**
 * 手机重启后恢复尚未触发的微信图文提醒Alarm。
 *
 * 使用方法：
 * 在AndroidManifest中监听BOOT_COMPLETED，由系统自动调用，不需要用户手动启动。
 */
class ScheduledMessageBootReceiver : BroadcastReceiver() {

    /**
     * 在系统启动完成后读取未来计划并重新提交给AlarmManager。
     *
     * @param context 广播上下文。
     * @param intent 系统启动广播Intent。
     *
     * @return 无返回值；非BOOT_COMPLETED广播直接忽略。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val repository = ScheduledMessageRepository(context.applicationContext)
        val restoredCount = ScheduledMessageScheduler(context.applicationContext)
            .reschedule(repository.getMessages())
        Log.i(TAG, "Restored $restoredCount scheduled WeChat reminders after boot")
    }

    private companion object {
        const val TAG = "ScheduledMessage"
    }
}
