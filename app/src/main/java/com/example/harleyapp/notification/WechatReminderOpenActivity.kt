package com.example.harleyapp.notification

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.example.harleyapp.MainActivity
import com.example.harleyapp.data.WechatReminderRepository

/**
 * 处理用户点击本App微信等待提醒后的安全跳转，并结束本轮后续提醒。
 *
 * 使用方法：
 * 只由[WechatReminderReceiver]生成的通知PendingIntent启动，不在桌面显示入口。Activity先清除本轮
 * 匿名待提醒状态和后续Alarm，再打开微信；设备未安装微信时回退到本应用首页，最后立即结束自身。
 */
class WechatReminderOpenActivity : Activity() {

    /**
     * 清理提醒状态并执行用户明确触发的微信跳转。
     *
     * @param savedInstanceState Activity重建状态；首次从通知点击进入时通常为null。
     *
     * @return 无返回值。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WechatReminderRepository(applicationContext).clearPendingNotifications()
        WechatReminderScheduler(applicationContext).cancelPendingAlarm()

        val openIntent = packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE_NAME)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ?: Intent(this, MainActivity::class.java)
        runCatching {
            startActivity(openIntent)
        }.onFailure { error ->
            Log.e(TAG, "Failed to open WeChat from reminder", error)
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private companion object {
        const val TAG = "WechatReminderOpen"
        const val WECHAT_PACKAGE_NAME = "com.tencent.mm"
    }
}
